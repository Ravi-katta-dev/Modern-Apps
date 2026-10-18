//! EGL display, surface, and context: the RAII foundation glow draws through.
//!
//! Lifecycle: `get_display(DEFAULT_DISPLAY)` → `initialize` → `choose_config`
//! (RGBA8888 + depth 24 + ES3 renderable + window surface) →
//! `create_window_surface` on the engine-owned `ANativeWindow` →
//! `create_context` (client version 3) → `make_current` + `swap_interval(1)`.
//! ES 3.0 only (no 3.1 compute) for the Mali-G31. [`Egl::resize`] re-creates
//! the surface on the same display/config/context; [`Egl::teardown`] (also run
//! from `Drop`) unbinds, destroys surface/context, then terminates the
//! display. Never touches the `ANativeWindow` lifetime: the engine owns
//! acquire/release, exactly as on the Vulkan path.
//!
//! `khronos-egl` is used through its `dynamic` backend (`dlopen("libEGL.so")`
//! at runtime): the `static` backend links EGL at compile time via pkg-config,
//! which fails for Android cross-compiles and on hosts without system EGL.

use khronos_egl as egl;
use std::os::raw::c_void;

/// EGL API instance: dynamically loaded `libEGL.so` (EGL 1.4 entry points —
/// everything kart needs is 1.0-era core).
type Api = egl::DynamicInstance<egl::EGL1_4>;

/// Config attributes: 8-bit RGBA, 24-bit depth, ES3-renderable window surface.
const CONFIG_ATTRIBS: &[egl::Int] = &[
    egl::RED_SIZE,
    8,
    egl::GREEN_SIZE,
    8,
    egl::BLUE_SIZE,
    8,
    egl::ALPHA_SIZE,
    8,
    egl::DEPTH_SIZE,
    24,
    egl::RENDERABLE_TYPE,
    egl::OPENGL_ES3_BIT,
    egl::SURFACE_TYPE,
    egl::WINDOW_BIT,
    egl::NONE,
];

/// Context attributes: OpenGL ES client version 3.
const CONTEXT_ATTRIBS: &[egl::Int] = &[egl::CONTEXT_CLIENT_VERSION, 3, egl::NONE];

/// RAII EGL state. Tear down explicitly with [`Self::teardown`] (called from
/// the renderer's `destroy` on the render thread); `Drop` re-runs it
/// idempotently in case destroy was skipped.
pub struct Egl {
    /// Dynamically loaded EGL entry points.
    api: Api,
    /// The default display (terminated on teardown).
    display: egl::Display,
    /// Chosen ES3 window config.
    config: egl::Config,
    /// Window surface (recreated on resize).
    surface: Option<egl::Surface>,
    /// ES 3.0 context.
    context: Option<egl::Context>,
    /// False once [`Self::teardown`] ran (idempotence guard for `Drop`).
    terminated: bool,
}

impl Egl {
    /// Bring up display → config → surface → context → current on `window`.
    ///
    /// # Safety
    /// `window` must be a live `ANativeWindow`; ownership stays with the
    /// caller (the engine releases it, as on the Vulkan path).
    pub unsafe fn new(
        window: *mut crate::vulkan::context::ANativeWindow,
    ) -> Result<Self, String> {
        let api =
            unsafe { Api::load_required() }.map_err(|e| format!("load libEGL: {e:?}"))?;
        let display = unsafe { api.get_display(egl::DEFAULT_DISPLAY) }
            .ok_or_else(|| "eglGetDisplay failed".to_string())?;
        api.initialize(display)
            .map_err(|e| format!("eglInitialize: {e:?}"))?;
        let mut configs = Vec::with_capacity(1);
        api.choose_config(display, CONFIG_ATTRIBS, &mut configs)
            .map_err(|e| format!("eglChooseConfig: {e:?}"))?;
        let config = configs
            .first()
            .copied()
            .ok_or_else(|| "no ES3 window EGL config".to_string())?;
        let mut egl = Self {
            api,
            display,
            config,
            surface: None,
            context: None,
            terminated: false,
        };
        unsafe { egl.create_surface(window)? };
        let context = egl
            .api
            .create_context(display, config, None, CONTEXT_ATTRIBS)
            .map_err(|e| format!("eglCreateContext (ES3): {e:?}"))?;
        egl.context = Some(context);
        egl.make_current()?;
        egl.api
            .swap_interval(display, 1)
            .map_err(|e| format!("eglSwapInterval: {e:?}"))?;
        Ok(egl)
    }

    /// Re-create the window surface on the same display/config/context
    /// (rotation/resize). GL objects survive — no re-upload needed.
    ///
    /// # Safety
    /// `window` must be the live adopted `ANativeWindow`; render thread only.
    pub unsafe fn resize(
        &mut self,
        window: *mut crate::vulkan::context::ANativeWindow,
    ) -> Result<(), String> {
        if let Some(surface) = self.surface.take() {
            let _ = self.unbind();
            let _ = self.api.destroy_surface(self.display, surface);
        }
        unsafe { self.create_surface(window)? };
        self.make_current()?;
        self.api
            .swap_interval(self.display, 1)
            .map_err(|e| format!("eglSwapInterval: {e:?}"))?;
        Ok(())
    }

    /// Present the current back buffer. No fences — this is the present.
    pub fn swap_buffers(&self) -> Result<(), String> {
        match self.surface {
            Some(surface) => self
                .api
                .swap_buffers(self.display, surface)
                .map_err(|e| format!("eglSwapBuffers: {e:?}")),
            None => Err("eglSwapBuffers: no surface".into()),
        }
    }

    /// Resolve a GL entry point for the glow loader (null when absent — glow
    /// treats a null loader result as a missing function).
    pub fn proc_address(&self, name: &str) -> *const c_void {
        self.api
            .get_proc_address(name)
            .map(|f| f as *const c_void)
            .unwrap_or(std::ptr::null())
    }

    /// Unbind, destroy surface/context, terminate the display. Idempotent.
    pub fn teardown(&mut self) {
        if self.terminated {
            return;
        }
        self.terminated = true;
        if self.surface.is_some() || self.context.is_some() {
            let _ = self.unbind();
        }
        if let Some(surface) = self.surface.take() {
            let _ = self.api.destroy_surface(self.display, surface);
        }
        if let Some(context) = self.context.take() {
            let _ = self.api.destroy_context(self.display, context);
        }
        let _ = self.api.terminate(self.display);
    }

    /// Create the window surface on the adopted window.
    unsafe fn create_surface(
        &mut self,
        window: *mut crate::vulkan::context::ANativeWindow,
    ) -> Result<(), String> {
        let surface = unsafe {
            self.api.create_window_surface(
                self.display,
                self.config,
                window as egl::NativeWindowType,
                None,
            )
        }
        .map_err(|e| format!("eglCreateWindowSurface: {e:?}"))?;
        self.surface = Some(surface);
        Ok(())
    }

    /// Bind surface + context to this thread.
    fn make_current(&self) -> Result<(), String> {
        self.api
            .make_current(self.display, self.surface, self.surface, self.context)
            .map_err(|e| format!("eglMakeCurrent: {e:?}"))
    }

    /// Unbind everything from this thread.
    fn unbind(&self) -> Result<(), String> {
        self.api
            .make_current(self.display, None, None, None)
            .map_err(|e| format!("eglMakeCurrent (unbind): {e:?}"))
    }
}

impl Drop for Egl {
    fn drop(&mut self) {
        self.teardown();
    }
}
