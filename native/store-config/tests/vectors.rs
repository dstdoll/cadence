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
fn user_agent_capturado() -> Option<String> {
    vectors_fixture::string_section(env!("CARGO_MANIFEST_DIR"), "userAgent")
}

/// Splits `Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)`.
fn partes(ua: &str) -> (String, String, String, String, String, u32) {
    let (cliente_ver, resto) = ua.split_once(' ').expect("missing space after version");
    let (cliente, version) = cliente_ver.split_once('/').expect("missing client/version");
    let plataforma = resto.split(" model/").next().unwrap().to_owned();
    let resto = resto.split_once(" model/").unwrap().1;
    let (modelo, resto) = resto.split_once(" build/").expect("missing build/");
    let (build, dt) = resto.split_once(" (dt:").expect("missing (dt:");
    let dt = dt.trim_end_matches(')').parse().expect("dt must be a number");
    (
        cliente.to_owned(),
        version.to_owned(),
        plataforma,
        modelo.to_owned(),
        build.to_owned(),
        dt,
    )
}

/// The port reconstructs the captured header character for character.
#[test]
fn reconstruye_el_user_agent_del_dispositivo() {
    let Some(ua) = user_agent_capturado() else {
        eprintln!("no vectors.json: skipped");
        return;
    };
    let (_, _, plataforma, _, _, dt) = partes(&ua);
    let release = plataforma.strip_prefix("Android/").expect("platform is Android/<rel>");

    let c = RequestContextConfig::for_android("/data/user/0/app.cadence/files", release);
    assert_eq!(c.user_agent(dt), ua, "reconstructed header mismatch");
}

/// Crate constants match what the app reports.
#[test]
fn las_constantes_coinciden_con_lo_capturado() {
    let Some(ua) = user_agent_capturado() else { return };
    let (cliente, version, _, modelo, build, _) = partes(&ua);
    assert_eq!(cliente, CLIENT_IDENTIFIER);
    assert_eq!(version, PRODUCT_VERSION);
    assert_eq!(modelo, DEVICE_MODEL);
    assert_eq!(build, BUILD_VERSION);
}

/// The declared model is the build constant, not the hardware that ran the capture.
///
/// That is the key invariant and it is checked without naming any vendor: the `User-Agent` must
/// match the crate constant. If the app ever starts reporting the real model, this test catches it.
#[test]
fn el_modelo_declarado_es_el_fijo_del_build() {
    let Some(ua) = user_agent_capturado() else { return };
    let (_, _, _, modelo, _, _) = partes(&ua);
    assert_eq!(modelo, DEVICE_MODEL, "User-Agent model must match build constant");
}
