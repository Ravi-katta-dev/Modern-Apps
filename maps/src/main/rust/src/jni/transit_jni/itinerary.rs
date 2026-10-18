//! Trip-itinerary JNI: `getTripItineraryNative`.
//!
//! Decodes a vehicle id back to its trip and returns the full stop-by-stop
//! run for the vehicle-details sheet.

use jni::objects::{JDoubleArray, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jint, jlong, jobject};
use jni::{EnvUnowned, jni_sig, jni_str};

use super::cache::transit_index;
use super::overlay::read_delay_entries;

// ---------------------------------------------------------------------------
// JNI: getTripItineraryNative (a trip's full stop list + times)
// ---------------------------------------------------------------------------

/// The itinerary of the trip `vehicle_id` names: header
/// `(route_name, headsign, color, route_type, feed, cancelled)` plus one
/// `RawTripStop` per stop `(name, lat, lon, arr_secs, dep_secs, motis_id)`.
/// Times are seconds since feed-local midnight in the query-day frame with
/// the realtime overlay applied — the same frame as departure-board times,
/// so Kotlin converts them identically. Returns null when the id does not
/// resolve or the trip does not run on the query service day.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTripItineraryNative<'local>(
    mut env: EnvUnowned<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    vehicle_id: jlong,
    weekday: jint,
    date: jint,
    prev_weekday: jint,
    prev_date: jint,
    overlay_coords: JDoubleArray<'local>,
    overlay_routes: JObjectArray<'local>,
    overlay_times: JIntArray<'local>,
) -> jobject {
    env.with_env(|env| -> jni::errors::Result<jobject> {
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

        let entries =
            read_delay_entries(&mut *env, &overlay_coords, &overlay_routes, &overlay_times);
        let overlay = if entries.is_empty() {
            None
        } else {
            Some(crate::transit::DelayOverlay::build(&index, &entries))
        };

        let itin = match crate::transit::trip_itinerary(
            &index,
            vehicle_id,
            crate::transit::Schedule {
                day: crate::transit::QueryDay {
                    weekday: weekday.max(0) as u32,
                    date: date.max(0) as u32,
                    prev_weekday: prev_weekday.max(0) as u32,
                    prev_date: prev_date.max(0) as u32,
                },
                overlay: overlay.as_ref(),
            },
        ) {
            Some(t) => t,
            None => return Ok(null),
        };

        let stop_class = match env.find_class(jni_str!("com/vayunmathur/maps/util/OfflineRouter$RawTripStop")) {
            Ok(c) => c,
            Err(_) => return Ok(null),
        };
        // MUST match the RawTripStop ctor: name, lat, lon (D), arr, dep (I), motisId, cancelled (Z).
        let stop_ctor = jni_sig!("(Ljava/lang/String;DDIILjava/lang/String;Z)V");
        let stop_array = match env.new_object_array(itin.stops.len() as i32, &stop_class, JObject::null())
        {
            Ok(a) => a,
            Err(_) => return Ok(null),
        };
        for (i, s) in itin.stops.iter().enumerate() {
            let jname: JObject = match env.new_string(&s.name) {
                Ok(x) => x.into(),
                Err(_) => return Ok(null),
            };
            let jmotis: JObject = match env.new_string(&s.motis_id) {
                Ok(x) => x.into(),
                Err(_) => return Ok(null),
            };
            let obj = match env.new_object(
                &stop_class,
                &stop_ctor,
                &[
                    JValue::Object(&jname),
                    JValue::Double(s.lat),
                    JValue::Double(s.lon),
                    JValue::Int(s.arr_secs as i32),
                    JValue::Int(s.dep_secs as i32),
                    JValue::Object(&jmotis),
                    JValue::Bool(itin.cancelled),
                ],
            ) {
                Ok(o) => o,
                Err(_) => return Ok(null),
            };
            if stop_array.set_element(env, i, &obj).is_err() {
                return Ok(null);
            }
        }

        let itin_class =
            match env.find_class(jni_str!("com/vayunmathur/maps/util/OfflineRouter$RawTripItinerary")) {
                Ok(c) => c,
                Err(_) => return Ok(null),
            };
        // MUST match the RawTripItinerary ctor: route, headsign, color, type (I),
        // feed, cancelled (Z), stops ([L...RawTripStop;).
        let itin_ctor = jni_sig!("(Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;Z[Lcom/vayunmathur/maps/util/OfflineRouter$RawTripStop;)V");
        let jroute: JObject = match env.new_string(&itin.route_name) {
            Ok(s) => s.into(),
            Err(_) => return Ok(null),
        };
        let jhead: JObject = match env.new_string(&itin.headsign) {
            Ok(s) => s.into(),
            Err(_) => return Ok(null),
        };
        let jfeed: JObject = match env.new_string(&itin.feed) {
            Ok(s) => s.into(),
            Err(_) => return Ok(null),
        };
        let jobjs: JObject = stop_array.into();
        match env.new_object(
            &itin_class,
            itin_ctor,
            &[
                JValue::Object(&jroute),
                JValue::Object(&jhead),
                JValue::Int(itin.color as i32),
                JValue::Int(itin.route_type as i32),
                JValue::Object(&jfeed),
                JValue::Bool(itin.cancelled),
                JValue::Object(&jobjs),
            ],
        ) {
            Ok(o) => Ok(o.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
