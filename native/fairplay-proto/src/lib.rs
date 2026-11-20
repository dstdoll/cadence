//! Protocolo de key delivery de FairPlay para Apple Music en Android.
//!
//! Reconstruido decompilando `SVFootHillSessionCtrl::_getCKC`
//! (`libandroidappmusic.so` @ `0x20d1e8`) y **verificado contra tráfico real**
//! capturado con Frida en un dispositivo.
//!
//! El cliente arma un SPC (Server Playback Context) con la blob de FairPlay, lo manda
//! en un POST JSON al key server de Apple, y recibe un CKC (Content Key Context) que
//! vuelve a entrar a la blob para derivar la clave de contenido.
//!
//! Este crate cubre **sólo la parte de red y serialización**: lo que entra y sale del
//! key server. La generación del SPC y el procesado del CKC siguen siendo de la blob.
//!
//! Hay dos formatos de request según el `keyServerProtocolType`:
//!
//! * `"simplified"` — un objeto plano. Es el que usa Apple Music hoy.
//! * cualquier otro — el wrapper `fairplay-streaming-request` anidado, que es el
//!   formato genérico de los key servers FPS.

#![forbid(unsafe_code)]

use serde::{Deserialize, Serialize};

/// Endpoint de key delivery de Apple Music.
pub const KEY_SERVER_URL: &str = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/music/fps";

/// Valor de `keyFormat` que espera `getPersistentKey`.
pub const KEY_FORMAT: &str = "com.apple.streamingkeydelivery";
/// Valor de `keyFormatVersion`.
pub const KEY_FORMAT_VERSION: &str = "1";
/// El `keyServerProtocolType` que selecciona el formato plano.
pub const PROTOCOL_SIMPLIFIED: &str = "simplified";

/// `adamId` que Apple usa para el contexto compartido previo a cualquier track.
///
/// Cuando llega con este valor, la implementación original cachea el contexto
/// resultante y lo reutiliza para toda la sesión.
pub const PRESHARE_ADAM_ID: &str = "0";

/// El key server devolvió este status cuando no hay slot offline disponible.
///
/// El cliente original reintenta el request completo con `offline = false`.
pub const CKC_STATUS_SLOT_ERROR: i32 = -1004;

/// Qué formato de cuerpo usar.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Protocol {
    /// Objeto plano con `guid`/`offline`/`adamId`/`spc`/`keyUri`.
    Simplified,
    /// Wrapper `fairplay-streaming-request` → `streaming-keys` → `[{id, spc, uri}]`.
    Legacy,
}

impl Protocol {
    /// Interpreta el `keyServerProtocolType` como lo hace `_getCKC`: compara contra
    /// `"simplified"` y cualquier otra cosa cae en el formato anidado.
    pub fn from_type(key_server_protocol_type: &str) -> Self {
        if key_server_protocol_type == PROTOCOL_SIMPLIFIED {
            Self::Simplified
        } else {
            Self::Legacy
        }
    }
}

/// Qué pide un `keyUri`.
///
/// Las dos formas se distinguen por la **caja de la letra** que abre la ruta, algo que no
/// se nota hasta tener capturas de ambas:
///
/// ```text
/// skd://itunes.apple.com/P000000000/s1/e1    contexto compartido   (P mayúscula, /sN/eN)
/// skd://itunes.apple.com/p1000000001/c23     clave de un track     (p minúscula, /cN)
/// ```
///
/// La forma del URI y el `adamId` tienen que concordar: el contexto compartido viaja con
/// `adamId` en [`PRESHARE_ADAM_ID`], y un track con el suyo.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyUriKind {
    /// El contexto compartido que se pide una vez por sesión.
    SharedContext,
    /// La clave de una pista concreta.
    Track,
}

/// El host que aparece en todos los `keyUri` observados.
pub const KEY_URI_HOST: &str = "skd://itunes.apple.com/";

/// Clasifica un `keyUri`.
///
/// Devuelve `None` si no tiene ninguna de las dos formas conocidas.
pub fn classify_key_uri(key_uri: &str) -> Option<KeyUriKind> {
    let resto = key_uri.strip_prefix(KEY_URI_HOST)?;
    let (primero, cola) = resto.split_once('/')?;
    let inicial = primero.get(0..1)?;
    let id = primero.get(1..)?;
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    match inicial {
        "P" if cola.starts_with('s') && cola.contains("/e") => Some(KeyUriKind::SharedContext),
        "p" if cola.starts_with('c') => Some(KeyUriKind::Track),
        _ => None,
    }
}

/// Lo necesario para pedir una clave.
#[derive(Debug, Clone)]
pub struct KeyRequest {
    /// El GUID del dispositivo (ver el crate `device-guid`).
    pub guid: String,
    /// `adamId` del track, o [`PRESHARE_ADAM_ID`] para el contexto compartido.
    pub adam_id: String,
    /// El `skd://` que viene del playlist HLS.
    pub key_uri: String,
    /// El SPC ya en base64, tal como lo entrega la blob.
    pub spc_base64: String,
    /// Si pedir una clave persistible (requiere que el device tenga slot offline).
    pub offline: bool,
}

