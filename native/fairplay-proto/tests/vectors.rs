//! Validation against vectors captured from the device.
//!
//! `vectors.json` is produced by `export-vectors.js` hooking Apple's libraries during playback.
//! These tests verify that the port generates the exact same output as the original implementation
//! from the same inputs.
//!
//! If the file is missing, tests are skipped: the rest of the crate is validated without a device.

use fairplay_proto::*;

fn vectors() -> Option<serde_json::Value> {
    vectors_fixture::load(env!("CARGO_MANIFEST_DIR"))
}

/// An array from the capture that **must** contain items.
///
/// Previously `as_array().cloned().unwrap_or_default()` was used: when the section was missing, it
/// left an empty vector and tests iterating over it passed without checking anything.
fn array(v: &serde_json::Value, key: &str) -> Vec<serde_json::Value> {
    let s = v.get(key).unwrap_or_else(|| {
        panic!("vectors.json missing section {key:?}")
    });
    let a = s
        .as_array()
        .unwrap_or_else(|| panic!("vectors.json: {key:?} should be an array"));
    assert!(!a.is_empty(), "vectors.json: {key:?} is empty, nothing to validate");
    a.clone()
}

/// Reconstructs each captured body from its fields and verifies it matches.
///
/// Proves the serializer reproduces byte for byte what the app sends: same key order, same
/// `offline` handling, same field names.
///
/// The expected string is built by walking keys **in captured order**: re-serializing the `Value`
/// does not work because `serde_json` sorts maps alphabetically while the original does not.
///
/// Error messages never include the body: the SPC identifies the device.
#[test]
fn reproduces_captured_bodies() {
    let Some(v) = vectors() else {
        eprintln!("no vectors.json: skipped");
        return;
    };
    let reqs = array(&v, "ckcRequests");
    assert!(!reqs.is_empty(), "vectors.json has no request bodies");

    for (i, r) in reqs.iter().enumerate() {
        let p = &r["parsed"];
        let offline = p["offline"].as_str() == Some("true");
        let req = KeyRequest {
            guid: p["guid"].as_str().unwrap().to_owned(),
            adam_id: p["adamId"].as_str().unwrap().to_owned(),
            key_uri: p["keyUri"].as_str().unwrap().to_owned(),
            spc_base64: p["spc"].as_str().unwrap().to_owned(),
            offline,
        };
        let ours = req.to_json(Protocol::Simplified).unwrap();

        let mut theirs = String::from("{");
        for (j, k) in r["keys"].as_array().unwrap().iter().enumerate() {
            let k = k.as_str().unwrap();
            if j > 0 {
                theirs.push(',');
            }
            theirs.push_str(&serde_json::to_string(k).unwrap());
            theirs.push(':');
            theirs.push_str(&serde_json::to_string(&p[k]).unwrap());
        }
        theirs.push('}');

        if ours != theirs {
            let at = ours
                .bytes()
                .zip(theirs.bytes())
                .position(|(a, b)| a != b)
                .unwrap_or_else(|| ours.len().min(theirs.len()));
            // trim context: the body carries the device's SPC
            panic!(
                "body {i} differs at byte {at} (our len {}, captured {})",
                ours.len(),
                theirs.len()
            );
        }

        // and length too, which is what the hook measured
        let len = r["raw_len"].as_u64().unwrap() as usize;
        assert_eq!(ours.len(), len, "body {i} length mismatch");
    }
}

/// The JSON keys, in order, match what Apple uses.
/// Canonical key order of the CKC body as emitted by the original.
const CANONICAL_ORDER: [&str; 5] = ["guid", "offline", "adamId", "spc", "keyUri"];

#[test]
fn key_order_matches_original() {
    let Some(v) = vectors() else { return };
    for (i, r) in array(&v, "ckcRequests").iter().enumerate() {
        let keys: Vec<String> = r["keys"]
            .as_array()
            .unwrap()
            .iter()
            .map(|k| k.as_str().unwrap().to_owned())
            .collect();
        // `offline` is the only optional key: the original omits it when false
        // (see `KeyRequest::to_json`). In the device capture every request was
        // offline, so all had five keys; a streaming request has four and is
        // equally valid. What matters is the **relative order**, which breaks the
        // body if someone reorders the struct.
        let expected: Vec<&str> =
            CANONICAL_ORDER.iter().copied().filter(|k| keys.iter().any(|x| x == k)).collect();
        assert_eq!(keys, expected, "body {i} has keys in a different order");
        assert!(
            keys.iter().all(|k| CANONICAL_ORDER.contains(&k.as_str())),
            "body {i} contains a key the original does not send: {keys:?}"
        );
        for required in ["guid", "adamId", "spc", "keyUri"] {
            assert!(
                keys.iter().any(|k| k == required),
                "body {i} is missing required key {required}"
            );
        }
    }
}

