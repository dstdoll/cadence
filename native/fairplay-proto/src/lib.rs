//! FairPlay key-delivery protocol for Apple Music on Android.
//!
//! Reconstructed by decompiling `SVFootHillSessionCtrl::_getCKC`
//! (`libandroidappmusic.so` @ `0x20d1e8`) and **verified against real traffic** captured with
//! Frida on a device.
//!
//! The client builds an SPC (Server Playback Context) with the FairPlay blob, sends it in a JSON
//! POST to Apple's key server, and receives a CKC (Content Key Context) that feeds back into the
//! blob to derive the content key.
//!
//! This crate covers **only the network and serialization layer**: what goes to and from the key
//! server. SPC generation and CKC processing still stay inside the native blob.
//!
//! There are two request formats depending on `keyServerProtocolType`:
//!
//! * `"simplified"` -- a flat object. This is what Apple Music uses today.
//! * anything else -- the nested `fairplay-streaming-request` wrapper, which is the generic FPS
//!   key-server format.

#![forbid(unsafe_code)]

use serde::{Deserialize, Serialize};

/// Apple Music key-delivery endpoint.
pub const KEY_SERVER_URL: &str = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/music/fps";

/// `keyFormat` value expected by `getPersistentKey`.
pub const KEY_FORMAT: &str = "com.apple.streamingkeydelivery";
/// `keyFormatVersion` value.
pub const KEY_FORMAT_VERSION: &str = "1";
/// `keyServerProtocolType` selecting the flat format.
pub const PROTOCOL_SIMPLIFIED: &str = "simplified";

/// `adamId` Apple uses for the shared context prior to any track.
///
/// When this value arrives, the original implementation caches the resulting context and reuses it
/// for the whole session.
pub const PRESHARE_ADAM_ID: &str = "0";

/// Key-server status returned when no offline slot is available.
///
/// The original client retries the full request with `offline = false`.
pub const CKC_STATUS_SLOT_ERROR: i32 = -1004;

/// Which request body format to use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Protocol {
    /// Flat object with `guid`/`offline`/`adamId`/`spc`/`keyUri`.
    Simplified,
    /// Wrapper `fairplay-streaming-request` -> `streaming-keys` -> `[{id, spc, uri}]`.
    Legacy,
}

impl Protocol {
    /// Interprets `keyServerProtocolType` like `_getCKC`: compares against `"simplified"`, and
    /// anything else falls back to the nested format.
    pub fn from_type(key_server_protocol_type: &str) -> Self {
        if key_server_protocol_type == PROTOCOL_SIMPLIFIED {
            Self::Simplified
        } else {
            Self::Legacy
        }
    }
}

/// What a `keyUri` requests.
///
/// Both forms are distinguished by the **case of the leading letter** in the path, something not
/// obvious until captures of both exist:
///
/// ```text
/// skd://itunes.apple.com/P000000000/s1/e1    shared context   (uppercase P, /sN/eN)
/// skd://itunes.apple.com/p1000000001/c23     track key        (lowercase p, /cN)
/// ```
///
/// The URI form and `adamId` must match: the shared context travels with `adamId` set to
/// [`PRESHARE_ADAM_ID`], and a track with its own ID.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyUriKind {
    /// Shared context requested once per session.
    SharedContext,
    /// Key for a specific track.
    Track,
}

/// Host appearing in all observed `keyUri` values.
pub const KEY_URI_HOST: &str = "skd://itunes.apple.com/";

/// Classifies a `keyUri`.
///
/// Returns `None` if it matches neither of the two known forms.
pub fn classify_key_uri(key_uri: &str) -> Option<KeyUriKind> {
    let rest = key_uri.strip_prefix(KEY_URI_HOST)?;
    let (first, tail) = rest.split_once('/')?;
    let initial = first.get(0..1)?;
    let id = first.get(1..)?;
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    match initial {
        "P" if tail.starts_with('s') && tail.contains("/e") => Some(KeyUriKind::SharedContext),
        "p" if tail.starts_with('c') => Some(KeyUriKind::Track),
        _ => None,
    }
}

