//! Midpoint subdivision of long triangle edges, for geometry the globe shaders bend.
//!
//! The `*_globe.vert` shaders move only vertices onto the sphere; the rasteriser fills
//! between them in straight lines. A z0–z7 tile edge spans many degrees, so without
//! extra vertices it cuts a chord through the ball, the planet reads as faceted, and a
//! triangle straddling the limb folds its far corner back over the disc.
//!
//! On the flat map a split is exact for fills (every attribute is used linearly) and
//! near-exact for strokes: the only product of two varying attributes is the miter
//! normal times the extrude multiplier, and a split diagonal follows that bilinear
//! form at its midpoint rather than the straight line between the corners. The
//! difference is confined to the join ends of a segment and to globe-zoom tiles.
use std::collections::HashMap;

/// The longest edge, in tile-local units, kept for a tile at zoom `z`.
///
/// Chord sag on a ball of radius `r` across an angle `a` is `r * a^2 / 8`. With the
/// globe radius `128 * 2^zoom` Dp, a camera at most half a level past the tile's zoom,
/// and an edge of `e` tile units spanning `2 * pi * e / 2^z` radians, sag stays under
/// half a Dp when `e <= sqrt(2^z / 2524)` — about 1/50 of a tile at z0, a third at z8.
pub fn globe_max_edge(z: u8) -> f32 {
    (2f32.powi(i32::from(z)) / 2524.0).sqrt().min(1.0)
}

