//! Port del `storeservicescore::DeviceGUID` de Apple Music para Android.
//!
//! El GUID que viaja en el campo `guid` del request al key server son **seis campos de
//! ancho fijo separados por `.`**:
//!
//! ```text
//! fairplay1 . fairplay2 . serial . androidIdHash . relleno . relleno
//!     36          14         14          14           14        14
//! ```
//!
//! Total: `36 + 14*5 + 5 separadores = 111` caracteres, que es exactamente lo que se
//! observa en el tráfico real.
//!
//! La clave del formato es que **ningún campo se deja vacío**: los que no tienen dato se
//! rellenan con ceros ASCII hasta su ancho. Por eso un GUID de un dispositivo que no
//! reporta serial no trae `..` sino catorce ceros.
//!
//! # Qué está confirmado y qué no
//!
//! Confirmado contra el dispositivo, con los hooks de `export-vectors.js` y
//! `probe-guid-fields.py`:
//!
//! * El GUID completo: largos `[36, 14, 14, 14, 14, 14]`, 111 chars.
//! * El **campo 1** es el base16 de un blob de **18 bytes** que entrega
//!   `FairPlayGetAndroidGUIDFields`. Se leyó el buffer de salida de esa función: el flag
//!   en `+0`, los 18 bytes en `+1`, y su largo (`18`) en `+24`. El base16 de esos bytes
//!   coincide carácter por carácter con el campo 1 del GUID que viajó al key server.
//! * El **campo 4** es `sha1(android_id_ascii)` en hex, recortado a sus últimos 14. Se
//!   verificó por dos caminos: el digest capturado de
//!   `mediaplatform::CryptoHexStringFromData` y el campo del GUID real.
//!
//! Lo que **no** está confirmado:
//!
//! * El **campo 2** sale en catorce ceros en todas las capturas, y el descriptor que
//!   sigue al primero en el buffer de FairPlay dice `len = 7` -- siete bytes son
//!   justo 14 chars en hex, así que lo más probable es que sea el segundo campo de
//!   FairPlay y no un relleno. No se pudo leer su contenido (el puntero de ese
//!   descriptor no resultó legible), así que queda como lectura, no como hecho. Por eso
//!   este crate lo expone como un campo **opcional de FairPlay** y no como una
//!   constante: en otro dispositivo podría traer datos, y tratarlo como relleno
//!   produciría un GUID que el key server rechaza.
//! * Los campos 5 y 6 salieron en catorce ceros siempre. El decompilado de `configure`
//!   los muestra como literales, aunque con largos 15 y 14 en vez de 14 y 14; donde el
//!   decompilado y la captura no coinciden, manda la captura.

use sha1::{Digest, Sha1};

/// Ancho del primer campo, el que aporta FairPlay.
pub const FAIRPLAY_LEN: usize = 36;
/// Ancho de los otros cinco campos.
pub const FIELD_LEN: usize = 14;
/// Cantidad de campos.
pub const FIELD_COUNT: usize = 6;
/// Largo total con todos los campos presentes.
pub const GUID_LEN: usize = FAIRPLAY_LEN + FIELD_LEN * 5 + (FIELD_COUNT - 1);

/// Largo en bytes del blob que FairPlay entrega para el primer campo.
///
/// 18 bytes -> 36 chars en hex. Medido en el buffer de salida de
/// `FairPlayGetAndroidGUIDFields`.
pub const FAIRPLAY_FIELD1_BYTES: usize = 18;

/// Largo en bytes que el descriptor del segundo campo declara.
///
/// 7 bytes -> 14 chars en hex, que es justo el ancho del campo 2. Ver la nota del módulo:
/// el contenido no se pudo leer.
pub const FAIRPLAY_FIELD2_BYTES: usize = 7;

/// El relleno que Apple usa para un campo sin dato.
fn padding(width: usize) -> String {
    "0".repeat(width)
}

