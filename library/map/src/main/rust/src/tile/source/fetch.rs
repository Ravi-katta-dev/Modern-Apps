#[cfg(target_os = "android")]
use tilecodec::proto::err;
use tilecodec::proto::Result;

/// What a range fetch returned.
pub struct RangeResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

/// Fetches a byte range. Separated from the policy so the policy is testable without a
/// JVM.
pub trait RangeFetcher {
    fn fetch(&self, url: &str, range: &str) -> Result<RangeResponse>;

    /// Several ranges of the same URL at once, answered in order with one slot per range.
    ///
    /// The default serves each range through [`fetch`](Self::fetch) in turn. Fetchers over
    /// a multiplexed transport override this to fire one network call for the whole batch —
    /// one JNI crossing and one OkHttp dispatcher hop instead of one per range — which is
    /// what makes a wave of leaf/body reads cost one round trip rather than N serial ones.
    /// Failures stay per-slot: a failed range is an error for that slot, never a poisoned
    /// batch.
    fn fetch_batch(&self, url: &str, ranges: &[String]) -> Vec<Result<RangeResponse>> {
        ranges.iter().map(|range| self.fetch(url, range)).collect()
    }
}

/// A [`RangeFetcher`] over `library/jni-http`, and so over `:library:network`.
#[cfg(target_os = "android")]
pub struct JniRangeFetcher;

#[cfg(target_os = "android")]
impl JniRangeFetcher {
    fn fetch_inner(&self, url: &str, range: &str) -> Result<RangeResponse> {
        // `Header` is an owned `(String, String)` pair, and `body` is `Option`, not a
        // slice: a GET has none.
        let headers = [("Range".to_string(), range.to_string())];
        match jni_http::request(jni_http::Method::Get, url, &headers, None) {
            Ok(response) => to_range_response(url, range, response),
            Err(e) => err(format!("range request for {range} of {url} failed: {e:?}")),
        }
    }

    /// One wave, one JNI crossing: the batch counterpart of [`fetch_inner`](Self::fetch_inner).
    ///
    /// A framing failure (not a per-slot status 0) fails every slot, so no tile hangs
    /// waiting for a range that will never arrive.
    fn fetch_batch_inner(&self, url: &str, ranges: &[String]) -> Vec<Result<RangeResponse>> {
        let frames = match jni_http::request_batch(url, ranges) {
            Ok(frames) if frames.len() == ranges.len() => frames,
            Ok(_) => {
                return ranges
                    .iter()
                    .map(|range| {
                        err(format!(
                            "batch range request for {range} of {url} failed: reply count mismatch"
                        ))
                    })
                    .collect();
            }
            Err(e) => {
                return ranges
                    .iter()
                    .map(|range| {
                        err(format!("batch range request for {range} of {url} failed: {e:?}"))
                    })
                    .collect();
            }
        };
        ranges
            .iter()
            .zip(frames)
            .map(|(range, frame)| to_range_response(url, range, frame))
            .collect()
    }
}

#[cfg(target_os = "android")]
impl RangeFetcher for JniRangeFetcher {
    fn fetch(&self, url: &str, range: &str) -> Result<RangeResponse> {
        // Issue-815 timing: how long one range request spends across the JNI
        // bridge. A slow first request with fast followers means connection
        // reuse works; uniformly slow requests implicate per-request TLS
        // handshakes (a `:library:network` follow-up, diagnose-only here).
        let start = std::time::Instant::now();
        let outcome = self.fetch_inner(url, range);
        let ms = start.elapsed().as_millis();
        let status = match &outcome {
            Ok(r) => r.status.to_string(),
            Err(_) => "err".to_string(),
        };
        crate::bridge::log::log_info(&format!("range {range} -> {status} in {ms}ms"));
        outcome
    }

    fn fetch_batch(&self, url: &str, ranges: &[String]) -> Vec<Result<RangeResponse>> {
        if ranges.len() < 2 {
            return ranges.iter().map(|range| self.fetch(url, range)).collect();
        }
        let start = std::time::Instant::now();
        let outcome = self.fetch_batch_inner(url, ranges);
        let ms = start.elapsed().as_millis();
        let ok = outcome.iter().filter(|r| r.is_ok()).count();
        crate::bridge::log::log_info(&format!(
            "batch {} ranges of {url} -> {ok}/{} ok in {ms}ms",
            ranges.len(),
            ranges.len(),
        ));
        outcome
    }
}

/// One status-0-aware conversion for both the single and batch paths: status 0 means the
/// request never completed (a transport error the bridge reports in-band), anything else
/// is the server's answer verbatim.
#[cfg(target_os = "android")]
fn to_range_response(
    url: &str,
    range: &str,
    response: jni_http::ResponseFrame,
) -> Result<RangeResponse> {
    if response.status == 0 {
        let detail = response.error().unwrap_or_else(|| "transport failure".into());
        return err(format!("range request for {range} of {url} failed: {detail}"));
    }
    Ok(RangeResponse { status: response.status, body: response.body })
}
