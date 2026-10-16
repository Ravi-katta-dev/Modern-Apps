//! Fetch zoom debouncing across an active gesture (MapLibre's "load while moving" rule).
//!
//! Split from `bridge::handle` (file-length limit). A pinch changes the ideal zoom every
//! frame; fetching every intermediate level storms the workers with tiles that scroll out
//! before they land. While a gesture is active the *fetch* zoom freezes at the
//! gesture-start level and rendering overzooms the resident set (which costs nothing —
//! tessellation is zoom-independent), and the real zoom is fetched once the camera
//! settles. Pan-only motion never freezes: the zoom is unchanged, so the ideal and fetch
//! zooms agree and this is a no-op.
//!
//! Fed by [`set_gesture_active`](crate::bridge::handle::MapHandle::set_gesture_active)
//! from the host's gesture channel; the settle timer runs on frame time so no extra thread
//! or callback is needed.
pub(crate) struct GestureDebounce {
    /// Whether the host reports an active gesture (finger down / fling running).
    active: bool,
    /// The zoom fetches are pinned to while `active`, or `None` outside a gesture.
    frozen_zoom: Option<f64>,
    /// Last frame's camera zoom, for the settle detector when the host hint is absent.
    last_zoom: Option<f64>,
    /// Consecutive settled frames (zoom unchanged within epsilon) while active.
    settled_frames: u32,
}

impl GestureDebounce {
    pub(crate) fn new() -> GestureDebounce {
        GestureDebounce {
            active: false,
            frozen_zoom: None,
            last_zoom: None,
            settled_frames: 0,
        }
    }

    /// How many settled frames end a gesture without a host signal: at 60fps this is
    /// ~250ms of a still camera, matching the Kotlin idle grace. Longer would hold
    /// blurry overzoom after the finger lifts; shorter would unfreeze mid-pinch.
    const SETTLE_FRAMES: u32 = 15;
    /// Zoom changes below this count as settled: Choreographer jitter and float
    /// round-trips through JNI move the zoom by dust, not by levels.
    const SETTLE_EPSILON: f64 = 1e-6;

    /// The host's gesture signal. `true` on finger-down (or fling start), `false` on
    /// release. Freezing starts on the rising edge at the current zoom; the falling
    /// edge releases immediately so the settled zoom fetches on the next frame.
    pub(crate) fn set_active(&mut self, active: bool, zoom: f64) {
        if active && !self.active {
            self.frozen_zoom = Some(zoom);
            self.settled_frames = 0;
        } else if !active {
            self.frozen_zoom = None;
            self.settled_frames = 0;
        }
        self.active = active;
    }

    /// The zoom to select tiles at this frame: the frozen zoom while a gesture is
    /// mid-flight, the live zoom otherwise. Rendering still uses the live camera —
    /// only the *fetch* set freezes, and resident ancestors/descendants cover the gap.
    ///
    /// When the host never signals (car path, older hosts), a still camera settles the
    /// freeze by itself after [`SETTLE_FRAMES`](Self::SETTLE_FRAMES) unchanged frames,
    /// so a stuck-active hint cannot pin the map on overzoom forever.
    pub(crate) fn fetch_zoom(&mut self, zoom: f64) -> f64 {
        let settled = self
            .last_zoom
            .is_some_and(|last| (zoom - last).abs() < Self::SETTLE_EPSILON);
        self.last_zoom = Some(zoom);
        let Some(frozen) = self.frozen_zoom else {
            self.settled_frames = 0;
            return zoom;
        };
        if settled {
            self.settled_frames += 1;
            if self.settled_frames >= Self::SETTLE_FRAMES {
                self.frozen_zoom = None;
                self.settled_frames = 0;
                return zoom;
            }
        } else {
            self.settled_frames = 0;
        }
        frozen
    }

    /// Whether a fetch freeze is currently holding the zoom back. For tests and the
    /// frame log; the fetch path itself reads [`fetch_zoom`](Self::fetch_zoom).
    #[cfg(test)]
    pub(crate) fn is_frozen(&self) -> bool {
        self.frozen_zoom.is_some()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn idle_never_freezes() {
        let mut g = GestureDebounce::new();
        assert_eq!(g.fetch_zoom(14.0), 14.0);
        assert_eq!(g.fetch_zoom(14.5), 14.5);
        assert!(!g.is_frozen());
    }

    #[test]
    fn gesture_freezes_at_start_zoom_until_release() {
        let mut g = GestureDebounce::new();
        g.set_active(true, 14.0);
        // The pinch moves through intermediate zooms; fetches stay pinned.
        assert_eq!(g.fetch_zoom(14.2), 14.0);
        assert_eq!(g.fetch_zoom(14.7), 14.0);
        assert!(g.is_frozen());
        // Release fetches the settled zoom on the very next frame.
        g.set_active(false, 15.0);
        assert_eq!(g.fetch_zoom(15.0), 15.0);
        assert!(!g.is_frozen());
    }

    #[test]
    fn pan_only_motion_never_freezes() {
        // Same zoom in, same zoom out even while "active": a pan carries no new level
        // to fetch, so the freeze is a no-op by construction.
        let mut g = GestureDebounce::new();
        g.set_active(true, 14.0);
        for _ in 0..30 {
            assert_eq!(g.fetch_zoom(14.0), 14.0);
        }
        // …but 30 settled frames release the freeze itself (the settle timer), so a
        // stuck-active hint cannot pin overzoom forever.
        assert!(!g.is_frozen());
    }

    #[test]
    fn stuck_active_settles_by_itself() {
        // The host signalled active and never released (car path, dropped signal):
        // 15 settled frames at the *moved* zoom release the freeze.
        let mut g = GestureDebounce::new();
        g.set_active(true, 14.0);
        assert_eq!(g.fetch_zoom(15.0), 14.0);
        for _ in 0..15 {
            g.fetch_zoom(15.0);
        }
        assert_eq!(g.fetch_zoom(15.0), 15.0);
        assert!(!g.is_frozen());
    }

    #[test]
    fn jitter_does_not_reset_the_settle() {
        // Sub-epsilon zoom dust (JNI float round-trips) counts as settled.
        let mut g = GestureDebounce::new();
        g.set_active(true, 14.0);
        g.fetch_zoom(15.0);
        for i in 0..15 {
            g.fetch_zoom(15.0 + (i as f64) * 1e-9);
        }
        assert_eq!(g.fetch_zoom(15.0), 15.0);
    }
}
