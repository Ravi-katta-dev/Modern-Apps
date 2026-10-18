//! Offline scheduled departure board JNI: `getStopDeparturesNative`.
//!
//! Pure move out of `super` (`transit_jni.rs`); no logic changes.

use jni::objects::{JDoubleArray, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jdouble, jint, jobjectArray};
use jni::{EnvUnowned, jni_sig, jni_str};

use super::cache::transit_index;
use super::overlay::read_delay_entries;

// ---------------------------------------------------------------------------
// JNI: getStopDeparturesNative (offline scheduled departure board)
// ---------------------------------------------------------------------------

/// Offline scheduled departure board from the `<base>/<feed>.transit` index for
/// the stop nearest `(lat,lon)`. `dep_secs` = seconds since local midnight,
/// `weekday` 0=Mon..6=Sun, `date` yyyymmdd. Returns
/// `OfflineRouter.RawDeparture[]` (sorted, upcoming, scheduled-only) or `null`
/// when the feed is absent / doesn't cover the point — the Kotlin side then
/// keeps the online board.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getStopDeparturesNative<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    lat: jdouble,
    lon: jdouble,
    dep_secs: jint,
    weekday: jint,
    date: jint,
    prev_weekday: jint,
    prev_date: jint,
    overlay_coords: JDoubleArray<'local>,
    overlay_routes: JObjectArray<'local>,
    overlay_times: JIntArray<'local>,
    max: jint,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let null = std::ptr::null_mut();
        let base: String = match base_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(null),
        };
        let feed_name: String = match feed.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(null),
        };

        let index = match transit_index(&base, &feed_name) {
            Some(i) => i,
            None => return Ok(null),
        };
        if !index.covers(lat, lon) {
            return Ok(null);
        }

        let entries =
            read_delay_entries(&mut *env, &overlay_coords, &overlay_routes, &overlay_times);
        let overlay = if entries.is_empty() {
            None
        } else {
            Some(crate::transit::DelayOverlay::build(&index, &entries))
        };

        let deps = crate::transit::stop_departures(
            &index,
            lat,
            lon,
            dep_secs.max(0) as u32,
            crate::transit::Schedule {
                day: crate::transit::QueryDay {
                    weekday: weekday.max(0) as u32,
                    date: date.max(0) as u32,
                    prev_weekday: prev_weekday.max(0) as u32,
                    prev_date: prev_date.max(0) as u32,
                },
                overlay: overlay.as_ref(),
            },
            max.max(0) as usize,
        );

        let class = match env.find_class(jni_str!("com/vayunmathur/maps/util/OfflineRouter$RawDeparture")) {
            Ok(c) => c,
            Err(_) => return Ok(null),
        };
        // MUST match the RawDeparture ctor: 4 strings, 4 ints, 2 bools, then the
        // packed trip id (J) LAST so older descriptors never renumber.
        let ctor =
            jni_sig!("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIIZZJ)V");
        let array = match env.new_object_array(deps.len() as i32, &class, JObject::null()) {
            Ok(a) => a,
            Err(_) => return Ok(null),
        };

        for (i, d) in deps.iter().enumerate() {
            let jroute: JObject = match env.new_string(&d.route_name) {
                Ok(s) => s.into(),
                Err(_) => return Ok(null),
            };
            let jheadsign: JObject = match env.new_string(&d.headsign) {
                Ok(s) => s.into(),
                Err(_) => return Ok(null),
            };
            let jfeed: JObject = match env.new_string(&d.feed) {
                Ok(s) => s.into(),
                Err(_) => return Ok(null),
            };
            let jcode: JObject = match env.new_string(&d.stop_code) {
                Ok(s) => s.into(),
                Err(_) => return Ok(null),
            };
            let obj = match env.new_object(
                &class,
                &ctor,
                &[
                    JValue::Object(&jroute),
                    JValue::Object(&jheadsign),
                    JValue::Object(&jfeed),
                    JValue::Object(&jcode),
                    JValue::Int(d.route_color as i32),
                    JValue::Int(d.route_type as i32),
                    JValue::Int(d.dep_secs as i32),
                    JValue::Int(d.delay_secs),
                    JValue::Bool(d.cancelled),
                    JValue::Bool(d.real_time),
                    JValue::Long(d.trip_id),
                ],
            ) {
                Ok(o) => o,
                Err(_) => return Ok(null),
            };
            if array.set_element(env, i, &obj).is_err() {
                return Ok(null);
            }
        }

        Ok(array.into_raw())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