/// Both body forms (with and without `offline`) come from the same `to_json`.
///
/// Locks down the important detail: when `offline` is false the key is **omitted**, and when
/// present its value is the string `"true"`, not a boolean. Sending `"offline": false` is a
/// body the official app never emits.
#[test]
fn offline_false_omits_key() {
    const EXPECTED_STRING: &str = "\"offline\":\"true\"";
    const EXPECTED_BOOL: &str = "\"offline\":true";
    let base = KeyRequest {
        guid: "a".repeat(111),
        adam_id: "100000001".into(),
        key_uri: "skd://itunes.apple.com/p1/c23".into(),
        spc_base64: "AAAA".into(),
        offline: false,
    };
    let without = base.to_json(Protocol::Simplified).unwrap();
    assert!(!without.contains("offline"), "with offline=false key must be omitted: {without}");

    let with = KeyRequest { offline: true, ..base }.to_json(Protocol::Simplified).unwrap();
    assert!(with.contains(EXPECTED_STRING), "offline must be a string: {with}");
    assert!(!with.contains(EXPECTED_BOOL), "not a boolean: {with}");
}

/// Constant arguments to `getPersistentKey` match what this crate declares.
#[test]
fn constants_match_real_arguments() {
    let Some(v) = vectors() else { return };
    let args = array(&v, "getPersistentKeyArgs");
    assert!(!args.is_empty(), "no captured arguments");

    for a in &args {
        assert_eq!(a["keyFormat"].as_str().unwrap(), KEY_FORMAT);
        assert_eq!(a["keyFormatVersion"].as_str().unwrap(), KEY_FORMAT_VERSION);
        assert_eq!(a["serverUri"].as_str().unwrap(), KEY_SERVER_URL);
        assert_eq!(a["protocolType"].as_str().unwrap(), PROTOCOL_SIMPLIFIED);
        // defaultId and adamId are the same repeated value
        assert_eq!(a["defaultId"], a["adamId"], "original passes adamId twice");
    }
}

/// `keyUri` and `adamId` match in every captured request.
///
/// Does not assume the shared context is first: it is requested once per FairPlay session, so a
/// capture starting with the app already running does not include it. What always holds is the
/// match between URI form and `adamId`.
#[test]
fn uri_matches_adam_id_across_captures() {
    let Some(v) = vectors() else {
        eprintln!("no vectors.json: skipped");
        return;
    };
    let reqs = array(&v, "ckcRequests");
    assert!(!reqs.is_empty(), "vectors.json has no request bodies");

    let mut shared = 0;
    let mut tracks = 0;
    for (i, r) in reqs.iter().enumerate() {
        let p = &r["parsed"];
        let adam = p["adamId"].as_str().unwrap();
        let uri = p["keyUri"].as_str().unwrap();
        match classify_key_uri(uri) {
            Some(KeyUriKind::SharedContext) => {
                assert_eq!(adam, PRESHARE_ADAM_ID, "request {i} asks for shared context with another adamId");
                shared += 1;
            }
            Some(KeyUriKind::Track) => {
                assert_ne!(adam, PRESHARE_ADAM_ID, "request {i} asks for a track with preshare adamId");
                assert!(adam.chars().all(|c| c.is_ascii_digit()), "request {i} adamId is not numeric");
                tracks += 1;
            }
            None => panic!("request {i} keyUri has no known format"),
        }
    }
    assert_eq!(shared + tracks, reqs.len());
    eprintln!("classified: {shared} shared context, {tracks} track");
}

/// If the capture includes the shared context, it is the first request of the session.
#[test]
fn shared_context_opens_session() {
    let Some(v) = vectors() else { return };
    let reqs = array(&v, "ckcRequests");
    let pos = reqs
        .iter()
        .position(|r| r["parsed"]["adamId"].as_str() == Some(PRESHARE_ADAM_ID));
    match pos {
        Some(0) => {}
        Some(n) => panic!("shared context appeared at position {n}, not at start"),
        None => eprintln!("this capture started with an active session: skipped"),
    }
}

/// `KDDecryptSample` is always called in content-key mode and always in-place.
#[test]
fn decryption_is_content_key_and_in_place() {
    let Some(v) = vectors() else { return };
    let d = &v["decryptSamples"];
    let count = d["count"].as_u64().unwrap_or(0);
    if count == 0 {
        eprintln!("no samples captured: skipped");
        return;
    }
    assert_eq!(
        d["inPlace"].as_u64().unwrap(),
        count,
        "all calls must be in-place"
    );
    let modes = d["modes"].as_object().unwrap();
    assert_eq!(modes.len(), 1, "expected a single mode");
    assert!(modes.contains_key("5"), "mode must be content-key (5)");
}
