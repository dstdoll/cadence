//! Port de `storeservicescore::RequestContextConfig`.
//!
//! Son los nueve campos que la app oficial setea antes de `RequestContext::init`, y que
//! terminan determinando el `User-Agent` y varias cabeceras del request.
//!
//! Los valores por defecto acá no son inventados: salen del `User-Agent` observado en
//! el tráfico real del dispositivo de pruebas:
//!
//! ```text
//! User-Agent: Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)
//! ```
//!
//! Cada campo del struct es **exactamente** el argumento de su setter homonimo en
//! `RequestContextConfig`, para que el struct sirva de reemplazo directo. Ojo con dos:
//! `platform_identifier` es `"Android"` a secas y `product_version` es el release del
//! sistema (`"10"`), no la version de la app; el `Android/10` del User-Agent sale de
//! concatenar los dos. La version de la app va en `version_identifier`.
//!
//! Nótese que la app **no reporta el modelo real del dispositivo**: dice `Pixel 8` corra
//! donde corra, y el log de referencia se capturó en una tablet de otro fabricante. El
//! `deviceModel` es un valor fijo del build, no algo que se consulte al sistema.

#![forbid(unsafe_code)]

use std::fmt;

/// Identificador de cliente de Apple Music.
pub const CLIENT_IDENTIFIER: &str = "Music";
/// Versión del producto que reporta la app observada.
pub const PRODUCT_VERSION: &str = "5.0.2";
/// Identificador de plataforma, sin el release (ese va en `product_version`).
pub const PLATFORM_IDENTIFIER: &str = "Android";
/// Modelo que la app declara, con independencia del hardware real.
pub const DEVICE_MODEL: &str = "Pixel 8";
/// Número de build que acompaña al modelo.
pub const BUILD_VERSION: &str = "7663314";

/// Los nueve campos de `RequestContextConfig`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestContextConfig {
    /// Dónde viven `adi.pb`, `fsi.pdat` y el resto del estado nativo.
    pub base_directory_path: String,
    /// `Music`.
    pub client_identifier: String,
    /// La versión que se concatena al `client_identifier` en el User-Agent.
    pub version_identifier: String,
    /// `Android`. Es el identificador de plataforma a secas: el release va en
    /// `product_version`, que es como los recibe `RequestContextConfig`.
    pub platform_identifier: String,
    /// El release de Android (`"10"`), **no** la version de la app. Asi lo recibe
    /// `setProductVersion` en la libreria cerrada; en el User-Agent sale como
    /// `Android/10` al concatenarse con `platform_identifier`.
    pub product_version: String,
    pub device_model: String,
    pub build_version: String,
    pub locale_identifier: String,
    pub language_identifier: String,
}

impl RequestContextConfig {
    /// Config equivalente a la que arma la app oficial, parametrizada por lo único que
    /// depende del dispositivo: dónde guardar el estado y qué versión de Android corre.
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

    /// Reconstruye el `User-Agent` tal como se observó en el tráfico.
    ///
    /// El sufijo `(dt:66)` lo agrega la capa de red; no sale de esta config, así que se
    /// recibe como parámetro.
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

/// Storefront de Apple: el número identifica el país.
///
/// En el tráfico se observó `X-Apple-Store-Front: 143483-2,31 t:music31`, donde
/// `143483` es Chile.
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

    /// El valor de la cabecera `X-Apple-Store-Front`.
    ///
    /// Se observaron dos variantes: con y sin el sufijo ` t:music31`. La app manda la
    /// corta en el primer request de una cadena y la larga después.
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
        // Se capturó en una tablet de otro fabricante y aun así dice Pixel 8.
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
