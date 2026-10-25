// Multi-day service dates and the intercity route classification the planner
// uses for long journeys.
//
// Included into `transit.rs`, so `TransitIndex`'s private helpers are in scope.

/// A trip qualifies as long-distance when its stops span at least this far.
/// Commuter rail and regional coaches mostly fall short of it; Amtrak, VIA,
/// European intercity trains and FlixBus-style coaches clear it.
const INTERCITY_SPAN_M: f64 = 100_000.0;
/// Origin-destination distance from which a journey is planned on the
/// intercity network between its two local areas.
const INTERCITY_TRIP_M: f64 = 150_000.0;
/// Within this distance of either end every route is scanned.
const TRANSIT_LOCAL_M: f64 = 50_000.0;

impl QueryDay {
    /// The query day for weekday `weekday` (0=Mon) and date `yyyymmdd`, with its
    /// previous service day derived.
    pub fn for_date(weekday: u32, date: u32) -> QueryDay {
        QueryDay { weekday, date, prev_weekday: (weekday + 6) % 7, prev_date: add_days(date, -1) }
    }

    /// Weekday (0=Mon) and `yyyymmdd` date of the service day `offset` days from
    /// the query day: `-1` is the previous day, `1` tomorrow.
    pub(crate) fn on(&self, offset: i32) -> (u32, u32) {
        match offset {
            -1 => (self.prev_weekday, self.prev_date),
            0 => (self.weekday, self.date),
            n => ((self.weekday + n.rem_euclid(7) as u32) % 7, add_days(self.date, n)),
        }
    }
}

/// `yyyymmdd` shifted by `n` days in the proleptic Gregorian calendar.
fn add_days(yyyymmdd: u32, n: i32) -> u32 {
    let (y, m, d) = (
        i64::from(yyyymmdd / 10_000),
        i64::from(yyyymmdd / 100 % 100),
        i64::from(yyyymmdd % 100),
    );
    let (y, m, d) = civil_from_days(days_from_civil(y, m, d) + i64::from(n));
    (y * 10_000 + m * 100 + d) as u32
}

/// Days since 1970-01-01 (Howard Hinnant's `days_from_civil`).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { yoe + era * 400 + 1 } else { yoe + era * 400 }, m, d)
}

/// Long-distance routes of a pack, classified once on first use.
pub struct Intercity {
    /// Bit per route: long-distance rail or coach.
    long: Vec<u64>,
}

impl Intercity {
    fn build(idx: &TransitIndex) -> Intercity {
        let n = idx.route_count;
        let mut long = vec![0u64; (n as usize).div_ceil(64)];
        for r in 0..n {
            let route = idx.route(r);
            let is_rail = matches!(route.route_type, 2 | 100..=199);
            let is_coach = matches!(route.route_type, 3 | 200..=299);
            if !(is_rail || is_coach) || route.n_stops < 2 {
                continue;
            }
            // Three stops bound the span well enough for a threshold this coarse,
            // and keep the pass to a few reads per route over a world pack.
            let at = |pos: u32| idx.stop_ll(idx.route_stop(route.first_route_stop + pos));
            let pts = [at(0), at(route.n_stops / 2), at(route.n_stops - 1)];
            let span = [(0, 1), (0, 2), (1, 2)]
                .iter()
                .map(|&(a, b)| dist_m(pts[a].0, pts[a].1, pts[b].0, pts[b].1))
                .fold(0.0, f64::max);
            if span < INTERCITY_SPAN_M {
                continue;
            }
            long[(r / 64) as usize] |= 1 << (r % 64);
        }
        Intercity { long }
    }

    fn is_long(&self, route: u32) -> bool {
        self.long.get((route / 64) as usize).is_some_and(|w| w & (1 << (route % 64)) != 0)
    }
}

impl TransitIndex {
    pub(crate) fn intercity(&self) -> &Intercity {
        self.intercity.get_or_init(|| Intercity::build(self))
    }
}

/// The planner's intercity overlay for one long journey: near either end every
/// route is scanned, but a stop far from both only boards long-distance routes.
///
/// Without it, an earliest-arrival search across a continent marks every local
/// bus in every city it passes before reaching the destination. The filter
/// depends only on the stop, so the result is still a valid journey; it can
/// miss one that strings local services together far from both ends.
struct IntercityScope<'a> {
    intercity: &'a Intercity,
    from: (f64, f64),
    to: (f64, f64),
}

impl<'a> IntercityScope<'a> {
    fn for_trip(
        idx: &'a TransitIndex,
        from_lat: f64,
        from_lon: f64,
        to_lat: f64,
        to_lon: f64,
    ) -> Option<IntercityScope<'a>> {
        (dist_m(from_lat, from_lon, to_lat, to_lon) >= INTERCITY_TRIP_M).then(|| IntercityScope {
            intercity: idx.intercity(),
            from: (from_lat, from_lon),
            to: (to_lat, to_lon),
        })
    }

    fn long_only_at(&self, idx: &TransitIndex, stop: u32) -> bool {
        let (lat, lon) = idx.stop_ll(stop);
        dist_m(lat, lon, self.from.0, self.from.1) >= TRANSIT_LOCAL_M
            && dist_m(lat, lon, self.to.0, self.to.1) >= TRANSIT_LOCAL_M
    }

    fn is_long(&self, route: u32) -> bool {
        self.intercity.is_long(route)
    }
}

