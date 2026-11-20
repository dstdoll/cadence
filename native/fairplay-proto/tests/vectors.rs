//! Validación contra vectores capturados del dispositivo.
//!
//! `vectors.json` lo produce `export-vectors.js` enganchando las librerías de Apple
//! mientras la app reproduce. Estos tests comprueban que el port genera exactamente lo
//! mismo que la implementación original a partir de los mismos insumos.
//!
//! Si el archivo no está, los tests se saltan: el resto del crate se valida sin
//! dispositivo.

use fairplay_proto::*;

fn vectors() -> Option<serde_json::Value> {
    vectors_fixture::load(env!("CARGO_MANIFEST_DIR"))
}

/// Un arreglo de la captura que **tiene** que traer algo.
///
/// Antes se usaba `as_array().cloned().unwrap_or_default()`: con la seccion ausente
/// quedaba un vector vacio y los tests que iteran sobre el pasaban sin comprobar nada.
fn arreglo(v: &serde_json::Value, clave: &str) -> Vec<serde_json::Value> {
    let s = v.get(clave).unwrap_or_else(|| {
        panic!("vectors.json no trae la seccion {clave:?}")
    });
    let a = s
        .as_array()
        .unwrap_or_else(|| panic!("vectors.json: {clave:?} deberia ser un arreglo"));
    assert!(!a.is_empty(), "vectors.json: {clave:?} esta vacio, no hay nada que validar");
    a.clone()
}

/// Reconstruye cada cuerpo capturado a partir de sus campos y comprueba que coincide.
///
/// Es la prueba de que el serializador reproduce byte a byte lo que manda la app: mismo
/// orden de claves, mismo tratamiento de `offline`, mismos nombres.
///
/// El esperado se arma recorriendo las claves **en el orden capturado**: volver a
/// serializar el `Value` no sirve, porque serde_json ordena los mapas alfabéticamente y
/// el original no lo hace.
///
/// Los mensajes de error nunca incluyen el cuerpo: el SPC identifica al dispositivo.
#[test]
fn reproduce_los_cuerpos_capturados() {
    let Some(v) = vectors() else {
        eprintln!("sin vectors.json: salteado");
        return;
    };
    let reqs = arreglo(&v, "ckcRequests");
    assert!(!reqs.is_empty(), "vectors.json no trae ningún cuerpo");

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
            // recortamos el contexto: el cuerpo lleva el SPC del dispositivo
            panic!(
                "el cuerpo {i} difiere en el byte {at} (nuestro largo {}, capturado {})",
                ours.len(),
                theirs.len()
            );
        }

        // y el largo también, que es lo que midió el hook
        let len = r["raw_len"].as_u64().unwrap() as usize;
        assert_eq!(ours.len(), len, "el largo del cuerpo {i} no coincide");
    }
}

/// Las claves del JSON, en orden, son las que usa Apple.
/// El orden canonico de las claves del cuerpo CKC, tal como lo emite el original.
const ORDEN_CANONICO: [&str; 5] = ["guid", "offline", "adamId", "spc", "keyUri"];

#[test]
fn el_orden_de_las_claves_es_el_del_original() {
    let Some(v) = vectors() else { return };
    for (i, r) in arreglo(&v, "ckcRequests").iter().enumerate() {
        let keys: Vec<String> = r["keys"]
            .as_array()
            .unwrap()
            .iter()
            .map(|k| k.as_str().unwrap().to_owned())
            .collect();
        // `offline` es la unica clave opcional: el original no la agrega cuando es falsa
        // (ver `KeyRequest::to_json`). En la captura del dispositivo todas las peticiones
        // salieron offline, asi que todas traian las cinco; una peticion de streaming trae
        // cuatro y es igual de valida. Lo que se exige es el **orden relativo**, que es lo
        // que rompe el cuerpo si alguien reordena el struct.
        let esperado: Vec<&str> =
            ORDEN_CANONICO.iter().copied().filter(|k| keys.iter().any(|x| x == k)).collect();
        assert_eq!(keys, esperado, "el cuerpo {i} tiene las claves en otro orden");
        assert!(
            keys.iter().all(|k| ORDEN_CANONICO.contains(&k.as_str())),
            "el cuerpo {i} trae una clave que el original no manda: {keys:?}"
        );
        for obligatoria in ["guid", "adamId", "spc", "keyUri"] {
            assert!(
                keys.iter().any(|k| k == obligatoria),
                "al cuerpo {i} le falta {obligatoria}, que no es opcional"
            );
        }
    }
}

