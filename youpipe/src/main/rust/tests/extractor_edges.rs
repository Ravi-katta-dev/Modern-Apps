//! Parsing, request-shape and boundary tests for the youpipe extractor.
//!
//! Extends the existing suites without duplicating them: the search-response integration test
//! covers the fixture end to end, and the inline `json`, `parsing`, `response`, `decoder`,
//! `innertube`, `suggestions` and `linkhandler` tests cover the happy paths. These tests add
//! count/duration/thumbnail edges, suggestion envelope variants, client-version fallback, and
//! fixture error propagation.

use youpipe_extractor::http::{Error, HttpClient, Request, Response, Result};
use youpipe_extractor::innertube::{client_version, desktop_body, endpoint_url, reset_client_version};
use youpipe_extractor::json::JsonExt;
use youpipe_extractor::linkhandler::{channel_url, is_youtube_url, playlist_url, stream_url};
use youpipe_extractor::parsing::{
    parse_abbreviated_count, parse_duration, parse_view_count, text_from_object,
    thumbnails_from_array,
};
use youpipe_extractor::search;
use youpipe_extractor::suggestions::suggestions_for;

fn json_headers() -> youpipe_extractor::http::Headers {
    [("Content-Type".to_string(), vec!["application/json".to_string()])].into_iter().collect()
}

fn json_response(url: &str, body: Vec<u8>) -> Response {
    Response { code: 200, body, latest_url: url.to_string(), headers: json_headers() }
}

struct Stub {
    search_body: Vec<u8>,
    version_body: Vec<u8>,
}

impl HttpClient for Stub {
    fn execute(&self, request: Request<'_>) -> Result<Response> {
        if request.url.contains("sw.js") {
            return Ok(json_response(&request.url, self.version_body.clone()));
        }
        Ok(json_response(&request.url, self.search_body.clone()))
    }
}

const FIXTURE: &str = include_str!("fixtures/search_response.json");

fn live_stub() -> Stub {
    Stub {
        search_body: FIXTURE.as_bytes().to_vec(),
        version_body: br#"{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260731.00.00"}"#.to_vec(),
    }
}

#[test]
fn counts_durations_and_views_at_the_boundaries() {
    assert_eq!(parse_abbreviated_count("1.2M subscribers"), 1_200_000);
    assert_eq!(parse_abbreviated_count("15K"), 15_000);
    assert_eq!(parse_abbreviated_count("2B views"), 2_000_000_000);
    assert_eq!(parse_abbreviated_count("no number here"), -1);
    assert_eq!(parse_duration("1:02:03"), Some(3723));
    assert_eq!(parse_duration("LIVE"), None);
    assert_eq!(parse_duration(""), None);
    assert_eq!(parse_view_count("1,234 views"), 1234);
    assert_eq!(parse_view_count("No views"), 0);
    assert_eq!(parse_view_count("Premiered yesterday"), -1);
}

#[test]
fn text_and_thumbnails_tolerate_bad_shapes() {
    assert_eq!(text_from_object(&serde_json::json!({"runs": [{"nope": 1}]})).as_deref(), Some(""));
    assert_eq!(text_from_object(&serde_json::json!({"runs": "nope"})), None);
    let thumbs = serde_json::json!([
        {"url": "//i.ytimg.com/a.jpg", "width": 120, "height": 90},
        {"url": "", "width": 1, "height": 1},
        {"width": 3, "height": 3},
    ]);
    let parsed = thumbnails_from_array(thumbs.as_array().unwrap());
    assert_eq!(parsed.len(), 1);
    assert_eq!(parsed[0].url, "https://i.ytimg.com/a.jpg");
    let blank = serde_json::Value::Null;
    assert!(blank.obj("missing").arr("deeper").is_empty());
    assert_eq!(blank.obj("a").obj("b").obj("c").str("d"), None);
}

#[test]
fn link_builders_round_trip_with_parsers() {
    assert_eq!(stream_url("dQw4w9WgXcQ"), "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    assert_eq!(
        channel_url("UCuAXFkgsw1L7xaCfnd5JJOw"),
        "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw"
    );
    assert_eq!(
        playlist_url("PLabc123"),
        "https://www.youtube.com/playlist?list=PLabc123"
    );
    assert!(is_youtube_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    assert!(!is_youtube_url("https://evil-youtube.com/watch?v=dQw4w9WgXcQ"));
    assert!(!is_youtube_url("not a url"));
}

#[test]
fn suggestion_envelope_variants() {
    struct SuggestStub(&'static str);
    impl HttpClient for SuggestStub {
        fn execute(&self, request: Request<'_>) -> Result<Response> {
            Ok(json_response(&request.url, self.0.as_bytes().to_vec()))
        }
    }
    let body = r#"window.google.ac.h(["q",[["alpha",0],[42],["beta",0]],{}])"#;
    assert_eq!(
        suggestions_for(&SuggestStub(body), "q", "en", "US").unwrap(),
        vec!["alpha".to_string(), "beta".to_string()]
    );
    let empty = r#"window.google.ac.h(["q",[]])"#;
    assert!(suggestions_for(&SuggestStub(empty), "q", "en", "US").unwrap().is_empty());
    assert!(suggestions_for(&SuggestStub("garbage"), "q", "en", "US").is_err());
}

#[test]
fn client_version_falls_back_and_caches() {
    struct Failing;
    impl HttpClient for Failing {
        fn execute(&self, _: Request<'_>) -> Result<Response> {
            Err(Error::Network("offline".into()))
        }
    }
    reset_client_version();
    let fallback = client_version(&Failing);
    assert!(!fallback.is_empty(), "hardcoded fallback must be non-empty");
    assert_eq!(client_version(&Failing), fallback, "second call hits the cache");
    reset_client_version();
    let live = client_version(&live_stub());
    assert!(!live.is_empty(), "client version must be non-empty");
    reset_client_version();
}

#[test]
fn fixture_errors_propagate_and_bodies_carry_context() {
    reset_client_version();
    let result = search::search(&live_stub(), "never gonna give you up", None, "en-GB", "GB")
        .expect("fixture parses");
    assert!(result.errors.is_empty());
    assert!(!result.items.is_empty());
    let body = desktop_body("v", "en", "US", &[("query", serde_json::json!("cats"))]);
    let parsed: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert!(parsed.get("context").is_some());
    assert_eq!(parsed.get("query").and_then(|v| v.as_str()), Some("cats"));
    assert!(endpoint_url("player").ends_with("player?prettyPrint=false"));
    struct Short;
    impl HttpClient for Short {
        fn execute(&self, request: Request<'_>) -> Result<Response> {
            Ok(json_response(&request.url, b"{}".to_vec()))
        }
    }
    reset_client_version();
    assert!(search::search(&Short, "q", None, "en", "US").is_err());
    reset_client_version();
}
