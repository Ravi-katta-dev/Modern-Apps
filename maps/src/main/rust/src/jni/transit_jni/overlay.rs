//! Realtime-overlay decoder shared by the offline-transit JNI entry points.
//!
//! Pure move out of `super` (`transit_jni.rs`); no logic changes.

use jni::objects::{JDoubleArray, JIntArray, JObjectArray, JString};
use jni::Env;

/// Decode the realtime overlay's parallel arrays. `coords` is interleaved
/// `[lat, lon, ...]` and `times` is interleaved
/// `[sched_secs, delay_secs, cancelled, ...]`, so one entry spans 2 doubles,
/// 1 string and 3 ints. Any length mismatch yields no overlay rather than a
/// partial one — a wrong fingerprint would silently mis-delay a trip.
pub(super) fn read_delay_entries<'local>(
    env: &mut Env<'local>,
    coords: &JDoubleArray<'local>,
    routes: &JObjectArray<'local>,
    times: &JIntArray<'local>,
) -> Vec<crate::transit::DelayEntry> {
    let n = match routes.len(env) {
        Ok(n) if n > 0 => n,
        _ => return Vec::new(),
    };
    if coords.len(env).unwrap_or(0) != n * 2
        || times.len(env).unwrap_or(0) != n * 3
    {
        return Vec::new();
    }
    let mut ll = vec![0f64; n * 2];
    if coords.get_region(env, 0, &mut ll).is_err() {
        return Vec::new();
    }
    let mut tv = vec![0i32; n * 3];
    if times.get_region(env, 0, &mut tv).is_err() {
        return Vec::new();
    }

    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let name: Option<String> = match routes.get_element(env, i) {
            Ok(obj) => {
                if obj.is_null() {
                    None
                } else {
                    // Convert to an owned String within this statement so the
                    // borrowing `JavaStr` is dropped before `s`.
                    match env.cast_local::<JString>(obj) {
                        Ok(s) => s.try_to_string(env).ok(),
                        Err(_) => None,
                    }
                }
            }
            Err(_) => None,
        };
        let name = match name {
            Some(n) => n,
            None => continue,
        };
        out.push(crate::transit::DelayEntry {
            lat: ll[i * 2],
            lon: ll[i * 2 + 1],
            route_name: name,
            sched_secs: tv[i * 3].max(0) as u32,
            delay_secs: tv[i * 3 + 1],
            cancelled: tv[i * 3 + 2] != 0,
        });
    }
    out
}
