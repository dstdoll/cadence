//! Validación contra el `User-Agent` real capturado en el dispositivo.
//!
//! `vectors.json` lo produce `export-vectors.js` enganchando
//! `HTTPMessage::setHeader`. Acá se comprueba que el port reconstruye la cabecera
//! exactamente, y que los campos fijos del crate son los que la app reporta de verdad.
//!
//! Si el archivo no está, los tests se saltan (y `CADENCE_VECTORS_REQUIRED=1` hace que
//! ni eso: falla). Si está pero no trae `userAgent`, o no parsea, **falla**.

use store_config::*;

/// Sin captura se salta; con una captura incompleta o corrupta **falla**.
/// Ver `vectors-fixture`: el `else { return }` de antes daba verde sin mirar nada.
fn user_agent_capturado() -> Option<String> {
    vectors_fixture::string_section(env!("CARGO_MANIFEST_DIR"), "userAgent")
}

/// Descompone `Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)`.
fn partes(ua: &str) -> (String, String, String, String, String, u32) {
    let (cliente_ver, resto) = ua.split_once(' ').expect("falta el espacio tras la versión");
    let (cliente, version) = cliente_ver.split_once('/').expect("falta client/version");
    let plataforma = resto.split(" model/").next().unwrap().to_owned();
    let resto = resto.split_once(" model/").unwrap().1;
    let (modelo, resto) = resto.split_once(" build/").expect("falta build/");
    let (build, dt) = resto.split_once(" (dt:").expect("falta (dt:");
    let dt = dt.trim_end_matches(')').parse().expect("dt debe ser un número");
    (
        cliente.to_owned(),
        version.to_owned(),
        plataforma,
        modelo.to_owned(),
        build.to_owned(),
        dt,
    )
}

/// El port reconstruye la cabecera capturada carácter por carácter.
#[test]
fn reconstruye_el_user_agent_del_dispositivo() {
    let Some(ua) = user_agent_capturado() else {
        eprintln!("sin vectors.json: salteado");
        return;
    };
    let (_, _, plataforma, _, _, dt) = partes(&ua);
    let release = plataforma.strip_prefix("Android/").expect("la plataforma es Android/<rel>");

    let c = RequestContextConfig::for_android("/data/user/0/app.cadence/files", release);
    assert_eq!(c.user_agent(dt), ua, "la cabecera reconstruida no coincide");
}

/// Los valores fijos del crate son los que la app reporta.
#[test]
fn las_constantes_coinciden_con_lo_capturado() {
    let Some(ua) = user_agent_capturado() else { return };
    let (cliente, version, _, modelo, build, _) = partes(&ua);
    assert_eq!(cliente, CLIENT_IDENTIFIER);
    assert_eq!(version, PRODUCT_VERSION);
    assert_eq!(modelo, DEVICE_MODEL);
    assert_eq!(build, BUILD_VERSION);
}

/// El modelo declarado es el fijo del build, no el del hardware que corrió la captura.
///
/// Es el invariante que importa y se comprueba sin nombrar ningun fabricante: el
/// `User-Agent` tiene que coincidir con la constante del crate. Si algun dia la app
/// empezara a reportar el modelo real, este test lo pilla.
#[test]
fn el_modelo_declarado_es_el_fijo_del_build() {
    let Some(ua) = user_agent_capturado() else { return };
    let (_, _, _, modelo, _, _) = partes(&ua);
    assert_eq!(modelo, DEVICE_MODEL, "el modelo del User-Agent es la constante del build");
}