/// Inputs needed to request a key.
#[derive(Debug, Clone)]
pub struct KeyRequest {
    /// Device GUID (see the `device-guid` crate).
    pub guid: String,
    /// Track `adamId`, or [`PRESHARE_ADAM_ID`] for the shared context.
    pub adam_id: String,
    /// The `skd://` URI from the HLS playlist.
    pub key_uri: String,
    /// Base64-encoded SPC as returned by the blob.
    pub spc_base64: String,
    /// Whether to request a persistent key (requires an offline slot on the device).
    pub offline: bool,
}

/// Request body for the `simplified` format.
///
/// Field order matches the original, which inserts them into a `CFDictionary` in this sequence.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SimplifiedRequest {
    pub guid: String,
    /// Only present when requesting an offline key. Apple sends it as the **string** `"true"`, not
    /// a JSON boolean.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub offline: Option<String>,
    #[serde(rename = "adamId")]
    pub adam_id: String,
    pub spc: String,
    #[serde(rename = "keyUri")]
    pub key_uri: String,
}

/// An entry in `streaming-keys` for the legacy format.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LegacyKeyEntry {
    pub id: u32,
    pub spc: String,
    pub uri: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LegacyStreamingKeys {
    #[serde(rename = "streaming-keys")]
    pub streaming_keys: Vec<LegacyKeyEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LegacyRequest {
    #[serde(rename = "fairplay-streaming-request")]
    pub fairplay_streaming_request: LegacyStreamingKeys,
}

impl KeyRequest {
    /// Serializes the POST body in the requested format.
    pub fn to_json(&self, protocol: Protocol) -> Result<String, Error> {
        let json = match protocol {
            Protocol::Simplified => serde_json::to_string(&SimplifiedRequest {
                guid: self.guid.clone(),
                // the original only adds the key when true; if false, omit it
                offline: self.offline.then(|| "true".to_owned()),
                adam_id: self.adam_id.clone(),
                spc: self.spc_base64.clone(),
                key_uri: self.key_uri.clone(),
            })?,
            Protocol::Legacy => serde_json::to_string(&LegacyRequest {
                fairplay_streaming_request: LegacyStreamingKeys {
                    streaming_keys: vec![LegacyKeyEntry {
                        id: 0,
                        spc: self.spc_base64.clone(),
                        uri: self.key_uri.clone(),
                    }],
                },
            })?,
        };
        Ok(json)
    }

    /// Whether this request is for the shared context.
    pub fn is_preshare(&self) -> bool {
        self.adam_id == PRESHARE_ADAM_ID
    }

    /// Whether `keyUri` and `adamId` match.
    ///
    /// A well-formed request asks for the shared context with [`PRESHARE_ADAM_ID`] and an
    /// uppercase-`P` URI, or a track with its `adamId` and a lowercase-`p` URI.
    pub fn uri_matches_adam_id(&self) -> bool {
        match classify_key_uri(&self.key_uri) {
            Some(KeyUriKind::SharedContext) => self.is_preshare(),
            Some(KeyUriKind::Track) => !self.is_preshare(),
            None => false,
        }
    }
}

/// A key returned by the server.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StreamingKey {
    pub status: i32,
    pub ckc: String,
}

