impl Reference {
    /// Mirrors `shaders/topk.comp`. Stage 1 (`kh == 0`): each of `p.group` slices of the row
    /// writes its 64 best ids as `(lo, hi)` pairs, highest value first, ties by lower id, with
    /// `(2047, 2047)` for missing ranks. Stage 2: the candidates' values re-read from the row at
    /// `p.in1`, the best 64 written as `(value, lo, hi)`.
    fn top_k_stage(&mut self, p: &Push) -> Result<(), String> {
        const K: u32 = 64;
        let order = |a: &(f32, u32), b: &(f32, u32)| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1));
        if p.kh == 0 {
            let groups = p.group.max(1);
            let slice = p.count.div_ceil(groups);
            for group in 0..groups {
                let mut keys = Vec::new();
                for i in group * slice..((group + 1) * slice).min(p.count) {
                    keys.push((self.load(p.in0, i)?, i));
                }
                keys.sort_by(order);
                for r in 0..K {
                    let (lo, hi) = keys.get(r as usize).map_or((2047, 2047), |&(_, i)| (i % 2048, i / 2048));
                    self.store(p.out, (group * K + r) * 2, lo as f32)?;
                    self.store(p.out, (group * K + r) * 2 + 1, hi as f32)?;
                }
            }
            return Ok(());
        }
        let mut keys = Vec::new();
        for c in 0..p.in_c {
            let index = self.load(p.in0, c * 2)? as u32 + 2048 * self.load(p.in0, c * 2 + 1)? as u32;
            if index < p.in_w {
                keys.push((self.load(p.in1, index)?, index));
            }
        }
        keys.sort_by(order);
        for r in 0..K {
            let (value, lo, hi) = keys
                .get(r as usize)
                .map_or((-65504.0, 2047, 2047), |&(v, i)| (v, i % 2048, i / 2048));
            self.store(p.out, r * 3, value)?;
            self.store(p.out, r * 3 + 1, lo as f32)?;
            self.store(p.out, r * 3 + 2, hi as f32)?;
        }
        Ok(())
    }
}
