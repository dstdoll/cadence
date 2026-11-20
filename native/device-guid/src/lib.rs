//! Port of Apple Music for Android's `storeservicescore::DeviceGUID`.
//!
//! The GUID sent in the `guid` field of key-server requests consists of **six fixed-width fields
//! separated by `.`**:
//!
//! ```text
//! fairplay1 . fairplay2 . serial . androidIdHash . filler . filler
//!     36          14        14          14           14       14
//! ```
//!
//! Total: `36 + 14*5 + 5 separators = 111` characters, matching what is observed in real traffic.
//!
//! The key format rule is that **no field is left empty**: fields without data are ASCII zero-padded
//! to their width. That is why a GUID from a device that exposes no serial has fourteen zeros
//! rather than `..`.
//!
//! # What is confirmed and what is not
//!
//! Confirmed against the device using the `export-vectors.js` and `probe-guid-fields.py` hooks:
//!
//! * Full GUID: lengths `[36, 14, 14, 14, 14, 14]`, 111 chars.
//! * **Field 1** is the lowercase hex of an **18-byte** blob returned by
//!   `FairPlayGetAndroidGUIDFields`. Its output buffer was inspected: flag at `+0`, 18 bytes at
//!   `+1`, and length (`18`) at `+24`. Hex-encoding those bytes matches field 1 of the GUID sent to
//!   the key server character for character.
//! * **Field 4** is `sha1(android_id_ascii)` in hex, truncated to its last 14 chars. Verified two
//!   ways: the digest captured from `mediaplatform::CryptoHexStringFromData` and the field in the
//!   real GUID.
//!
//! What is **not** confirmed:
//!
//! * **Field 2** is fourteen zeros in all captures, and the descriptor following the first in the
//!   FairPlay buffer says `len = 7` -- seven bytes is exactly 14 hex chars, so it is most likely
//!   the second FairPlay field rather than filler. Its contents could not be read (that
//!   descriptor's pointer was unreadable), so it remains a reading rather than an established fact.
//!   That is why this crate exposes it as an **optional FairPlay field** rather than a constant: on
//!   another device it might carry data, and treating it as filler would yield a GUID rejected by
//!   the key server.
//! * Fields 5 and 6 were always fourteen zeros. The decompiled `configure` shows them as literals,
//!   though with lengths 15 and 14 instead of 14 and 14; where decompilation and capture disagree,
//!   the capture wins.

use sha1::{Digest, Sha1};

/// Width of the first field, supplied by FairPlay.
pub const FAIRPLAY_LEN: usize = 36;
/// Width of the remaining five fields.
pub const FIELD_LEN: usize = 14;
/// Number of fields.
pub const FIELD_COUNT: usize = 6;
/// Total length with all fields present.
pub const GUID_LEN: usize = FAIRPLAY_LEN + FIELD_LEN * 5 + (FIELD_COUNT - 1);

/// Byte length of the blob FairPlay returns for the first field.
///
/// 18 bytes -> 36 hex chars. Measured from the output buffer of `FairPlayGetAndroidGUIDFields`.
pub const FAIRPLAY_FIELD1_BYTES: usize = 18;

/// Byte length declared by the second field's descriptor.
///
/// 7 bytes -> 14 hex chars, matching field 2's width. See the module note: its contents could not
/// be read.
pub const FAIRPLAY_FIELD2_BYTES: usize = 7;

/// Padding Apple uses for an empty field.
fn padding(width: usize) -> String {
    "0".repeat(width)
}

/// Truncates or pads to `width`, matching the original.
///
/// If longer, keeps the **last** `width` characters (`erase(0, len - width)`); if shorter,
/// right-pads with zeros.
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

/// Field 4: `sha1` of the Android ID **as an ASCII string**, hex-encoded and truncated to 14 chars.
///
/// Apple hashes the 16 characters of the Android ID, not the 8 bytes they represent.
pub fn android_id_hash(android_id: &str) -> String {
    fit(&hex::encode(Sha1::digest(android_id.as_bytes())), FIELD_LEN)
}

/// Converts a FairPlay blob into the form used in the GUID.
///
/// `Base16Representation`: lowercase hex, then fitted to the field width.
pub fn fairplay_hex(bytes: &[u8], width: usize) -> String {
    fit(&hex::encode(bytes), width)
}

/// Device inputs used to build the GUID.
///
/// Both FairPlay fields are `Option` intentionally: `None` zero-pads them, producing a well-formed
/// GUID that the key server rejects. They are the only parts that cannot be derived without the
/// native blob.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DeviceInfo {
    /// Android ID (16 hex chars). The only strictly required field.
    pub android_id: String,
    /// Device serial. Most Android devices do not expose it; empty is normal.
    pub serial: String,
    /// Field 1: the 36 chars from `FairPlayGetAndroidGUIDFields` (18 bytes in hex).
    pub fairplay_field1: Option<String>,
    /// Field 2: the 14 chars of the second FairPlay field, if non-empty.
    ///
    /// On the test device this is all zeros. See the module note.
    pub fairplay_field2: Option<String>,
}

impl DeviceInfo {
    /// Builds the FairPlay fields from raw blobs as returned by the native library.
    pub fn with_fairplay_bytes(mut self, field1: &[u8], field2: Option<&[u8]>) -> Self {
        self.fairplay_field1 = Some(fairplay_hex(field1, FAIRPLAY_LEN));
        self.fairplay_field2 = field2.map(|b| fairplay_hex(b, FIELD_LEN));
        self
    }
}

/// Builds the GUID.
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
    /// Whether widths match expectations and trailing fillers are zeroed.
    pub fn looks_valid(&self) -> bool {
        self.fairplay1.len() == FAIRPLAY_LEN
            && self.fairplay2.len() == FIELD_LEN
            && self.serial.len() == FIELD_LEN
            && self.android_id_hash.len() == FIELD_LEN
            && self.filler1 == padding(FIELD_LEN)
            && self.filler2 == padding(FIELD_LEN)
    }

    /// Whether field 4 matches this Android ID.
    pub fn matches_android_id(&self, android_id: &str) -> bool {
        self.android_id_hash == android_id_hash(android_id)
    }

    /// Whether the GUID carries the FairPlay data the key server needs.
    ///
    /// A GUID with field 1 zeroed is well-formed but unusable: it occurs when the native blob
    /// could not be queried.
    pub fn has_fairplay(&self) -> bool {
        self.fairplay1 != padding(FAIRPLAY_LEN)
    }
}

/// Parses a captured GUID.
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

    // **Synthetic** values. A real device's ANDROID_ID and GUID are its own identifiers and do
    // not belong in the repository; what these tests verify is the algorithm, which does not
    // depend on which ID is passed. Values observed on-device are validated separately in
    // `tests/vectors.rs` against `native/vectors.json` (gitignored). These match what
    // `native/vectors-sample` uses, so the sample and these tests tell the same story.

    /// Synthetic ANDROID_ID: 16 hex chars like a real one, but fabricated.
    const ANDROID_ID: &str = "0123456789abcdef";

    /// `sha1("0123456789abcdef")`, matching what the library computes.
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
        assert!(!p.has_fairplay(), "without the blob the GUID is unusable");
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