/// Recorta o rellena a `width`, como hace el original.
///
/// Si sobra, se queda con los **últimos** `width` caracteres (`erase(0, len - width)`);
/// si falta, agrega ceros por la derecha.
fn fit(value: &str, width: usize) -> String {
    if value.len() > width {
        value[value.len() - width..].to_owned()
    } else {
        let mut s = String::with_capacity(width);
        s.push_str(value);
        while s.len() < width {
            s.push('0');
        }
        s
    }
}

/// Campo 4: `sha1` del Android ID **como cadena ASCII**, en hex, recortado a 14 chars.
///
/// Apple hashea los 16 caracteres del Android ID, no los 8 bytes que representan.
pub fn android_id_hash(android_id: &str) -> String {
    fit(&hex::encode(Sha1::digest(android_id.as_bytes())), FIELD_LEN)
}

/// Pasa un blob de FairPlay a la forma en que entra al GUID.
///
/// Es `Base16Representation`: hex en minúsculas, y después el ajuste al ancho del campo.
pub fn fairplay_hex(bytes: &[u8], width: usize) -> String {
    fit(&hex::encode(bytes), width)
}

/// Lo que el dispositivo aporta al GUID.
///
/// Los dos campos de FairPlay son `Option` a propósito: con `None` quedan en relleno, lo
/// que produce un GUID bien formado pero que el key server rechaza. Son lo único que no
/// se puede derivar sin la blob.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DeviceInfo {
    /// El Android ID (16 hex chars). Es lo único imprescindible.
    pub android_id: String,
    /// Serial del dispositivo. La mayoría de los Android no lo exponen; vacío es normal.
    pub serial: String,
    /// Campo 1: los 36 chars de `FairPlayGetAndroidGUIDFields` (18 bytes en hex).
    pub fairplay_field1: Option<String>,
    /// Campo 2: los 14 chars del segundo campo de FairPlay, si trae algo.
    ///
    /// En el dispositivo de pruebas sale en ceros. Ver la nota del módulo.
    pub fairplay_field2: Option<String>,
}

impl DeviceInfo {
    /// Construye los campos de FairPlay desde los blobs crudos, como los entrega la blob.
    pub fn with_fairplay_bytes(mut self, field1: &[u8], field2: Option<&[u8]>) -> Self {
        self.fairplay_field1 = Some(fairplay_hex(field1, FAIRPLAY_LEN));
        self.fairplay_field2 = field2.map(|b| fairplay_hex(b, FIELD_LEN));
        self
    }
}

/// Arma el GUID.
pub fn build(info: &DeviceInfo) -> String {
    let campo1 = info
        .fairplay_field1
        .as_deref()
        .map(|f| fit(f, FAIRPLAY_LEN))
        .unwrap_or_else(|| padding(FAIRPLAY_LEN));

    let campo2 = info
        .fairplay_field2
        .as_deref()
        .map(|f| fit(f, FIELD_LEN))
        .unwrap_or_else(|| padding(FIELD_LEN));

    let serial = if info.serial.is_empty() {
        padding(FIELD_LEN)
    } else {
        fit(&info.serial, FIELD_LEN)
    };

    let id_hash = android_id_hash(&info.android_id);

    [
        campo1,
        campo2,
        serial,
        id_hash,
        padding(FIELD_LEN),
        padding(FIELD_LEN),
    ]
    .join(".")
}

/// Los seis campos de un GUID.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GuidParts {
    /// Base16 del primer blob de FairPlay.
    pub fairplay1: String,
    /// Base16 del segundo campo de FairPlay; en ceros en todo lo observado.
    pub fairplay2: String,
    pub serial: String,
    pub android_id_hash: String,
    /// Relleno. Siempre en ceros en lo observado.
    pub filler1: String,
    /// Relleno. Siempre en ceros en lo observado.
    pub filler2: String,
}

impl GuidParts {
    /// Si los anchos son los esperados y los rellenos finales están en ceros.
    pub fn looks_valid(&self) -> bool {
        self.fairplay1.len() == FAIRPLAY_LEN
            && self.fairplay2.len() == FIELD_LEN
            && self.serial.len() == FIELD_LEN
            && self.android_id_hash.len() == FIELD_LEN
            && self.filler1 == padding(FIELD_LEN)
            && self.filler2 == padding(FIELD_LEN)
    }

