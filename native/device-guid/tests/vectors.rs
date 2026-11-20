//! Validation against the real GUID captured on the device.
//!
//! `vectors.json` is produced by `export-vectors.js` hooking `DeviceGUID::configure` (inputs:
//! Android ID and serial) and key-server traffic (the resulting GUID, where the value actually
//! matters). With both, the port is tested end to end: same inputs -> same GUID, char for char.
//!
//! **Without a device the tests are skipped; with an incomplete `vectors.json` they fail.** A file
//! missing a section means that hook did not fire, which must show red: a silent skip looks like
//! validation when it is not.
//!
//! OPSEC: the GUID identifies the device. Error messages must not print it.

use device_guid::*;

fn vectors() -> Option<serde_json::Value> {
    vectors_fixture::load(env!("CARGO_MANIFEST_DIR"))
}

/// The GUID as sent to the key server.
fn guid_capturado(v: &serde_json::Value) -> String {
    let reqs = v["ckcRequests"].as_array().expect("vectors.json missing ckcRequests");
    let primero = reqs.first().expect("vectors.json has no CKC request body");
    primero["parsed"]["guid"]
        .as_str()
        .expect("CKC body missing guid")
        .to_owned()
}

/// Inputs received by `DeviceGUID::configure`.
///
/// Accepted in two places because the harness emits them separately since hooking `guid()` proved
/// fragile.
fn insumos(v: &serde_json::Value) -> (String, String) {
    let i = if v["deviceGuidInputs"].is_object() {
        &v["deviceGuidInputs"]
    } else {
        &v["deviceGuid"]["inputs"]
    };
    let id = i["androidId"].as_str().unwrap_or_default().to_owned();
    assert!(
        !id.is_empty(),
        "vectors.json is missing Android ID: the DeviceGUID::configure hook captured nothing, so \
         this test cannot validate the port. Re-run the harness."
    );
    (id, i["serial"].as_str().unwrap_or_default().to_owned())
}

/// Observed structure matches what the crate declares.
#[test]
fn la_estructura_capturada_tiene_seis_campos_de_ancho_fijo() {
    let Some(v) = vectors() else {
        eprintln!("no vectors.json: skipped");
        return;
    };
    let guid = guid_capturado(&v);
    assert_eq!(guid.len(), GUID_LEN, "captured GUID length");
    let anchos: Vec<usize> = guid.split('.').map(str::len).collect();
    assert_eq!(anchos, vec![36, 14, 14, 14, 14, 14]);
}

/// Given the same inputs, the port produces the exact same GUID byte for byte.
///
/// The acid test: the only field that cannot be derived without the blob (the first) is taken
/// from the capture; everything else is computed by the port.
#[test]
fn reproduce_el_guid_capturado() {
    let Some(v) = vectors() else { return };
    let guid = guid_capturado(&v);
    let (android_id, serial) = insumos(&v);
    let parts = parse(&guid).expect("captured GUID must parse");

    let nuestro = build(&DeviceInfo {
        android_id,
        serial,
        fairplay_field1: Some(parts.fairplay1.clone()),
        fairplay_field2: Some(parts.fairplay2.clone()),
    });
    assert!(
        nuestro == guid,
        "generated GUID does not match captured GUID (lengths {} vs {})",
        nuestro.len(),
        guid.len()
    );
}

/// The fourth field is truncated `sha1(androidId)` and nothing else.
#[test]
fn el_hash_corresponde_al_android_id_de_los_insumos() {
    let Some(v) = vectors() else { return };
    let (android_id, _) = insumos(&v);
    let parts = parse(&guid_capturado(&v)).unwrap();
    assert_eq!(parts.android_id_hash, android_id_hash(&android_id));
    assert!(parts.matches_android_id(&android_id));
}

/// Fillers came in as zeros, and field 1 carries FairPlay data.
#[test]
fn los_rellenos_capturados_estan_en_ceros() {
    let Some(v) = vectors() else { return };
    let parts = parse(&guid_capturado(&v)).unwrap();
    assert_eq!(parts.filler1, "0".repeat(FIELD_LEN));
    assert_eq!(parts.filler2, "0".repeat(FIELD_LEN));
    assert!(parts.looks_valid());
    assert!(parts.has_fairplay(), "captured GUID must carry FairPlay blob data");
}

/// El campo 1 mide lo que da el base16 de los 18 bytes que entrega FairPlay.
#[test]
fn el_campo1_es_el_base16_de_18_bytes() {
    let Some(v) = vectors() else { return };
    let parts = parse(&guid_capturado(&v)).unwrap();
    assert_eq!(parts.fairplay1.len(), FAIRPLAY_FIELD1_BYTES * 2);
    assert!(
        parts.fairplay1.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()),
        "Base16Representation da hex en minúsculas"
    );
}

/// El dispositivo de pruebas no reporta serial, y el campo igual viene relleno.
#[test]
fn sin_serial_el_campo_viene_relleno() {
    let Some(v) = vectors() else { return };
    let (_, serial) = insumos(&v);
    let parts = parse(&guid_capturado(&v)).unwrap();
    if serial.is_empty() {
        assert_eq!(parts.serial, "0".repeat(FIELD_LEN), "sin serial va relleno, no vacío");
    } else {
        assert_eq!(parts.serial.len(), FIELD_LEN);
    }
}

/// Todos los requests llevan el mismo GUID.
#[test]
fn el_guid_es_el_mismo_en_todos_los_requests() {
    let Some(v) = vectors() else { return };
    let guid = guid_capturado(&v);
    for r in v["ckcRequests"].as_array().unwrap() {
        assert!(
            r["parsed"]["guid"].as_str() == Some(guid.as_str()),
            "un request lleva otro GUID"
        );
    }
}
