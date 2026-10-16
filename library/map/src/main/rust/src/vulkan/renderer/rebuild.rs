use super::Renderer;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;

impl Renderer {
    pub fn resize(&mut self, width: u32, height: u32) {
        if width == self.width && height == self.height {
            return;
        }
        self.width = width;
        self.height = height;
        self.needs_rebuild = true;
    }

    /// Rebuild the swapchain after a resize, rotation or out-of-date present.
    pub(super) fn rebuild(&mut self) -> Result<(), String> {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            // The pipelines reference the old render pass, so they go with it. This is the
            // expensive part and the reason `pipeline_cache` exists: without it every one of the
            // twelve is recompiled from SPIR-V here, on the Choreographer callback, inside a
            // frame.
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            self.swapchain = Swapchain::new(&self.context, self.width, self.height)?;
            self.pipelines = Pipelines::new(
                &self.context.device,
                self.swapchain.render_pass,
                self.swapchain.samples,
                Some(self.atlas_set.layout),
                self.pipeline_cache.handle(),
            )?;
            // Only writes if the driver actually added something — a rebuild to the same sample
            // count adds nothing, so the steady state costs no I/O on the frame path.
            self.pipeline_cache.persist(&self.context.device);
        }
        self.width = self.swapchain.extent.width;
        self.height = self.swapchain.extent.height;
        Ok(())
    }
}

impl Drop for Renderer {
    fn drop(&mut self) {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            // Tile pools first (fresh pools destroy, arena ranges do not — see
            // `upload_tail::free_tile_set`), then the retiring queue the same way,
            // then the arenas themselves. Order matters: the tiles borrow nothing
            // from the pool, but freeing arenas first would leave the tile loops
            // comparing against dead handles.
            let tiles: Vec<super::ResidentTile> =
                std::mem::take(&mut self.tiles).into_values().collect();
            super::upload_tail::free_tile_set(self, tiles, &self.context.device);
            let retiring: Vec<(usize, super::ResidentTile)> =
                std::mem::take(&mut self.retiring);
            for (_, tile) in &retiring {
                super::upload_tail::free_retired_tile(self, tile, &self.context.device);
            }
            super::upload_pool::destroy_pool(&mut self.upload_pool, &self.context.device);
            if let Some(route) = &self.route {
                route.vertices.destroy(&self.context.device);
                route.indices.destroy(&self.context.device);
            }
            if let Some(rails) = &self.rail_lines {
                rails.vertices.destroy(&self.context.device);
                rails.indices.destroy(&self.context.device);
            }
            for transient in &self.transients {
                transient.vbuf.destroy(&self.context.device);
                transient.ibuf.destroy(&self.context.device);
            }
            self.transients.clear();
            for ring in &self.scratch {
                ring.destroy(&self.context.device);
            }
            self.scratch.clear();
            super::upload_pool::destroy_pool(&mut self.upload_pool, &self.context.device);
            for frame in &self.frames {
                self.context.device.destroy_fence(frame.in_flight, None);
                self.context
                    .device
                    .destroy_semaphore(frame.image_available, None);
                self.context
                    .device
                    .destroy_semaphore(frame.render_finished, None);
            }
            self.context
                .device
                .destroy_command_pool(self.command_pool, None);
            if let Some(image) = &self.glyph_atlas {
                image.destroy(&self.context.device);
            }
            if let Some(image) = &self.sprite_atlas {
                image.destroy(&self.context.device);
            }
            if let Some(image) = &self.moon_color {
                image.destroy(&self.context.device);
            }
            if let Some(image) = &self.moon_dem {
                image.destroy(&self.context.device);
            }
            if let Some(pool) = &self.moon_pool {
                self.context.device.destroy_descriptor_pool(*pool, None);
            }
            for (_, retired) in &self.moon_retiring {
                match retired {
                    super::upload_moon::MoonRetired::Image(image) => {
                        image.destroy(&self.context.device)
                    }
                    super::upload_moon::MoonRetired::Pool(pool) => self
                        .context
                        .device
                        .destroy_descriptor_pool(*pool, None),
                }
            }
            self.moon_retiring.clear();
            self.quad.vertices.destroy(&self.context.device);
            self.quad.indices.destroy(&self.context.device);
            self.pick.destroy(&self.context.device);
            self.pipeline_cache.destroy(&self.context.device);
            self.atlas_set.destroy(&self.context.device);
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            // `context`'s own Drop destroys the device, surface and instance after this.
            if !self.window.is_null() {
                crate::vulkan::context::ANativeWindow_release(self.window);
                self.window = std::ptr::null_mut();
            }
        }
    }
}
