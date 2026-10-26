impl Act {
    pub(crate) fn code(self) -> u32 {
        match self {
            Act::None => 0,
            Act::Relu => 1,
            Act::HardSwish => 2,
            Act::Sigmoid => 3,
            Act::PRelu(_) => 4,
            Act::Clip01 => 5,
            Act::Swish => 6,
            Act::Relu6 => 7,
            Act::Gelu => 8,
        }
    }
}

/// [`Push::res`] / [`Push::shift`] when no addend is folded into the store.
///
/// Arena offset 0 is a live tensor — the first input is pinned there — so "none" needs a value
/// no allocation can hold. [`Builder::finish`] writes real offsets only onto the ops it fuses;
/// everything else keeps this through the manual [`Push`] default below.
pub const NO_FUSE: u32 = u32::MAX;
