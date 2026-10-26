impl<'a> Builder<'a> {
    fn emit_last(
        &self,
        node: &Node,
        at: &dyn Fn(Id) -> Result<u32, String>,
        shape: &dyn Fn(Id) -> Shape,
        ops: &mut Vec<Op>,
    ) -> Result<(), String> {
        match node {
            Node::AttnApply { probs, v, out, heads, kv_heads } => {
                let (sv, so) = (shape(*v), shape(*out));
                let (in0, in1) = (at(*probs)?, at(*v)?);
                // The tiled shader reads both operands four keys per load and owns four channels of
                // one head per invocation. A single query would waste three of every four lanes'
                // work, so a decode step stays on the per-element shader.
                let head_dim = so.c / (*heads).max(1);
                let tiled = so.w >= 8
                    && sv.w % 4 == 0
                    && head_dim % 4 == 0
                    && in0 % 4 == 0
                    && in1 % 4 == 0;
                let tiles = so.c.div_ceil(ATTN_TILE) * so.w.div_ceil(ATTN_TILE);
                let (kw, count, invocations) =
                    if tiled { (1, tiles, tiles * 64) } else { (0, so.len(), so.len()) };
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApply,
                    push: Push {
                        in0,
                        in1,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        // The **key** count, which is V's length. `out_w` is the query count,
                        // and for a cross-attention those differ.
                        in_w: sv.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        kv_heads: *kv_heads,
                        count,
                        kw,
                        ..Push::default()
                    },
                    invocations,
                });
            }
            Node::Rotary { input, angles, out, heads, axes } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Rotary,
                    push: Push {
                        in0: at(*input)?,
                        in1: at(*angles)?,
                        out: at(*out)?,
                        // The head width, which is what the frequency index wraps on. Not the
                        // channel count.
                        in_c: so.c / heads.max(&1),
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        rope_axes: *axes,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Constant { out, weight } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Constant,
                    push: Push {
                        out: at(*out)?,
                        weight: *weight,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            other => self.emit_top_k(other, at, shape, ops)?,
        }
        Ok(())
    }
}