impl StreamingKey {
    /// Whether the server asks to retry without an offline slot.
    pub fn is_slot_error(&self) -> bool {
        self.status == CKC_STATUS_SLOT_ERROR
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct LegacyResponseInner {
    #[serde(rename = "streaming-keys")]
    streaming_keys: Vec<StreamingKey>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct LegacyResponse {
    #[serde(rename = "fairplay-streaming-response")]
    fairplay_streaming_response: LegacyResponseInner,
}

/// Reads the key-server response.
///
/// In `simplified` the root object directly carries `status` and `ckc`. In legacy we walk
/// `fairplay-streaming-response` -> `streaming-keys` -> first element, matching
/// `_getCKCFromResponse`.
pub fn parse_response(body: &str, protocol: Protocol) -> Result<StreamingKey, Error> {
    match protocol {
        Protocol::Simplified => Ok(serde_json::from_str::<StreamingKey>(body)?),
        Protocol::Legacy => {
            let parsed: LegacyResponse = serde_json::from_str(body)?;
            parsed
                .fairplay_streaming_response
                .streaming_keys
                .into_iter()
                .next()
                .ok_or(Error::EmptyStreamingKeys)
        }
    }
}

/// What to do after reading a response.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Key ready; the base64 CKC goes back into the native blob.
    Key { ckc_base64: String, status: i32 },
    /// Server rejected the offline slot: retry the request with `offline = false`.
    RetryWithoutOfflineSlot,
}

/// Applies `_getCKC`'s retry policy.
///
/// The original only retries when offline was **requested** and received
/// [`CKC_STATUS_SLOT_ERROR`]; for any other status it keeps the key as-is, even if status is
/// non-zero (the blob decides whether it is usable when processing it).
pub fn evaluate(key: StreamingKey, requested_offline: bool) -> Outcome {
    if requested_offline && key.is_slot_error() {
        Outcome::RetryWithoutOfflineSlot
    } else {
        Outcome::Key { ckc_base64: key.ckc, status: key.status }
    }
}

/// Fixed POST headers. The rest (auth, Anisette, cookies) are added by `RequestContext`.
pub const CONTENT_TYPE: (&str, &str) = ("Content-Type", "application/json");

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("response contained no streaming key")]
    EmptyStreamingKeys,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real body captured with Frida on `HTTPMessage::setBody` on the test device, with long
    /// values trimmed (real `guid` is 111 chars and `spc` is 9600 chars). What matters here is the
    /// shape: field names, order, and types.
    const CAPTURADO_PRESHARE: &str = r#"{"guid":"G","offline":"true","adamId":"0","spc":"S","keyUri":"skd://itunes.apple.com/P000000000/s1/e1"}"#;

    const CAPTURADO_TRACK: &str = r#"{"guid":"G","offline":"true","adamId":"1632087479","spc":"S","keyUri":"skd://itunes.apple.com/p1492777545/c6"}"#;

    fn req(adam: &str, uri: &str, offline: bool) -> KeyRequest {
        KeyRequest {
            guid: "G".into(),
            adam_id: adam.into(),
            key_uri: uri.into(),
            spc_base64: "S".into(),
            offline,
        }
    }

    #[test]
    fn simplified_reproduces_captured_body() {
        let r = req(PRESHARE_ADAM_ID, "skd://itunes.apple.com/P000000000/s1/e1", true);
        assert_eq!(r.to_json(Protocol::Simplified).unwrap(), CAPTURADO_PRESHARE);
    }

    #[test]
    fn simplified_reproduces_track_body() {
        let r = req("1632087479", "skd://itunes.apple.com/p1492777545/c6", true);
        assert_eq!(r.to_json(Protocol::Simplified).unwrap(), CAPTURADO_TRACK);
    }

