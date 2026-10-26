// Device top-k over a logits row (`shaders/topk.comp`): Gemma's sampler draws from 64
// candidates, so 64 `(value, id)` triples come back instead of the 262,144-entry row.

/// Candidates per row. `topk.comp` hard-codes the same count as `K`.
pub const TOP_K: u32 = 64;

impl Builder<'_> {
    /// The [`TOP_K`] largest of `row` (any shape, read flat), highest first, ties by lower
    /// index, as `[value, lo, hi] x TOP_K` with `id = hi * 2048 + lo`. Ranks past the row's
    /// length have `lo = hi = 2047`.
    pub fn top_k(&mut self, row: Id) -> Id {
        let len = self.shape_of(row).len();
        let groups = super::argmax_groups(len).max(1);
        let partials = self.tensor(Shape::new(groups * TOP_K * 2, 1, 1));
        self.nodes.push(super::Node::TopK { input: row, row, out: partials, fold: false });
        let out = self.tensor(Shape::new(TOP_K * 3, 1, 1));
        self.nodes.push(super::Node::TopK { input: partials, row, out, fold: true });
        out
    }

    /// The emit arm for [`super::Node::TopK`]; every other node emits nothing here.
    pub(crate) fn emit_top_k(
        &self,
        node: &super::Node,
        at: &dyn Fn(Id) -> Result<u32, String>,
        shape: &dyn Fn(Id) -> Shape,
        ops: &mut Vec<super::Op>,
    ) -> Result<(), String> {
        let super::Node::TopK { input, row, out, fold } = node else { return Ok(()) };
        let (si, sr, so) = (shape(*input), shape(*row), shape(*out));
        let common = super::Push {
            in0: at(*input)?,
            out: at(*out)?,
            out_c: so.c,
            out_h: so.h,
            out_w: so.w,
            ..super::Push::default()
        };
        let (push, invocations) = if *fold {
            let push = super::Push { in1: at(*row)?, in_c: si.len() / 2, in_w: sr.len(), kh: 1, count: TOP_K, ..common };
            (push, 64)
        } else {
            let groups = super::argmax_groups(si.len()).max(1);
            (super::Push { in_c: si.len(), group: groups, count: si.len(), ..common }, groups * 64)
        };
        ops.push(super::Op::Dispatch { kind: super::Kind::TopK, push, invocations });
        Ok(())
    }
}