/// Cuerpo del formato `simplified`.
///
/// El orden de los campos replica el del original, que los inserta en un
/// `CFDictionary` en esta secuencia.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SimplifiedRequest {
    pub guid: String,
    /// Sólo aparece cuando se pide una clave offline. Apple lo manda como el **string**
    /// `"true"`, no como booleano JSON.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub offline: Option<String>,
    #[serde(rename = "adamId")]
    pub adam_id: String,
    pub spc: String,
    #[serde(rename = "keyUri")]
    pub key_uri: String,
}

/// Una entrada de `streaming-keys` en el formato legacy.
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
    /// Serializa el cuerpo del POST en el formato pedido.
    pub fn to_json(&self, protocol: Protocol) -> Result<String, Error> {
        let json = match protocol {
            Protocol::Simplified => serde_json::to_string(&SimplifiedRequest {
                guid: self.guid.clone(),
                // el original sólo agrega la clave cuando corresponde; si es false, no va
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

    /// Si este request es el del contexto compartido.
    pub fn is_preshare(&self) -> bool {
        self.adam_id == PRESHARE_ADAM_ID
    }

    /// Si el `keyUri` y el `adamId` concuerdan.
    ///
    /// Un request bien formado pide el contexto compartido con [`PRESHARE_ADAM_ID`] y un
    /// URI con `P` mayúscula, o una pista con su `adamId` y un URI con `p` minúscula.
    pub fn uri_concuerda_con_adam_id(&self) -> bool {
        match classify_key_uri(&self.key_uri) {
            Some(KeyUriKind::SharedContext) => self.is_preshare(),
            Some(KeyUriKind::Track) => !self.is_preshare(),
            None => false,
        }
    }
}

/// Una clave devuelta por el server.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StreamingKey {
    pub status: i32,
    pub ckc: String,
}

impl StreamingKey {
    /// Si el server pide reintentar sin slot offline.
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

/// Lee la respuesta del key server.
///
/// En `simplified` el objeto raíz ya trae `status` y `ckc`. En legacy hay que bajar por
/// `fairplay-streaming-response` → `streaming-keys` → primer elemento, que es
/// exactamente el recorrido que hace `_getCKCFromResponse`.
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

/// Qué hacer después de leer una respuesta.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Clave lista; el CKC en base64 va a la blob.
    Key { ckc_base64: String, status: i32 },
    /// El server rechazó el slot offline: repetir el request con `offline = false`.
    RetryWithoutOfflineSlot,
}

/// Aplica la política de reintento de `_getCKC`.
///
/// El original sólo reintenta cuando **pidió** offline y recibió
/// [`CKC_STATUS_SLOT_ERROR`]; con cualquier otro status se queda con la clave tal cual,
/// incluso si el status no es cero (quien decide si sirve es la blob, al procesarla).
pub fn evaluate(key: StreamingKey, requested_offline: bool) -> Outcome {
    if requested_offline && key.is_slot_error() {
        Outcome::RetryWithoutOfflineSlot
    } else {
        Outcome::Key { ckc_base64: key.ckc, status: key.status }
    }
}

/// Cabeceras fijas del POST. El resto (autenticación, Anisette, cookies) las agrega la
/// capa de `RequestContext`.
pub const CONTENT_TYPE: (&str, &str) = ("Content-Type", "application/json");

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("la respuesta no traía ninguna streaming key")]
    EmptyStreamingKeys,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Cuerpo real capturado con Frida sobre `HTTPMessage::setBody` en el dispositivo de
    /// pruebas, con los valores largos recortados (el `guid` real mide 111 y el `spc`
    /// 9600 caracteres). Lo que importa acá es la forma: nombres, orden y tipos.
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
    fn el_simplified_reproduce_el_cuerpo_capturado() {
        let r = req(PRESHARE_ADAM_ID, "skd://itunes.apple.com/P000000000/s1/e1", true);
        assert_eq!(r.to_json(Protocol::Simplified).unwrap(), CAPTURADO_PRESHARE);
    }

    #[test]
    fn el_simplified_reproduce_el_cuerpo_de_un_track() {
        let r = req("1632087479", "skd://itunes.apple.com/p1492777545/c6", true);
        assert_eq!(r.to_json(Protocol::Simplified).unwrap(), CAPTURADO_TRACK);
    }

    #[test]
    fn sin_offline_la_clave_no_aparece() {
        let r = req("123", "skd://x", false);
        let json = r.to_json(Protocol::Simplified).unwrap();
        assert!(!json.contains("offline"), "el original omite la clave en vez de mandar false");
        assert_eq!(json, r#"{"guid":"G","adamId":"123","spc":"S","keyUri":"skd://x"}"#);
    }

    #[test]
    fn offline_viaja_como_string_no_como_booleano() {
        let json = req("1", "skd://x", true).to_json(Protocol::Simplified).unwrap();
        assert!(json.contains(r#""offline":"true""#));
        assert!(!json.contains(r#""offline":true"#));
    }

    #[test]
    fn el_legacy_anida_en_streaming_keys() {
        let json = req("1", "skd://x", true).to_json(Protocol::Legacy).unwrap();
        assert_eq!(
            json,
            r#"{"fairplay-streaming-request":{"streaming-keys":[{"id":0,"spc":"S","uri":"skd://x"}]}}"#
        );
    }

    #[test]
    fn el_legacy_ignora_el_flag_offline() {
        // El formato anidado no tiene dónde ponerlo: el original tampoco lo manda.
        let a = req("1", "skd://x", true).to_json(Protocol::Legacy).unwrap();
        let b = req("1", "skd://x", false).to_json(Protocol::Legacy).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn el_tipo_de_protocolo_selecciona_el_formato() {
        assert_eq!(Protocol::from_type("simplified"), Protocol::Simplified);
        assert_eq!(Protocol::from_type("standard"), Protocol::Legacy);
        assert_eq!(Protocol::from_type(""), Protocol::Legacy);
    }

    #[test]
    fn parsea_una_respuesta_simplified() {
        let k = parse_response(r#"{"status":0,"ckc":"AAAAAQ"}"#, Protocol::Simplified).unwrap();
        assert_eq!(k.status, 0);
        assert_eq!(k.ckc, "AAAAAQ");
    }

    #[test]
    fn parsea_una_respuesta_legacy() {
        let body = r#"{"fairplay-streaming-response":{"streaming-keys":[{"status":0,"ckc":"AAAAAQ"}]}}"#;
        let k = parse_response(body, Protocol::Legacy).unwrap();
        assert_eq!(k.ckc, "AAAAAQ");
    }

    #[test]
    fn una_lista_vacia_es_error() {
        let body = r#"{"fairplay-streaming-response":{"streaming-keys":[]}}"#;
        assert!(matches!(
            parse_response(body, Protocol::Legacy),
            Err(Error::EmptyStreamingKeys)
        ));
    }

    #[test]
    fn el_slot_error_dispara_el_reintento() {
        let k = StreamingKey { status: CKC_STATUS_SLOT_ERROR, ckc: String::new() };
        assert!(k.is_slot_error());
        assert_eq!(evaluate(k, true), Outcome::RetryWithoutOfflineSlot);
    }

    #[test]
    fn el_slot_error_sin_haber_pedido_offline_no_reintenta() {
        // Si no se pidió offline, el original no entra en el bucle de reintento.
        let k = StreamingKey { status: CKC_STATUS_SLOT_ERROR, ckc: "X".into() };
        assert_eq!(
            evaluate(k, false),
            Outcome::Key { ckc_base64: "X".into(), status: CKC_STATUS_SLOT_ERROR }
        );
    }

    #[test]
    fn un_status_cualquiera_devuelve_la_clave() {
        let k = StreamingKey { status: 0, ckc: "CKC".into() };
        assert_eq!(evaluate(k, true), Outcome::Key { ckc_base64: "CKC".into(), status: 0 });
    }

    #[test]
    fn reconoce_el_contexto_compartido() {
        assert!(req(PRESHARE_ADAM_ID, "skd://x", false).is_preshare());
        assert!(!req("1632087479", "skd://x", false).is_preshare());
    }

    #[test]
    fn la_caja_de_la_letra_distingue_los_dos_tipos_de_uri() {
        assert_eq!(
            classify_key_uri("skd://itunes.apple.com/P000000000/s1/e1"),
            Some(KeyUriKind::SharedContext)
        );
        assert_eq!(
            classify_key_uri("skd://itunes.apple.com/p1000000001/c23"),
            Some(KeyUriKind::Track)
        );
        // la misma ruta con la caja cambiada no es ninguna de las dos
        assert_eq!(classify_key_uri("skd://itunes.apple.com/p000000000/s1/e1"), None);
        assert_eq!(classify_key_uri("skd://itunes.apple.com/P1000000001/c23"), None);
        assert_eq!(classify_key_uri("skd://otro.host/p1/c1"), None);
        assert_eq!(classify_key_uri("skd://itunes.apple.com/pXYZ/c1"), None);
    }

    #[test]
    fn el_uri_tiene_que_concordar_con_el_adam_id() {
        assert!(req(PRESHARE_ADAM_ID, "skd://itunes.apple.com/P000000000/s1/e1", true)
            .uri_concuerda_con_adam_id());
        assert!(req("100000001", "skd://itunes.apple.com/p1000000001/c23", true)
            .uri_concuerda_con_adam_id());
        // cruzados: no
        assert!(!req(PRESHARE_ADAM_ID, "skd://itunes.apple.com/p1000000001/c23", true)
            .uri_concuerda_con_adam_id());
        assert!(!req("100000001", "skd://itunes.apple.com/P000000000/s1/e1", true)
            .uri_concuerda_con_adam_id());
    }
}
