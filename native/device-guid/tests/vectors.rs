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
fn captured_guid(v: &serde_json::Value) -> String {
    let reqs = v["ckcRequests"].as_array().expect("vectors.json missing ckcRequests");
    let first = reqs.first().expect("vectors.json has no CKC request body");
    first["parsed"]["guid"]
        .as_str()
        .expect("CKC body missing guid")
        .to_owned()
}

/// Inputs received by `DeviceGUID::configure`.
///
/// Accepted in two places because the harness emits them separately since hooking `guid()` proved
/// fragile.
fn inputs(v: &serde_json::Value) -> (String, String) {
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
fn captured_structure_has_six_fixed_width_fields() {
    let Some(v) = vectors() else {
        eprintln!("no vectors.json: skipped");
        return;
    };
    let guid = captured_guid(&v);
    assert_eq!(guid.len(), GUID_LEN, "captured GUID length");
    let widths: Vec<usize> = guid.split('.').map(str::len).collect();
    assert_eq!(widths, vec![36, 14, 14, 14, 14, 14]);
}

/// Given the same inputs, the port produces the exact same GUID byte for byte.
///
/// The acid test: the only field that cannot be derived without the blob (the first) is taken
/// from the capture; everything else is computed by the port.
#[test]
fn reproduces_captured_guid() {
    let Some(v) = vectors() else { return };
    let guid = captured_guid(&v);
    let (android_id, serial) = inputs(&v);
    let parts = parse(&guid).expect("captured GUID must parse");

    let ours = build(&DeviceInfo {
        android_id,
        serial,
        fairplay_field1: Some(parts.fairplay1.clone()),
        fairplay_field2: Some(parts.fairplay2.clone()),
    });
    assert!(
        ours == guid,
        "generated GUID does not match captured GUID (lengths {} vs {})",
        ours.len(),
        guid.len()
    );
}

/// The fourth field is truncated `sha1(androidId)` and nothing else.
#[test]
fn hash_matches_android_id_from_inputs() {
    let Some(v) = vectors() else { return };
    let (android_id, _) = inputs(&v);
    let parts = parse(&captured_guid(&v)).unwrap();
    assert_eq!(parts.android_id_hash, android_id_hash(&android_id));
    assert!(parts.matches_android_id(&android_id));
}

/// Fillers came in as zeros, and field 1 carries FairPlay data.
#[test]
fn captured_fillers_are_zeroed() {
    let Some(v) = vectors() else { return };
    let parts = parse(&captured_guid(&v)).unwrap();
    assert_eq!(parts.filler1, "0".repeat(FIELD_LEN));
    assert_eq!(parts.filler2, "0".repeat(FIELD_LEN));
    assert!(parts.looks_valid());
    assert!(parts.has_fairplay(), "captured GUID must carry FairPlay blob data");
}

/// Field 1 matches the base16 encoding of the 18 bytes returned by FairPlay.
#[test]
fn field1_is_base16_of_18_bytes() {
    let Some(v) = vectors() else { return };
    let parts = parse(&captured_guid(&v)).unwrap();
    assert_eq!(parts.fairplay1.len(), FAIRPLAY_FIELD1_BYTES * 2);
    assert!(
        parts.fairplay1.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()),
        "Base16Representation outputs lowercase hex"
    );
}

/// The test device does not report a serial, and the field is still zero-padded.
#[test]
fn missing_serial_field_is_padded() {
    let Some(v) = vectors() else { return };
    let (_, serial) = inputs(&v);
    let parts = parse(&captured_guid(&v)).unwrap();
    if serial.is_empty() {
        assert_eq!(parts.serial, "0".repeat(FIELD_LEN), "missing serial is zero-padded, not empty");
    } else {
        assert_eq!(parts.serial.len(), FIELD_LEN);
    }
}

/// Every request carries the same GUID.
#[test]
fn guid_is_identical_across_requests() {
    let Some(v) = vectors() else { return };
    let guid = captured_guid(&v);
    for r in v["ckcRequests"].as_array().unwrap() {
        assert!(
            r["parsed"]["guid"].as_str() == Some(guid.as_str()),
            "a request carries a different GUID"
        );
    }
}