/// Split every edge longer than `max_edge` at its midpoint, recursively, until none is.
///
/// Length is measured on each vertex's first two floats (tile-local x/y); every float of
/// a new vertex is the average of its edge's endpoints. Whether an edge splits, and where
/// its midpoint lands, depend only on its two endpoints, so neighbouring triangles split
/// a shared edge identically and no crack opens between them — whether they share the
/// vertex indices or only the positions.
pub fn subdivide(vertices: &mut Vec<f32>, indices: &mut Vec<u32>, fpv: usize, max_edge: f32) {
    let limit = max_edge * max_edge;
    let long = |v: &[f32], a: u32, b: u32| {
        let (a, b) = (a as usize * fpv, b as usize * fpv);
        let (dx, dy) = (v[a] - v[b], v[a + 1] - v[b + 1]);
        dx * dx + dy * dy > limit
    };
    let any_long = indices.chunks_exact(3).any(|t| {
        long(vertices, t[0], t[1]) || long(vertices, t[1], t[2]) || long(vertices, t[2], t[0])
    });
    if !any_long {
        return;
    }

    let mut midpoints: HashMap<(u32, u32), u32> = HashMap::new();
    let mut midpoint = |v: &mut Vec<f32>, a: u32, b: u32| -> u32 {
        *midpoints.entry((a.min(b), a.max(b))).or_insert_with(|| {
            let (pa, pb) = (a as usize * fpv, b as usize * fpv);
            for k in 0..fpv {
                let m = (v[pa + k] + v[pb + k]) * 0.5;
                v.push(m);
            }
            (v.len() / fpv - 1) as u32
        })
    };

    let mut stack: Vec<[u32; 3]> = indices.chunks_exact(3).map(|t| [t[0], t[1], t[2]]).collect();
    stack.reverse();
    let mut out = Vec::with_capacity(indices.len());
    while let Some(mut t) = stack.pop() {
        let mut split =
            [long(vertices, t[0], t[1]), long(vertices, t[1], t[2]), long(vertices, t[2], t[0])];
        let count = split.iter().filter(|&&s| s).count();
        // Rotate so one split edge is first (count 1) or the unsplit edge last (count 2).
        // Rotation keeps the winding.
        for _ in 0..3 {
            let canonical = match count {
                1 => split[0],
                2 => !split[2],
                _ => true,
            };
            if canonical {
                break;
            }
            t = [t[1], t[2], t[0]];
            split = [split[1], split[2], split[0]];
        }
        let [a, b, c] = t;
        match count {
            0 => out.extend_from_slice(&t),
            1 => {
                let ab = midpoint(vertices, a, b);
                stack.push([a, ab, c]);
                stack.push([ab, b, c]);
            }
            2 => {
                let ab = midpoint(vertices, a, b);
                let bc = midpoint(vertices, b, c);
                stack.push([ab, b, bc]);
                stack.push([a, ab, bc]);
                stack.push([a, bc, c]);
            }
            _ => {
                let ab = midpoint(vertices, a, b);
                let bc = midpoint(vertices, b, c);
                let ca = midpoint(vertices, c, a);
                stack.push([a, ab, ca]);
                stack.push([ab, b, bc]);
                stack.push([ca, bc, c]);
                stack.push([ab, bc, ca]);
            }
        }
    }
    *indices = out;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn area(v: &[f32], fpv: usize, idx: &[u32]) -> f32 {
        idx.chunks_exact(3)
            .map(|t| {
                let p = |i: u32| (v[i as usize * fpv], v[i as usize * fpv + 1]);
                let (a, b, c) = (p(t[0]), p(t[1]), p(t[2]));
                ((b.0 - a.0) * (c.1 - a.1) - (c.0 - a.0) * (b.1 - a.1)) * 0.5
            })
            .sum()
    }

    fn max_edge(v: &[f32], fpv: usize, idx: &[u32]) -> f32 {
        let len = |a: u32, b: u32| {
            let (a, b) = (a as usize * fpv, b as usize * fpv);
            ((v[a] - v[b]).powi(2) + (v[a + 1] - v[b + 1]).powi(2)).sqrt()
        };
        idx.chunks_exact(3)
            .flat_map(|t| [len(t[0], t[1]), len(t[1], t[2]), len(t[2], t[0])])
            .fold(0.0, f32::max)
    }

    #[test]
    fn short_geometry_is_left_untouched() {
        let mut v = vec![0.0, 0.0, 0.0, 0.1, 0.0, 0.0, 0.0, 0.1, 0.0];
        let mut i = vec![0, 1, 2];
        subdivide(&mut v, &mut i, 3, 0.25);
        assert_eq!(v.len(), 9);
        assert_eq!(i, vec![0, 1, 2]);
    }

    #[test]
    fn a_full_tile_quad_splits_to_the_limit_and_keeps_its_area_and_winding() {
        let mut v = vec![0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0];
        let mut i = vec![0, 1, 2, 0, 2, 3];
        let before = area(&v, 3, &i);
        subdivide(&mut v, &mut i, 3, 0.1);
        assert!(max_edge(&v, 3, &i) <= 0.1);
        assert!((area(&v, 3, &i) - before).abs() < 1e-5);
        // Every triangle keeps the original (positive) winding.
        for t in i.chunks_exact(3) {
            assert!(area(&v, 3, t) > 0.0);
        }
    }

    #[test]
    fn attributes_are_interpolated_linearly() {
        // Third float = 2x + 3y + 1 at every corner, so every new vertex must carry it too.
        let f = |x: f32, y: f32| 2.0 * x + 3.0 * y + 1.0;
        let mut v = vec![0.0, 0.0, f(0.0, 0.0), 1.0, 0.0, f(1.0, 0.0), 0.0, 1.0, f(0.0, 1.0)];
        let mut i = vec![0, 1, 2];
        subdivide(&mut v, &mut i, 3, 0.2);
        for p in v.chunks_exact(3) {
            assert!((p[2] - f(p[0], p[1])).abs() < 1e-5);
        }
    }

    #[test]
    fn neighbours_with_separate_vertices_split_a_shared_edge_identically() {
        // Two triangles sharing the diagonal (1,0)-(0,1) by position only, with different
        // third corners so their other edges split differently.
        let mut v = vec![
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, // A: 0 1 2
            1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 3.0, 3.0, 0.0, // B: 3 4 5
        ];
        let mut i = vec![0, 1, 2, 3, 4, 5];
        subdivide(&mut v, &mut i, 3, 0.3);
        let on_diagonal = |tri: &[u32]| -> Vec<(i64, i64)> {
            let mut pts: Vec<(i64, i64)> = tri
                .iter()
                .map(|&k| (v[k as usize * 3], v[k as usize * 3 + 1]))
                .filter(|(x, y)| (x + y - 1.0).abs() < 1e-6)
                .map(|(x, y)| ((x * 1e6).round() as i64, (y * 1e6).round() as i64))
                .collect();
            pts.sort_unstable();
            pts.dedup();
            pts
        };
        // Triangles from A have their centroid below the diagonal; B's lie above it.
        let side = |t: &[u32]| {
            let s: f32 = t.iter().map(|&k| v[k as usize * 3] + v[k as usize * 3 + 1]).sum();
            s / 3.0 < 1.0
        };
        let collect = |from_a: bool| {
            let mut pts: Vec<(i64, i64)> =
                i.chunks_exact(3).filter(|t| side(t) == from_a).flat_map(on_diagonal).collect();
            pts.sort_unstable();
            pts.dedup();
            pts
        };
        assert_eq!(collect(true), collect(false));
        assert!(collect(true).len() > 2);
    }

    #[test]
    fn the_edge_limit_loosens_with_zoom() {
        assert!(globe_max_edge(0) < 0.03);
        assert!(globe_max_edge(8) > globe_max_edge(0) * 10.0);
        assert!(globe_max_edge(8) <= 1.0);
    }
}
