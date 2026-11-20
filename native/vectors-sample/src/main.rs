//! Genera `native/vectors.sample.json`: la captura de referencia **sin datos del
//! dispositivo**.
//!
//! La captura real (`native/vectors.json`, gitignorada) sale del dispositivo de pruebas y
//! trae su ANDROID_ID, su GUID de FairPlay, los SPC crudos y los IDs de los temas que se
//! reprodujeron. Nada de eso puede ir al repositorio; pero sin ninguna captura los tests
//! de vectores no validan nada. Esta muestra es el punto medio: misma forma, mismos
//! invariantes, valores sinteticos.
//!
//! Lo que la hace util es que **sale de los ports, no de literales pegados a mano**:
//!
//! - el GUID lo calcula `device_guid::build`, asi que el campo del hash es el hash de
//!   verdad del ANDROID_ID sintetico y `matches_android_id` comprueba el algoritmo;
//! - los cuerpos CKC los serializa `fairplay_proto::KeyRequest::to_json`, asi que `keys`,
//!   `parsed` y `raw_len` son por construccion lo que la app manda — incluido el detalle
//!   de que la clave `offline` **no aparece** cuando es falsa, y que cuando aparece su
//!   valor es el string `"true"` y no el booleano;
//! - el User-Agent lo arma `store_config`.
//!
//! Por eso no se escribe a mano: una muestra inventada puede describir un cuerpo que la
//! app nunca manda, y entonces el test que la compara falla por culpa de la muestra.
//!
//! ```sh
//! cd native/vectors-sample && cargo run --quiet > ../vectors.sample.json
//! ```

use device_guid::{DeviceInfo, FAIRPLAY_LEN};
use fairplay_proto::{KeyRequest, Protocol};
use serde_json::{json, Map, Value};

/// ID sintetico: 16 hex como los de verdad, pero evidentemente inventado.
const ANDROID_ID: &str = "0123456789abcdef";
/// Campo 1 de FairPlay: 36 hex (18 bytes), con un patron reconocible a ojo.
const FAIRPLAY_FIELD1: &str = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";

/// Bytes deterministas en base64, del largo que tendria un SPC real recortado.
fn spc_sintetico(semilla: u8, largo: usize) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let data: Vec<u8> = (0..largo)
        .map(|i| (i as u8).wrapping_mul(31).wrapping_add(semilla))
        .collect();
    let mut s = String::new();
    for c in data.chunks(3) {
        let b = [c[0], *c.get(1).unwrap_or(&0), *c.get(2).unwrap_or(&0)];
        let n = u32::from(b[0]) << 16 | u32::from(b[1]) << 8 | u32::from(b[2]);
        for i in 0..4 {
            if i <= c.len() {
                s.push(T[(n >> (18 - 6 * i)) as usize & 63] as char);
            } else {
                s.push('=');
            }
        }
    }
    s
}

fn main() {
    // ---- GUID, con el algoritmo del port ----
    let info = DeviceInfo {
        android_id: ANDROID_ID.to_owned(),
        serial: String::new(), // la mayoria de los Android no lo exponen
        fairplay_field1: Some(FAIRPLAY_FIELD1.to_owned()),
        fairplay_field2: None, // en el dispositivo real sale en ceros
    };
    let guid = device_guid::build(&info);
    let campos: Vec<&str> = guid.split('.').collect();
    assert_eq!(campos.len(), 6, "el GUID tiene seis campos");
    assert_eq!(campos[0].len(), FAIRPLAY_LEN, "el primer campo son 36 hex");
    assert!(
        device_guid::parse(&guid).is_some_and(|p| p.matches_android_id(ANDROID_ID)),
        "el GUID generado tiene que corresponder a su propio ANDROID_ID"
    );

    // ---- cuerpos CKC, serializados por el propio port ----
    // Una pista de streaming y una offline, para cubrir las dos ramas de `offline`.
    let pistas = [("100000001", "skd://itunes.apple.com/p1000000001/c23", false),
                  ("100000002", "skd://itunes.apple.com/p1000000002/c23", true)];
    let mut ckc_requests = Vec::new();
    let mut key_args = Vec::new();
    for (i, (adam, uri, offline)) in pistas.iter().enumerate() {
        let req = KeyRequest {
            guid: guid.clone(),
            adam_id: (*adam).to_owned(),
            key_uri: (*uri).to_owned(),
            spc_base64: spc_sintetico(i as u8 + 1, 96),
            offline: *offline,
        };
        let cuerpo = req.to_json(Protocol::Simplified).expect("to_json");
        // `keys` y `parsed` se leen de vuelta del cuerpo: asi reflejan exactamente lo que
        // el port emite, incluido que `offline` falte cuando es falso.
        let parsed: Map<String, Value> =
            serde_json::from_str(&cuerpo).expect("el cuerpo que emite el port es JSON");
        ckc_requests.push(json!({
            "raw_len": cuerpo.len(),
            "keys": parsed.keys().cloned().collect::<Vec<_>>(),
            "parsed": parsed,
        }));
        key_args.push(json!({
            "defaultId": adam,
            "adamId": adam,
            "keyUri": uri,
            "keyFormat": fairplay_proto::KEY_FORMAT,
            "keyFormatVersion": fairplay_proto::KEY_FORMAT_VERSION,
            "serverUri": fairplay_proto::KEY_SERVER_URL,
            "protocolType": fairplay_proto::PROTOCOL_SIMPLIFIED,
            "fpsCertLen": 3464,
        }));
    }

    // ---- User-Agent, desde store-config ----
    // No identifica el dispositivo: la app declara "Pixel 8" fijo, corra donde corra. Va
    // tal cual porque es justo lo que el port tiene que reproducir.
    let ua = store_config::RequestContextConfig::for_android(
        "/data/user/0/app.cadence/files",
        "10",
    )
    .user_agent(66);

    let salida = json!({
        "_nota": "Muestra sintetica. La genera `cargo run` en native/vectors-sample; no sale \
de ningun dispositivo: el ANDROID_ID, el GUID, los SPC y los IDs de pista son inventados. \
El GUID lo calcula device_guid::build y los cuerpos los serializa \
fairplay_proto::to_json, asi que todo es consistente y los tests validan los algoritmos de \
verdad. La captura real va en native/vectors.json, que esta gitignorado.",
        "deviceGuidInputs": {
            "androidId": ANDROID_ID,
            "serial": Value::Null,
            "apiNum": 29,
            "forceConfig": true,
            "origen": "muestra sintetica (no DeviceGUID::configure de un dispositivo)"
        },
        "deviceGuid": {
            "origen": "device_guid::build sobre los inputs de arriba",
            "inputs": Value::Null,
            "guid": guid,
            "length": guid.len(),
            "fields": campos,
        },
        "ckcRequests": ckc_requests,
        "userAgent": ua,
        "getPersistentKeyArgs": key_args,
        "decryptSamples": {
            "count": 6137,
            "modes": { "5": 6137 },
            "inPlace": 6137,
            "lens": [32, 32, 2420, 9499, 8512, 10244]
        }
    });
    println!("{}", serde_json::to_string_pretty(&salida).expect("serializar"));
}
