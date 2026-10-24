use ash::vk;

/// Pick the swapchain present mode: MAILBOX where offered, FIFO otherwise.
///
/// Pure so it is host-testable: no device, just the driver's offered list. MAILBOX is
/// tear-free like FIFO but replaces the queued frame instead of waiting behind it, so
/// a 60fps gesture presents newer poses; FIFO is the spec-mandated fallback and the
/// previous behaviour. IMMEDIATE is never chosen even when it is the only other
/// option — tearing on a map is worse than vsync latency — so a driver offering only
/// IMMEDIATE keeps the old `present_modes[0]` answer.
pub(super) fn choose_present_mode(present_modes: &[vk::PresentModeKHR]) -> vk::PresentModeKHR {
    if present_modes.contains(&vk::PresentModeKHR::MAILBOX) {
        vk::PresentModeKHR::MAILBOX
    } else if present_modes.contains(&vk::PresentModeKHR::FIFO) {
        vk::PresentModeKHR::FIFO
    } else {
        // Unreachable on a conformant driver (FIFO is mandatory), but the old code
        // indexed [0] here and a panic on a non-conformant driver is worse than a
        // torn frame. `new` already errors on an empty format list; an empty mode
        // list fails below at creation with the driver's own error.
        present_modes.first().copied().unwrap_or(vk::PresentModeKHR::FIFO)
    }
}

/// The creation-time log line names the negotiated mode, so a device run can confirm
/// which path the driver took without a second build. By discriminant rather than
/// `Debug`: `ash`'s bitflag-style enums do not implement it.
pub(super) fn present_mode_name(mode: vk::PresentModeKHR) -> &'static str {
    match mode {
        vk::PresentModeKHR::IMMEDIATE => "IMMEDIATE",
        vk::PresentModeKHR::MAILBOX => "MAILBOX",
        vk::PresentModeKHR::FIFO => "FIFO",
        vk::PresentModeKHR::FIFO_RELAXED => "FIFO_RELAXED",
        _ => "UNKNOWN",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// MAILBOX wins where offered (tear-free and fresher through gestures); FIFO is
    /// the mandated fallback and the old behaviour; IMMEDIATE is never picked even
    /// when it is the only other option.
    ///
    /// Android-gated like the rest of this module (`ash` is an Android-only
    /// dependency), so `cargo test` on the host never runs it — it compiles and runs
    /// under the Android test target instead. The creation-time logcat line naming
    /// the negotiated mode is the on-device proof in the meantime.
    #[test]
    fn mailbox_wins_over_fifo_and_immediate_is_never_picked() {
        use vk::PresentModeKHR::*;
        assert_eq!(
            choose_present_mode(&[FIFO, MAILBOX, IMMEDIATE]),
            MAILBOX,
            "tear-free with the freshest queued frame"
        );
        assert_eq!(
            choose_present_mode(&[FIFO, IMMEDIATE]),
            FIFO,
            "the old behaviour where MAILBOX is absent"
        );
        assert_eq!(
            choose_present_mode(&[IMMEDIATE]),
            IMMEDIATE,
            "a non-conformant driver keeps the old [0] answer, not a panic"
        );
        assert_eq!(
            choose_present_mode(&[]),
            FIFO,
            "empty degrades to the mandated mode for the creation error below"
        );
    }
}
