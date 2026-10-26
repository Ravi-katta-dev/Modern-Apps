impl<'a> Builder<'a> {
    /// A learned tensor copied into the arena, so it can be an operand rather than a kernel.
    ///
    /// The only op that reads nothing from the arena. See [`Kind::Constant`] for why it exists
    /// at all; `shape` is what the `.maml` tensor holds, and its element count must match.
    pub fn constant(&mut self, weight_index: usize, shape: Shape) -> Id {
        let weight = self.weight(weight_index, &[shape.c, shape.h, shape.w]);
        let out = self.tensor(shape);
        self.nodes.push(Node::Constant { out, weight });
        out
    }

    /// An embedding lookup: `out[c][t] = table[id(t)][c]`, over a `[1, 1, T]` id tensor, or a
    /// `[2, 1, T]` one when the table has more than [`EMBED_LANE`] rows.
    ///
    /// `table` indexes a `[rows, channels]` tensor. A `sqrt(d_model)` scale applied after the
    /// lookup belongs in the table, folded by the converter, not here; see [`Kind::Embed`].
    pub fn embed(&mut self, ids: Id, table: usize, rows: u32, channels: u32) -> Id {
        let shape = self.shape_of(ids);
        let lanes = if rows > EMBED_LANE { 2 } else { 1 };
        if shape.c != lanes || shape.h != 1 {
            if lanes == 2 {
                // Refused rather than rounded: a single lane holds ids to 2048 exactly and
                // then starts landing on a neighbouring row, which reads as a plausible
                // wrong word rather than as a failure.
                self.fail(format!(
                    "embedding {shape:?}: {rows} rows is past {EMBED_LANE}, so the ids split \
                     across two lanes as a [2, 1, T] tensor"
                ));
            } else {
                self.fail(format!(
                    "embedding {shape:?}: ids are one per position, so a [1, 1, T] tensor"
                ));
            }
        }
        if rows == 0 || channels == 0 {
            self.fail(format!("an embedding table of {rows} x {channels}"));
        }
        let table = self.weight(table, &[rows, channels]);
        let out = self.tensor(Shape::new(channels, 1, shape.w));
        self.nodes.push(Node::Embed { ids, out, table, rows });
        out
    }

    /// [`Builder::attn_scores`] plus a relative-position term.
    ///
    /// `table` is a `[offsets, head_dim]` tensor of learned offsets, shared across heads.
    /// `offsets` must be odd: it is `2 * window + 1`, centred on zero displacement.
    pub fn attn_scores_relative(
        &mut self,
        q: Id,
        k: Id,
        heads: u32,
        table: usize,
        offsets: u32,
    ) -> Id {
        let (out, scale) = self.score_map(q, k, heads, heads);
        let shape = self.shape_of(q);
        // A relative offset is `key - query`, so the two sequences have to be the same one.
        // Only the cross-attention variants take differing lengths.
        if shape.w != self.shape_of(k).w {
            self.fail(format!(
                "a relative score map over q {shape:?} and k {:?}: an offset is `key - query`, \
                 so both are positions in the same sequence",
                self.shape_of(k)
            ));
        }
        let head_dim = shape.c.checked_div(heads.max(1)).unwrap_or(0);
        self.check_offsets(offsets);
        let table = self.weight(table, &[offsets, head_dim]);
        self.nodes.push(Node::AttnScoresRelative { q, k, out, heads, scale, table, offsets });
        out
    }
}