/// Las dos formas del cuerpo (con y sin `offline`) salen del mismo `to_json`.
///
/// Fija el detalle que importa: cuando `offline` es falso la clave **no va**, y cuando va
/// su valor es el string `"true"`, no el booleano. Mandar `"offline": false` es un cuerpo
/// que la app oficial nunca emite.
#[test]
fn offline_falso_omite_la_clave() {
    const ESPERADO_STRING: &str = "\"offline\":\"true\"";
    const ESPERADO_BOOL: &str = "\"offline\":true";
    let base = KeyRequest {
        guid: "a".repeat(111),
        adam_id: "100000001".into(),
        key_uri: "skd://itunes.apple.com/p1/c23".into(),
        spc_base64: "AAAA".into(),
        offline: false,
    };
    let sin = base.to_json(Protocol::Simplified).unwrap();
    assert!(!sin.contains("offline"), "con offline=false la clave no va: {sin}");

    let con = KeyRequest { offline: true, ..base }.to_json(Protocol::Simplified).unwrap();
    assert!(con.contains(ESPERADO_STRING), "offline va como string: {con}");
    assert!(!con.contains(ESPERADO_BOOL), "no como booleano: {con}");
}

/// Los argumentos constantes de `getPersistentKey` son los que declara este crate.
#[test]
fn las_constantes_coinciden_con_los_argumentos_reales() {
    let Some(v) = vectors() else { return };
    let args = arreglo(&v, "getPersistentKeyArgs");
    assert!(!args.is_empty(), "sin argumentos capturados");

    for a in &args {
        assert_eq!(a["keyFormat"].as_str().unwrap(), KEY_FORMAT);
        assert_eq!(a["keyFormatVersion"].as_str().unwrap(), KEY_FORMAT_VERSION);
        assert_eq!(a["serverUri"].as_str().unwrap(), KEY_SERVER_URL);
        assert_eq!(a["protocolType"].as_str().unwrap(), PROTOCOL_SIMPLIFIED);
        // defaultId y adamId son el mismo valor repetido
        assert_eq!(a["defaultId"], a["adamId"], "el original pasa el adamId dos veces");
    }
}

/// El `keyUri` y el `adamId` concuerdan en todos los requests capturados.
///
/// No se asume que el contexto compartido sea el primero: se pide una vez por sesión de
/// FairPlay, así que una captura que empieza con la app ya en marcha no lo incluye. Lo
/// que sí vale siempre es la concordancia entre la forma del URI y el `adamId`.
#[test]
fn el_uri_concuerda_con_el_adam_id_en_todo_lo_capturado() {
    let Some(v) = vectors() else {
        eprintln!("sin vectors.json: salteado");
        return;
    };
    let reqs = arreglo(&v, "ckcRequests");
    assert!(!reqs.is_empty(), "vectors.json no trae ningún cuerpo");

    let mut compartidos = 0;
    let mut tracks = 0;
    for (i, r) in reqs.iter().enumerate() {
        let p = &r["parsed"];
        let adam = p["adamId"].as_str().unwrap();
        let uri = p["keyUri"].as_str().unwrap();
        match classify_key_uri(uri) {
            Some(KeyUriKind::SharedContext) => {
                assert_eq!(adam, PRESHARE_ADAM_ID, "el request {i} pide el contexto compartido con otro adamId");
                compartidos += 1;
            }
            Some(KeyUriKind::Track) => {
                assert_ne!(adam, PRESHARE_ADAM_ID, "el request {i} pide un track con el adamId del preshare");
                assert!(adam.chars().all(|c| c.is_ascii_digit()), "el adamId del request {i} no es numérico");
                tracks += 1;
            }
            None => panic!("el keyUri del request {i} no tiene ninguna forma conocida"),
        }
    }
    assert_eq!(compartidos + tracks, reqs.len());
    eprintln!("clasificados: {compartidos} contexto compartido, {tracks} de track");
}

/// Si la captura trae el contexto compartido, es el primero de la sesión.
#[test]
fn el_contexto_compartido_abre_la_sesion() {
    let Some(v) = vectors() else { return };
    let reqs = arreglo(&v, "ckcRequests");
    let pos = reqs
        .iter()
        .position(|r| r["parsed"]["adamId"].as_str() == Some(PRESHARE_ADAM_ID));
    match pos {
        Some(0) => {}
        Some(n) => panic!("el contexto compartido salió en la posición {n}, no al abrir"),
        None => eprintln!("esta captura empezó con la sesión ya establecida: salteado"),
    }
}

/// `KDDecryptSample` se llama siempre en modo content-key y siempre in-place.
#[test]
fn el_descifrado_es_content_key_e_in_place() {
    let Some(v) = vectors() else { return };
    let d = &v["decryptSamples"];
    let count = d["count"].as_u64().unwrap_or(0);
    if count == 0 {
        eprintln!("no se capturaron samples: salteado");
        return;
    }
    assert_eq!(
        d["inPlace"].as_u64().unwrap(),
        count,
        "todas las llamadas deben ser in-place"
    );
    let modes = d["modes"].as_object().unwrap();
    assert_eq!(modes.len(), 1, "se esperaba un único modo");
    assert!(modes.contains_key("5"), "el modo debe ser content-key (5)");
}