    #[test]
    fn without_offline_key_is_omitted() {
        let r = req("123", "skd://x", false);
        let json = r.to_json(Protocol::Simplified).unwrap();
        assert!(!json.contains("offline"), "original omits the key instead of sending false");
        assert_eq!(json, r#"{"guid":"G","adamId":"123","spc":"S","keyUri":"skd://x"}"#);
    }

    #[test]
    fn offline_is_sent_as_string_not_boolean() {
        let json = req("1", "skd://x", true).to_json(Protocol::Simplified).unwrap();
        assert!(json.contains(r#""offline":"true""#));
        assert!(!json.contains(r#""offline":true"#));
    }

    #[test]
    fn legacy_nests_under_streaming_keys() {
        let json = req("1", "skd://x", true).to_json(Protocol::Legacy).unwrap();
        assert_eq!(
            json,
            r#"{"fairplay-streaming-request":{"streaming-keys":[{"id":0,"spc":"S","uri":"skd://x"}]}}"#
        );
    }

    #[test]
    fn legacy_ignores_offline_flag() {
        // Nested format has nowhere to put it; the original omits it too.
        let a = req("1", "skd://x", true).to_json(Protocol::Legacy).unwrap();
        let b = req("1", "skd://x", false).to_json(Protocol::Legacy).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn protocol_type_selects_format() {
        assert_eq!(Protocol::from_type("simplified"), Protocol::Simplified);
        assert_eq!(Protocol::from_type("standard"), Protocol::Legacy);
        assert_eq!(Protocol::from_type(""), Protocol::Legacy);
    }

    #[test]
    fn parses_simplified_response() {
        let k = parse_response(r#"{"status":0,"ckc":"AAAAAQ"}"#, Protocol::Simplified).unwrap();
        assert_eq!(k.status, 0);
        assert_eq!(k.ckc, "AAAAAQ");
    }

    #[test]
    fn parses_legacy_response() {
        let body = r#"{"fairplay-streaming-response":{"streaming-keys":[{"status":0,"ckc":"AAAAAQ"}]}}"#;
        let k = parse_response(body, Protocol::Legacy).unwrap();
        assert_eq!(k.ckc, "AAAAAQ");
    }

    #[test]
    fn empty_list_returns_error() {
        let body = r#"{"fairplay-streaming-response":{"streaming-keys":[]}}"#;
        assert!(matches!(
            parse_response(body, Protocol::Legacy),
            Err(Error::EmptyStreamingKeys)
        ));
    }

    #[test]
    fn slot_error_triggers_retry() {
        let k = StreamingKey { status: CKC_STATUS_SLOT_ERROR, ckc: String::new() };
        assert!(k.is_slot_error());
        assert_eq!(evaluate(k, true), Outcome::RetryWithoutOfflineSlot);
    }

    #[test]
    fn slot_error_without_offline_request_does_not_retry() {
        // If offline was not requested, the original does not enter the retry loop.
        let k = StreamingKey { status: CKC_STATUS_SLOT_ERROR, ckc: "X".into() };
        assert_eq!(
            evaluate(k, false),
            Outcome::Key { ckc_base64: "X".into(), status: CKC_STATUS_SLOT_ERROR }
        );
    }

    #[test]
    fn other_status_returns_key() {
        let k = StreamingKey { status: 0, ckc: "CKC".into() };
        assert_eq!(evaluate(k, true), Outcome::Key { ckc_base64: "CKC".into(), status: 0 });
    }

    #[test]
    fn recognizes_shared_context() {
        assert!(req(PRESHARE_ADAM_ID, "skd://x", false).is_preshare());
        assert!(!req("1632087479", "skd://x", false).is_preshare());
    }

    #[test]
    fn letter_case_distinguishes_both_uri_kinds() {
        assert_eq!(
            classify_key_uri("skd://itunes.apple.com/P000000000/s1/e1"),
            Some(KeyUriKind::SharedContext)
        );
        assert_eq!(
            classify_key_uri("skd://itunes.apple.com/p1000000001/c23"),
            Some(KeyUriKind::Track)
        );
        // same path with swapped case is neither
        assert_eq!(classify_key_uri("skd://itunes.apple.com/p000000000/s1/e1"), None);
        assert_eq!(classify_key_uri("skd://itunes.apple.com/P1000000001/c23"), None);
        assert_eq!(classify_key_uri("skd://other.host/p1/c1"), None);
        assert_eq!(classify_key_uri("skd://itunes.apple.com/pXYZ/c1"), None);
    }

    #[test]
    fn uri_must_match_adam_id() {
        assert!(req(PRESHARE_ADAM_ID, "skd://itunes.apple.com/P000000000/s1/e1", true)
            .uri_matches_adam_id());
        assert!(req("100000001", "skd://itunes.apple.com/p1000000001/c23", true)
            .uri_matches_adam_id());
        // mismatched: false
        assert!(!req(PRESHARE_ADAM_ID, "skd://itunes.apple.com/p1000000001/c23", true)
            .uri_matches_adam_id());
        assert!(!req("100000001", "skd://itunes.apple.com/P000000000/s1/e1", true)
            .uri_matches_adam_id());
    }
}
