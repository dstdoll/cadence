//! Validación contra el GUID real capturado en el dispositivo.
//!
//! `vectors.json` lo produce `export-vectors.js` enganchando `DeviceGUID::configure`
//! (los insumos: Android ID y serial) y el tráfico al key server (el GUID resultante,
//! que es donde el valor realmente importa). Con ambos se comprueba el port de punta a
//! punta: mismos insumos → mismo GUID, carácter por carácter.
//!
//! **Sin dispositivo los tests se saltan; con un `vectors.json` incompleto fallan.** Un
//! archivo al que le falta una sección significa que ese hook no funcionó, y eso tiene
//! que verse en rojo: un salto silencioso se lee como validación y no lo es.
//!
//! OPSEC: el GUID identifica al dispositivo. Los mensajes de error no lo imprimen.

use device_guid::*;

fn vectors() -> Option<serde_json::Value> {
    vectors_fixture::load(env!("CARGO_MANIFEST_DIR"))
}

/// El GUID tal como viajó al key server.
fn guid_capturado(v: &serde_json::Value) -> String {
    let reqs = v["ckcRequests"].as_array().expect("vectors.json sin ckcRequests");
    let primero = reqs.first().expect("vectors.json sin ningún cuerpo CKC");
    primero["parsed"]["guid"]
        .as_str()
        .expect("el cuerpo CKC no trae guid")
        .to_owned()
}

/// Los insumos que recibió `DeviceGUID::configure`.
///
/// Se aceptan en dos lugares porque el harness los emite por separado desde que el hook
/// de `guid()` resultó frágil.
fn insumos(v: &serde_json::Value) -> (String, String) {
    let i = if v["deviceGuidInputs"].is_object() {
        &v["deviceGuidInputs"]
    } else {
        &v["deviceGuid"]["inputs"]
    };
    let id = i["androidId"].as_str().unwrap_or_default().to_owned();
    assert!(
        !id.is_empty(),
        "vectors.json no trae el Android ID: el hook de DeviceGUID::configure no capturó \
         nada, así que este test no puede validar el port. Volvé a correr el harness."
    );
    (id, i["serial"].as_str().unwrap_or_default().to_owned())
}

/// La estructura observada es la que declara el crate.
#[test]
fn la_estructura_capturada_tiene_seis_campos_de_ancho_fijo() {
    let Some(v) = vectors() else {
        eprintln!("sin vectors.json: salteado");
        return;
    };
    let guid = guid_capturado(&v);
    assert_eq!(guid.len(), GUID_LEN, "el largo del GUID capturado");
    let anchos: Vec<usize> = guid.split('.').map(str::len).collect();
    assert_eq!(anchos, vec![36, 14, 14, 14, 14, 14]);
}

/// Con los mismos insumos, el port produce el mismo GUID. Byte a byte.
///
/// Es la prueba de fuego: el único campo que no se puede derivar sin la blob (el
/// primero) se toma del capturado; todo lo demás lo calcula el port.
#[test]
fn reproduce_el_guid_capturado() {
    let Some(v) = vectors() else { return };
    let guid = guid_capturado(&v);
    let (android_id, serial) = insumos(&v);
    let parts = parse(&guid).expect("el capturado debe parsear");

    let nuestro = build(&DeviceInfo {
        android_id,
        serial,
        fairplay_field1: Some(parts.fairplay1.clone()),
        fairplay_field2: Some(parts.fairplay2.clone()),
    });
    assert!(
        nuestro == guid,
        "el GUID generado no coincide con el capturado (largos {} vs {})",
        nuestro.len(),
        guid.len()
    );
}

/// El cuarto campo es `sha1(androidId)` recortado, y nada más.
#[test]
fn el_hash_corresponde_al_android_id_de_los_insumos() {
    let Some(v) = vectors() else { return };
    let (android_id, _) = insumos(&v);
    let parts = parse(&guid_capturado(&v)).unwrap();
    assert_eq!(parts.android_id_hash, android_id_hash(&android_id));
    assert!(parts.matches_android_id(&android_id));
}

/// Los rellenos vinieron en ceros, y el campo 1 trae los datos de FairPlay.
#[test]
fn los_rellenos_capturados_estan_en_ceros() {
    let Some(v) = vectors() else { return };
    let parts = parse(&guid_capturado(&v)).unwrap();
    assert_eq!(parts.filler1, "0".repeat(FIELD_LEN));
    assert_eq!(parts.filler2, "0".repeat(FIELD_LEN));
    assert!(parts.looks_valid());
    assert!(parts.tiene_fairplay(), "el GUID capturado tiene que traer los datos de la blob");
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