    /// Si el campo 4 corresponde a este Android ID.
    pub fn matches_android_id(&self, android_id: &str) -> bool {
        self.android_id_hash == android_id_hash(android_id)
    }

    /// Si el GUID trae los datos de FairPlay que el key server necesita.
    ///
    /// Un GUID con el campo 1 en ceros está bien formado pero no sirve: se arma cuando no
    /// se pudo hablar con la blob.
    pub fn tiene_fairplay(&self) -> bool {
        self.fairplay1 != padding(FAIRPLAY_LEN)
    }
}

/// Descompone un GUID capturado.
pub fn parse(guid: &str) -> Option<GuidParts> {
    let p: Vec<&str> = guid.split('.').collect();
    if p.len() != FIELD_COUNT {
        return None;
    }
    Some(GuidParts {
        fairplay1: p[0].to_owned(),
        fairplay2: p[1].to_owned(),
        serial: p[2].to_owned(),
        android_id_hash: p[3].to_owned(),
        filler1: p[4].to_owned(),
        filler2: p[5].to_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // Valores **sinteticos**. El ANDROID_ID y el GUID de un dispositivo real son
    // identificadores suyos y no van en el repositorio; lo que estos tests comprueban es
    // el algoritmo, que no depende de qué ID se le dé. Los valores observados en el
    // dispositivo se validan aparte, en `tests/vectors.rs` contra `native/vectors.json`
    // (gitignorado). Son los mismos que usa `native/vectors-sample`, asi que la muestra y
    // estos tests cuentan la misma historia.

    /// ANDROID_ID sintetico: 16 hex como los de verdad, pero inventado.
    const ANDROID_ID: &str = "0123456789abcdef";

    /// `sha1("0123456789abcdef")`, que es lo que hace
    /// `mediaplatform::CryptoHexStringFromData` sobre el ID en ASCII.
    const OBSERVED_SHA1: &str = "fe5567e8d769550852182cdf69d74bb16dff8e29";

    /// Campo 4 = los ultimos 7 bytes de ese digest, en hex.
    const OBSERVED_FIELD4: &str = "d74bb16dff8e29";

    /// 18 bytes sinteticos con la forma de los que deja `FairPlayGetAndroidGUIDFields`, y
    /// el campo 1 que resulta de ellos.
    const FP1_BYTES: [u8; FAIRPLAY_FIELD1_BYTES] = [
        0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6, 0xa1, 0xb2, 0xc3,
        0xd4, 0xe5, 0xf6,
    ];
    const OBSERVED_FIELD1: &str = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";

    fn info() -> DeviceInfo {
        DeviceInfo {
            android_id: ANDROID_ID.into(),
            serial: String::new(),
            fairplay_field1: Some(OBSERVED_FIELD1.into()),
            fairplay_field2: None,
        }
    }

    #[test]
    fn el_digest_es_sha1_del_android_id_ascii() {
        assert_eq!(hex::encode(Sha1::digest(ANDROID_ID.as_bytes())), OBSERVED_SHA1);
    }

    #[test]
    fn el_campo4_son_los_ultimos_siete_bytes_del_digest() {
        assert_eq!(android_id_hash(ANDROID_ID), OBSERVED_FIELD4);
    }

    /// Los 18 bytes de FairPlay dan el campo 1 tal cual, sin recorte ni relleno.
    #[test]
    fn los_18_bytes_de_fairplay_dan_el_campo1() {
        assert_eq!(fairplay_hex(&FP1_BYTES, FAIRPLAY_LEN), OBSERVED_FIELD1);
        assert_eq!(FP1_BYTES.len() * 2, FAIRPLAY_LEN, "18 bytes son 36 chars en hex");
    }

    /// Siete bytes son justo el ancho del campo 2.
    #[test]
    fn siete_bytes_llenan_el_campo2() {
        assert_eq!(FAIRPLAY_FIELD2_BYTES * 2, FIELD_LEN);
        let ceros = [0u8; FAIRPLAY_FIELD2_BYTES];
        assert_eq!(fairplay_hex(&ceros, FIELD_LEN), "00000000000000");
    }

    #[test]
    fn el_guid_mide_111() {
        assert_eq!(build(&info()).len(), GUID_LEN);
        assert_eq!(GUID_LEN, 111);
    }

    #[test]
    fn los_anchos_son_los_observados() {
        let widths: Vec<usize> = build(&info()).split('.').map(str::len).collect();
        assert_eq!(widths, vec![36, 14, 14, 14, 14, 14], "los largos del GUID capturado");
    }

    #[test]
    fn los_campos_sin_dato_van_en_ceros_no_vacios() {
        let i = DeviceInfo { android_id: ANDROID_ID.into(), ..Default::default() };
        let p = parse(&build(&i)).unwrap();
        assert_eq!(p.fairplay1, "0".repeat(FAIRPLAY_LEN));
        assert_eq!(p.fairplay2, "00000000000000");
        assert_eq!(p.serial, "00000000000000", "sin serial va relleno, no vacío");
        assert_eq!(p.filler1, "00000000000000");
        assert_eq!(p.filler2, "00000000000000");
        assert!(!p.tiene_fairplay(), "sin la blob el GUID no sirve");
    }

    #[test]
    fn el_hash_queda_en_el_cuarto_campo() {
        let p = parse(&build(&info())).unwrap();
        assert_eq!(p.android_id_hash, OBSERVED_FIELD4);
        assert!(p.matches_android_id(ANDROID_ID));
        assert!(!p.matches_android_id("0000000000000000"));
    }

    /// El GUID real, armado de punta a punta desde los bytes crudos de FairPlay.
    #[test]
    fn reproduce_el_guid_real_desde_los_bytes_de_fairplay() {
        let i = DeviceInfo {
            android_id: ANDROID_ID.into(),
            serial: String::new(),
            ..Default::default()
        }
        .with_fairplay_bytes(&FP1_BYTES, None);
        assert_eq!(
            build(&i),
            format!("{OBSERVED_FIELD1}.00000000000000.00000000000000.{OBSERVED_FIELD4}.00000000000000.00000000000000")
        );
    }

    /// El campo 2 no es una constante: si FairPlay trae datos, viajan.
    #[test]
    fn el_campo2_lleva_los_datos_de_fairplay_cuando_los_hay() {
        let i = DeviceInfo { android_id: ANDROID_ID.into(), ..Default::default() }
            .with_fairplay_bytes(&FP1_BYTES, Some(&[1, 2, 3, 4, 5, 6, 7]));
        assert_eq!(parse(&build(&i)).unwrap().fairplay2, "01020304050607");
    }

    #[test]
    fn un_serial_presente_ocupa_su_campo() {
        let i = DeviceInfo {
            android_id: ANDROID_ID.into(),
            serial: "ABC123".into(),
            ..Default::default()
        };
        let p = parse(&build(&i)).unwrap();
        assert_eq!(p.serial, "ABC12300000000", "se rellena a 14");
        assert_eq!(build(&i).len(), GUID_LEN);
    }

    #[test]
    fn fit_recorta_por_la_izquierda_y_rellena_por_la_derecha() {
        assert_eq!(fit("abcdefghijklmnopqrst", 14), "ghijklmnopqrst");
        assert_eq!(fit("abc", 14), "abc00000000000");
        assert_eq!(fit("", 14), "00000000000000");
    }

    #[test]
    fn parse_rechaza_una_cantidad_distinta_de_campos() {
        assert!(parse("a.b.c.d.e").is_none());
        assert!(parse("a.b.c.d.e.f.g").is_none());
        assert!(parse("sin-separadores").is_none());
        assert!(parse("a.b.c.d.e.f").is_some());
    }

    #[test]
    fn looks_valid_exige_los_anchos_y_los_rellenos_en_cero() {
        assert!(parse(&build(&info())).unwrap().looks_valid());
        assert!(!parse("a.b.c.d.e.f").unwrap().looks_valid());
    }
}
