/// The RAIL_LINES section: every line the basemap's rail layer draws, as `transit_shapes` reads it
/// out of the raw feeds, so that layer can be rebuilt from the pack alone.
///
/// Host-only. The device never reads it, and `mamaps_pack` writes it out of the app archive, so
/// it is self-contained (its own strings and blobs) rather than adding to STRINGS or SHAPE_COORDS,
/// which ship. Layout:
///
/// ```text
/// u32 line_count
/// line_count x { u32 feed_idx, route_type, color (0 = none), name_off, route_id_off, blob_off }
/// u32 strings_len, strings (NUL-terminated; offsets above index it, NONE = empty)
/// blobs (shapes::encode, deduplicated; blob_off indexes from here)
/// ```
///
/// Lines are in feed order, then route_id, then shape_id ascending: the order the raw path builds
/// them in, which is what its order-dependent dedup needs to reach the same output.
struct RailLines {
    records: Vec<u8>,
    count: u32,
    pool: StringPool,
    blobs: ShapeBlobs,
}

pub const RAIL_LINE_REC_BYTES: usize = 24;

impl RailLines {
    fn new() -> RailLines {
        RailLines { records: Vec::new(), count: 0, pool: StringPool::new(), blobs: ShapeBlobs::new() }
    }

    /// Every whole, unsimplified shape of every rail route in one feed.
    fn add_feed(&mut self, feed_idx: u32, feed: &FeedView) -> Result<(), String> {
        let Some(shapes) = feed.shapes else { return Ok(()) };
        let rail = crate::rail::rail_routes(feed.routes);
        for (route_id, shape_ids) in &crate::rail::shape_ids_by_route(feed.trips, &rail) {
            let (_, color, fallback, name, route_type) = &rail[route_id.as_str()];
            let raw_color = if *fallback { 0 } else { *color };
            for shape_id in shape_ids {
                let Some(shape) = shapes.get(shape_id.as_str()) else { continue };
                if shape.lat_e7.len() < 2 {
                    continue;
                }
                let points: Vec<(i32, i32)> =
                    shape.lat_e7.iter().zip(&shape.lon_e7).map(|(&lat, &lon)| (lat, lon)).collect();
                let blob_off = self.blobs.intern(&shapes::encode(&points))?;
                for v in [feed_idx, *route_type, raw_color, self.pool.intern(name), self.pool.intern(route_id), blob_off] {
                    append_u32(&mut self.records, v);
                }
                self.count += 1;
            }
        }
        if self.pool.overflowed {
            return Err("RAIL_LINES strings passed 4 GiB".to_string());
        }
        Ok(())
    }

    fn into_section(self) -> Vec<u8> {
        let mut out = Vec::with_capacity(8 + self.records.len() + self.pool.bytes.len() + self.blobs.bytes.len());
        append_u32(&mut out, self.count);
        out.extend_from_slice(&self.records);
        append_u32(&mut out, self.pool.bytes.len() as u32);
        out.extend_from_slice(&self.pool.bytes);
        out.extend_from_slice(&self.blobs.bytes);
        out
    }
}
