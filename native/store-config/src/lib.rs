//! Port of `storeservicescore::RequestContextConfig`.
//!
//! These are the nine fields the official app sets before `RequestContext::init`, which determine
//! the `User-Agent` and several request headers.
//!
//! The default values here are not invented: they come from the `User-Agent` observed in real
//! traffic from the test device:
//!
//! ```text
//! User-Agent: Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)
//! ```
//!
//! Each struct field matches **exactly** the argument of its namesake setter on
//! `RequestContextConfig`, so the struct serves as a drop-in replacement. Note two of them:
//! `platform_identifier` is bare `"Android"` and `product_version` is the OS release (`"10"`), not
//! the app version; `Android/10` in the User-Agent comes from concatenating both. The app version
//! goes in `version_identifier`.
//!
//! Note that the app **does not report the real device model**: it says `Pixel 8` wherever it runs,
//! and the reference log was captured on a tablet from another manufacturer. `deviceModel` is a
//! fixed build constant, not queried from the system.

#![forbid(unsafe_code)]

use std::fmt;

/// Apple Music client identifier.
pub const CLIENT_IDENTIFIER: &str = "Music";
/// Product version reported by the observed app.
pub const PRODUCT_VERSION: &str = "5.0.2";
/// Platform identifier without the release (which goes in `product_version`).
pub const PLATFORM_IDENTIFIER: &str = "Android";
/// Model declared by the app, regardless of actual hardware.
pub const DEVICE_MODEL: &str = "Pixel 8";
/// Build number accompanying the model.
pub const BUILD_VERSION: &str = "7663314";

/// The nine fields of `RequestContextConfig`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestContextConfig {
    /// Where `adi.pb`, `fsi.pdat`, and the rest of the native state live.
    pub base_directory_path: String,
    /// `Music`.
    pub client_identifier: String,
    /// Version concatenated to `client_identifier` in the User-Agent.
    pub version_identifier: String,
    /// `Android`. Bare platform identifier: the release goes in `product_version`, matching how
    /// `RequestContextConfig` receives them.
    pub platform_identifier: String,
    /// Android release (`"10"`), **not** the app version. That is how `setProductVersion` receives
    /// it in the closed library; in the User-Agent it appears as `Android/10` when concatenated
    /// with `platform_identifier`.
    pub product_version: String,
    pub device_model: String,
    pub build_version: String,
    pub locale_identifier: String,
    pub language_identifier: String,
}

impl RequestContextConfig {
    /// Config equivalent to what the official app builds, parameterized only by device-dependent
    /// inputs: where to store state and which Android version is running.
    pub fn for_android(base_directory_path: impl Into<String>, android_release: &str) -> Self {
        Self {
            base_directory_path: base_directory_path.into(),
            client_identifier: CLIENT_IDENTIFIER.into(),
            version_identifier: PRODUCT_VERSION.into(),
            platform_identifier: PLATFORM_IDENTIFIER.into(),
            product_version: android_release.into(),
            device_model: DEVICE_MODEL.into(),
            build_version: BUILD_VERSION.into(),
            locale_identifier: "en-US".into(),
            language_identifier: "en-US".into(),
        }
    }

    /// Reconstructs the `User-Agent` exactly as observed in traffic.
    ///
    /// The `(dt:66)` suffix is added by the network layer rather than this config, so it is passed
    /// as a parameter.
    pub fn user_agent(&self, dt: u32) -> String {
        format!(
            "{}/{} {}/{} model/{} build/{} (dt:{})",
            self.client_identifier,
            self.version_identifier,
            self.platform_identifier,
            self.product_version,
            self.device_model,
            self.build_version,
            dt,
        )
    }
}

impl fmt::Display for RequestContextConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.user_agent(66))
    }
}

/// Apple storefront: the numeric ID identifies the country.
///
/// Traffic showed `X-Apple-Store-Front: 143483-2,31 t:music31`, where `143483` is Chile.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoreFront {
    pub id: u32,
    pub suffix: String,
}

impl StoreFront {
    pub const CHILE: u32 = 143483;
    pub const USA: u32 = 143441;

    pub fn new(id: u32) -> Self {
        Self { id, suffix: "2,31".into() }
    }

    /// `X-Apple-Store-Front` header value.
    ///
    /// Two variants were observed: with and without the ` t:music31` suffix. The app sends the
    /// short one on the first request of a chain and the long one afterward.
    pub fn header_value(&self, with_music_tag: bool) -> String {
        if with_music_tag {
            format!("{}-{} t:music31", self.id, self.suffix)
        } else {
            format!("{}-{}", self.id, self.suffix)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reconstruye_el_user_agent_capturado() {
        let c = RequestContextConfig::for_android("/data/user/0/app.cadence/files", "10");
        assert_eq!(
            c.user_agent(66),
            "Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)"
        );
    }

    #[test]
    fn el_modelo_es_fijo_no_el_del_dispositivo() {
        // Captured on a tablet from another manufacturer and it still says Pixel 8.
        let c = RequestContextConfig::for_android("/x", "10");
        assert_eq!(c.device_model, "Pixel 8");
    }

    #[test]
    fn el_release_de_android_va_en_product_version() {
        let c = RequestContextConfig::for_android("/x", "15");
        assert_eq!(c.platform_identifier, "Android");
        assert_eq!(c.product_version, "15");
        assert_eq!(c.version_identifier, PRODUCT_VERSION, "la version de la app no cambia");
        // y en el User-Agent los dos se concatenan
        assert!(c.user_agent(66).contains(" Android/15 model/"));
    }

    /// Cada campo tiene que poder pasarse tal cual a su setter de la libreria cerrada.
    /// Si alguien reordena los campos, el User-Agent se rompe y este test lo pilla.
    #[test]
    fn los_campos_corresponden_uno_a_uno_con_los_setters() {
        let c = RequestContextConfig::for_android("/data/user/0/app.cadence/files", "10");
        // El orden es el de las llamadas en el arranque nativo.
        let setters = [
            ("setBaseDirectoryPath", &c.base_directory_path),
            ("setClientIdentifier", &c.client_identifier),
            ("setVersionIdentifier", &c.version_identifier),
            ("setPlatformIdentifier", &c.platform_identifier),
            ("setProductVersion", &c.product_version),
            ("setDeviceModel", &c.device_model),
            ("setBuildVersion", &c.build_version),
            ("setLocaleIdentifier", &c.locale_identifier),
            ("setLanguageIdentifier", &c.language_identifier),
        ];
        assert_eq!(setters.len(), 9, "RequestContextConfig tiene nueve campos");
        for (nombre, valor) in setters {
            assert!(!valor.is_empty(), "{nombre} no puede ir vacio");
        }
        // Los cuatro que el User-Agent expone, en el orden en que salen.
        assert_eq!(
            c.user_agent(66),
            "Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)"
        );
    }

    #[test]
    fn el_storefront_arma_las_dos_variantes() {
        let sf = StoreFront::new(StoreFront::CHILE);
        assert_eq!(sf.header_value(false), "143483-2,31");
        assert_eq!(sf.header_value(true), "143483-2,31 t:music31");
    }
}
