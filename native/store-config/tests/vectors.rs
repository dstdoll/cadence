//! Validation against the real `User-Agent` captured on the device.
//!
//! `vectors.json` is produced by `export-vectors.js` hooking `HTTPMessage::setHeader`. This tests
//! that the port reconstructs the header identically and that the crate's constants match what the
//! app actually reports.
//!
//! If the file is missing, tests are skipped (`CADENCE_VECTORS_REQUIRED=1` turns that into a
//! failure). If it exists without `userAgent` or fails to parse, it **fails**.

use store_config::*;

/// Skips without a capture; **fails** on an incomplete or corrupt capture.
/// See `vectors-fixture`: the old `else { return }` passed green without checking anything.
fn captured_user_agent() -> Option<String> {
    vectors_fixture::string_section(env!("CARGO_MANIFEST_DIR"), "userAgent")
}

/// Splits `Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)`.
fn split_ua_parts(ua: &str) -> (String, String, String, String, String, u32) {
    let (client_ver, rest) = ua.split_once(' ').expect("missing space after version");
    let (client, version) = client_ver.split_once('/').expect("missing client/version");
    let platform = rest.split(" model/").next().unwrap().to_owned();
    let rest = rest.split_once(" model/").unwrap().1;
    let (model, rest) = rest.split_once(" build/").expect("missing build/");
    let (build, dt) = rest.split_once(" (dt:").expect("missing (dt:");
    let dt = dt.trim_end_matches(')').parse().expect("dt must be a number");
    (
        client.to_owned(),
        version.to_owned(),
        platform,
        model.to_owned(),
        build.to_owned(),
        dt,
    )
}

/// The port reconstructs the captured header character for character.
#[test]
fn reconstructs_device_user_agent() {
    let Some(ua) = captured_user_agent() else {
        eprintln!("no vectors.json: skipped");
        return;
    };
    let (_, _, platform, _, _, dt) = split_ua_parts(&ua);
    let release = platform.strip_prefix("Android/").expect("platform is Android/<rel>");

    let c = RequestContextConfig::for_android("/data/user/0/app.cadence/files", release);
    assert_eq!(c.user_agent(dt), ua, "reconstructed header mismatch");
}

/// Crate constants match what the app reports.
#[test]
fn constants_match_captured() {
    let Some(ua) = captured_user_agent() else { return };
    let (client, version, _, model, build, _) = split_ua_parts(&ua);
    assert_eq!(client, CLIENT_IDENTIFIER);
    assert_eq!(version, PRODUCT_VERSION);
    assert_eq!(model, DEVICE_MODEL);
    assert_eq!(build, BUILD_VERSION);
}

/// The declared model is the build constant, not the hardware that ran the capture.
///
/// That is the key invariant and it is checked without naming any vendor: the `User-Agent` must
/// match the crate constant. If the app ever starts reporting the real model, this test catches it.
#[test]
fn declared_model_matches_build_constant() {
    let Some(ua) = captured_user_agent() else { return };
    let (_, _, _, model, _, _) = split_ua_parts(&ua);
    assert_eq!(model, DEVICE_MODEL, "User-Agent model must match build constant");
}
