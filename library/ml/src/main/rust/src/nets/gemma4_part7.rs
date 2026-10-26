// Multi-query cached attention: T queries against the position-major KV caches, so a batched
// prefill at any position attends everything already in the cache. Query `t` sits at
// `step_params.prefix + t` and sees keys `window_first(prefix + t) ..= prefix + t`. Lowered onto
// the decode kinds (`AttnScoresCached`, `SoftmaxPrefix`, `AttnApplyCached`) with the query count
// in `Push::dil_h` and the window in `Push::dil_w`; at one query those fields are zero and the
// decode path is unchanged.

impl Builder<'_> {
    /// `q` is `[heads * head_dim, 1, T]` (already scaled, as Gemma's `q_norm` carries the
    /// scale), `cache_k`/`cache_v` are `[context, 1, kv_heads * head_dim]` and must already hold
    /// positions `prefix .. prefix + T`. `window` is the sliding window, or 0 for full attention.
    /// Returns `[heads * head_dim, 1, T]`.
    pub fn attn_cached_multi(
        &mut self,
        q: Id,
        cache_k: Id,
        cache_v: Id,
        heads: u32,
        kv_heads: u32,
        window: u32,
    ) -> Id {
        let (sq, sk, sv) = (self.shape_of(q), self.shape_of(cache_k), self.shape_of(cache_v));
        let head_dim = sq.c.checked_div(heads).unwrap_or(0);
        if sq.h != 1 || heads == 0 || head_dim * heads != sq.c {
            self.fail(format!("multi-query attention over q {sq:?} with {heads} heads"));
        }
        if kv_heads == 0 || heads % kv_heads != 0 {
            self.fail(format!("{heads} query heads do not group into {kv_heads} key/value heads"));
        }
        if sk != sv || sk.h != 1 || sk.w != kv_heads * head_dim {
            self.fail(format!("caches {sk:?} / {sv:?} for {kv_heads} heads of {head_dim}"));
        }
        let queries = sq.w;
        let context = sk.c;
        let sliding = window != 0;
        let scores = self.tensor(Shape::new(heads, queries, context));
        self.nodes.push(super::Node::AttnScoresCached {
            q,
            cache: cache_k,
            out: scores,
            heads,
            kv_heads,
            scale: 1.0,
            dynamic: true,
            sliding,
            window,
        });
        let probs = self.tensor(Shape::new(heads, queries, context));
        self.nodes.push(super::Node::Softmax {
            input: scores,
            out: probs,
            mode: super::SoftmaxMode::Prefix,
            sliding,
            window,
        });
        let out = self.tensor(Shape::new(heads * head_dim, 1, queries));
        self.nodes.push(super::Node::AttnApplyCached {
            probs,
            cache: cache_v,
            out,
            heads,
            kv_heads,
            dynamic: true,
            sliding,
            window,
            quant_scale: None,
        });
        out
    }
}
