//! Bindings a las librerías cerradas de Apple Music para Android.
//!
//! Son los **57 símbolos** que el cliente realmente usa, de un total de ~7000 que
//! exportan las librerías. Se declararon a partir del inventario en
//! `~/labs/re/cadence-nativas/api_surface.json`.
//!
//! | librería | símbolos usados | de |
//! |----------|-----------------|-----|
//! | `libstoreservicescore.so` | 38 | 1702 |
//! | `libandroidappmusic.so`   | 17 | 4005 |
//! | `libmediaplatform.so`     |  2 | 1419 |
//!
//! (Los otros 10 que aparecían en el análisis eran `_Unwind_*` de libunwind, que las
//! aporta el runtime y en Rust no existen.)
//!
//! ## Qué queda cerrado
//!
//! Sólo dos cosas, ambas dentro de `libandroidappmusic.so`:
//!
//! * la generación del SPC, detrás de `SVFootHillPExchange::spc()`;
//! * los campos F1/F2 del GUID, detrás de `FairPlayGetAndroidGUIDFields`.
//!
//! Todo lo demás del camino de reproducción está portado en los crates `device-guid`,
//! `fairplay-proto` y `store-config`.
//!
//! ## Símbolos obfuscados
//!
//! Apple renombró parte de la API de key delivery. El mapeo se recuperó correlacionando
//! el orden de las llamadas con el de los strings de error que la propia librería
//! imprime (ver `~/labs/re/cadence-nativas/DEOBFUSCACION.md`):
//!
//! | nombre real | símbolo |
//! |---|---|
//! | `KDDecryptSample` | `NfcRKVnxuKZy04KWbdFu71Ou` |
//! | `KDPersistFromCKCWithAT` | `MYiCHr2n1RFBPK6tHo` |
//! | `KDCanProcessCKC` | `ZZUDP2EEn9tLDQ` |
//! | `KDProcessResponseCKC` | `C956s6fM` |
//! | `KDIsValidDecrypt` | `jSNXSLgDwR` |
//! | `KDProcessPersistentKeyWithAT` | `Li197SADabUVzZISmm` |
//! | `KDGenerateRequestSPCWithMovieId` | `sWBoEyhXQd6FTiLw` |
//! | `KDGenMovieId` | `Qhl17oSYJtCJIxM` |
//! | `KDSetAndroidID` | `hgBR8sd3n7ox0` |
//! | `FairPlayGetAndroidGUIDFields` | `QHioSBsQR` |

#![allow(non_snake_case)]

// ---------------------------------------------------------------------------------------
// SEGURIDAD: estas funciones son C++ de Apple y **lanzan excepciones**.
//
// Por eso los bloques se declaran `extern "C-unwind"` y no `extern "C"`: una excepcion
// cruzando un borde `extern "C"` es comportamiento indefinido en Rust y aborta el
// proceso. `C-unwind` permite que el unwind pase.
//
// Permitir el unwind no es atraparlo. Quien llame a cualquiera de estas funciones en
// produccion tiene que envolverla en el guard de excepciones (`cxx_guard` del crate
// `cadence-native-rs`, macro `apple_externs!`), que convierte un `throw` en un retorno
// en cero en vez de dejarlo subir por frames de Rust. Este crate declara el ABI; no lo
// protege.
// ---------------------------------------------------------------------------------------

pub mod abi;

use abi::{DevGuidConf256, ReqCtxInit88, SharedPtr, SretData, SretShared, StdString, StdVector};
use core::ffi::{c_char, c_int, c_long, c_ulong, c_void};

/// Modo de descifrado que usa Apple Music para content keys.
pub const DECRYPT_MODE_CONTENT_KEY: u32 = 5;

/// Respuesta de `AuthenticateResponse::responseType()` cuando la sesión quedó iniciada.
pub const AUTH_RESPONSE_SIGNED_IN: c_int = 6;

// =====================================================================================
// libandroidappmusic.so — FairPlay y lease de reproducción
// =====================================================================================

unsafe extern "C-unwind" {
    /// `KDDecryptSample(ctx, mode, in, out, len) -> status`
    ///
    /// Descifra un sample de audio. Con `mode` = [`DECRYPT_MODE_CONTENT_KEY`] y
    /// `input == output` trabaja in-place, que es como lo llama la app: se observaron
    /// 30 267 llamadas para un álbum, con `len` entre 8500 y 10500 bytes.
    #[link_name = "NfcRKVnxuKZy04KWbdFu71Ou"]
    pub fn KDDecryptSample(
        ctx: *mut c_void,
        mode: u32,
        input: *mut c_void,
        output: *mut c_void,
        len: usize,
    ) -> i64;

    /// `KDPersistFromCKCWithAT(kdExchange, ckc, ckcLen, 0, 0, &outKey, &outLen) -> status`
    ///
    /// Convierte un CKC en clave persistente. Sólo se llama en la rama offline.
    #[link_name = "MYiCHr2n1RFBPK6tHo"]
    pub fn KDPersistFromCKCWithAT(
        kd_exchange: *mut c_void,
        ckc: *const c_void,
        ckc_len: usize,
        reserved0: usize,
        reserved1: usize,
        out_key: *mut *mut c_void,
        out_len: *mut u32,
    ) -> i32;

    /// `FootHillConfig::config(std::string const&)`
    #[link_name = "_ZN14FootHillConfig6configERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE"]
    pub fn FootHillConfig_config(path: *const StdString) -> c_int;

    /// `SVFootHillSessionCtrl::instance()`
    #[link_name = "_ZN21SVFootHillSessionCtrl8instanceEv"]
    pub fn SVFootHillSessionCtrl_instance() -> *mut c_void;

    /// `SVFootHillSessionCtrl::getPersistentKey(this, 8 × std::string const&)`
    ///
    /// Los ocho argumentos, en orden, verificados contra tráfico real:
    /// `defaultId`, `adamId` (el mismo valor repetido), `keyUri`, `keyFormat`
    /// (`"com.apple.streamingkeydelivery"`), `keyFormatVersion` (`"1"`), `serverUri`,
    /// `protocolType` (`"simplified"`) y el certificado FPS en base64.
    ///
    /// Devuelve `shared_ptr<SVFootHillPKey>` por valor (sret).
    #[link_name = "_ZN21SVFootHillSessionCtrl16getPersistentKeyERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEES8_S8_S8_S8_S8_S8_S8_"]
    pub fn SVFootHillSessionCtrl_getPersistentKey(
        this: *mut c_void,
        default_id: *const StdString,
        adam_id: *const StdString,
        key_uri: *const StdString,
        key_format: *const StdString,
        key_format_version: *const StdString,
        server_uri: *const StdString,
        protocol_type: *const StdString,
        fps_cert: *const StdString,
    ) -> SretShared;

    /// `SVFootHillSessionCtrl::decryptContext(this, key, SVDecryptorType const&, bool const&)`
    #[link_name = "_ZN21SVFootHillSessionCtrl14decryptContextERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEERKN11SVDecryptor15SVDecryptorTypeERKb"]
    pub fn SVFootHillSessionCtrl_decryptContext(
        this: *mut c_void,
        key: *const StdString,
        decryptor_type: *const c_int,
        flag: *const bool,
    ) -> SretShared;

    /// `SVFootHillPContext::kdContext() const` — devuelve el handle que consume
    /// [`KDDecryptSample`].
    #[link_name = "_ZNK18SVFootHillPContext9kdContextEv"]
    pub fn SVFootHillPContext_kdContext(this: *mut c_void) -> *mut *mut c_void;

    /// `SVPlaybackLeaseManager::SVPlaybackLeaseManager(std::function const&, std::function const&)`
    ///
    /// Los dos callbacks son `std::function`, que hay que construir respetando el ABI de
    /// libc++ (buffer de 24 bytes + puntero, con la vtable de `__func`).
    #[link_name = "_ZN22SVPlaybackLeaseManagerC2ERKNSt6__ndk18functionIFvRKiEEERKNS1_IFvRKNS0_10shared_ptrIN17storeservicescore19StoreErrorConditionEEEEEE"]
    pub fn SVPlaybackLeaseManager_new(
        this: *mut c_void,
        end_lease_cb: *const c_void,
        playback_error_cb: *const c_void,
    );

    #[link_name = "_ZN22SVPlaybackLeaseManager25refreshLeaseAutomaticallyERKb"]
    pub fn SVPlaybackLeaseManager_refreshLeaseAutomatically(this: *mut c_void, enable: *const bool);

    #[link_name = "_ZN22SVPlaybackLeaseManager12requestLeaseERKb"]
    pub fn SVPlaybackLeaseManager_requestLease(this: *mut c_void, flag: *const bool);

    /// `SVPlaybackLeaseManager::requestAsset(adamId, vector<string> const&, bool const&)`
    #[link_name = "_ZN22SVPlaybackLeaseManager12requestAssetERKmRKNSt6__ndk16vectorINS2_12basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEENS7_IS9_EEEERKb"]
    pub fn SVPlaybackLeaseManager_requestAsset(
        ret: *mut SharedPtr,
        this: *mut c_void,
        adam_id: *const c_ulong,
        params: *const StdVector,
        flag: *const bool,
    );

    #[link_name = "_ZNK23SVPlaybackAssetResponse13hasValidAssetEv"]
    pub fn SVPlaybackAssetResponse_hasValidAsset(this: *mut c_void) -> c_int;

    #[link_name = "_ZNK23SVPlaybackAssetResponse13playbackAssetEv"]
    pub fn SVPlaybackAssetResponse_playbackAsset(this: *mut c_void) -> *mut SharedPtr;

    /// `RequestContextManager::configure(shared_ptr<RequestContext> const&)`
    #[link_name = "_ZN21RequestContextManager9configureERKNSt6__ndk110shared_ptrIN17storeservicescore14RequestContextEEE"]
    pub fn RequestContextManager_configure(ctx: *mut SharedPtr);

    /// `androidstoreservices::AndroidPresentationInterface::setDialogHandler(...)`
    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface16setDialogHandlerEPFvlNSt6__ndk110shared_ptrIN17storeservicescore14ProtocolDialogEEENS2_INS_36AndroidProtocolDialogResponseHandlerEEEE"]
    pub fn AndroidPresentationInterface_setDialogHandler(
        this: *mut c_void,
        cb: extern "C" fn(c_long, *mut SharedPtr, *mut SharedPtr),
    );

    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface21setCredentialsHandlerEPFvNSt6__ndk110shared_ptrIN17storeservicescore18CredentialsRequestEEENS2_INS_33AndroidCredentialsResponseHandlerEEEE"]
    pub fn AndroidPresentationInterface_setCredentialsHandler(
        this: *mut c_void,
        cb: extern "C" fn(*mut SharedPtr, *mut SharedPtr),
    );

    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface28handleProtocolDialogResponseERKlRKNSt6__ndk110shared_ptrIN17storeservicescore22ProtocolDialogResponseEEE"]
    pub fn AndroidPresentationInterface_handleProtocolDialogResponse(
        this: *mut c_void,
        token: *const c_long,
        response: *mut SharedPtr,
    );

    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface25handleCredentialsResponseERKNSt6__ndk110shared_ptrIN17storeservicescore19CredentialsResponseEEE"]
    pub fn AndroidPresentationInterface_handleCredentialsResponse(
        this: *mut c_void,
        response: *mut SharedPtr,
    );

    /// `std::make_shared<AndroidPresentationInterface>()`
    #[link_name = "_ZNSt6__ndk110shared_ptrIN20androidstoreservices28AndroidPresentationInterfaceEE11make_sharedIJEEES3_DpOT_"]
    pub fn make_presentation_interface() -> SretShared;
}

// =====================================================================================
// libstoreservicescore.so — identidad, contexto, HTTP y autenticación
// =====================================================================================

unsafe extern "C-unwind" {
    /// `DeviceGUID::instance()`
    #[link_name = "_ZN17storeservicescore10DeviceGUID8instanceEv"]
    pub fn DeviceGUID_instance() -> SretShared;

    /// `DeviceGUID::guid()` — el GUID ya armado, como `mediaplatform::Data`.
    #[link_name = "_ZN17storeservicescore10DeviceGUID4guidEv"]
    pub fn DeviceGUID_guid(this: *mut c_void) -> SretData;

    /// `DeviceGUID::configure(id, serial, apiNum, forceConfig)`
    ///
    /// `id` es el Android ID, `apiNum` el nivel de SDK. El algoritmo que arma el GUID a
    /// partir de esto está portado en el crate `device-guid`; esta llamada sigue siendo
    /// necesaria porque dos de los seis campos vienen de FairPlay.
    #[link_name = "_ZN17storeservicescore10DeviceGUID9configureERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_RKjRKb"]
    pub fn DeviceGUID_configure(
        this: *mut c_void,
        android_id: *const StdString,
        serial: *const StdString,
        api_num: *const u32,
        force_config: *const bool,
    ) -> DevGuidConf256;

    // ---- RequestContextConfig: los nueve setters ----
    #[link_name = "_ZN17storeservicescore20RequestContextConfigC2Ev"]
    pub fn RequestContextConfig_new(this: *mut c_void);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig20setBaseDirectoryPathERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setBaseDirectoryPath(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig19setClientIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setClientIdentifier(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig20setVersionIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setVersionIdentifier(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig21setPlatformIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setPlatformIdentifier(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig17setProductVersionERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setProductVersion(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig14setDeviceModelERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setDeviceModel(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig15setBuildVersionERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setBuildVersion(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig19setLocaleIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setLocaleIdentifier(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig21setLanguageIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContextConfig_setLanguageIdentifier(this: *mut c_void, v: *const StdString);

    // ---- RequestContext ----
    /// `std::make_shared<RequestContext>(std::string&)`
    #[link_name = "_ZNSt6__ndk110shared_ptrIN17storeservicescore14RequestContextEE11make_sharedIJRNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEEEEES3_DpOT_"]
    pub fn make_request_context(path: *const StdString) -> SretShared;

    #[link_name = "_ZN17storeservicescore14RequestContext4initERKNSt6__ndk110shared_ptrINS_20RequestContextConfigEEE"]
    pub fn RequestContext_init(this: *mut c_void, config: *const SharedPtr) -> ReqCtxInit88;

    #[link_name = "_ZN17storeservicescore14RequestContext24setFairPlayDirectoryPathERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn RequestContext_setFairPlayDirectoryPath(this: *mut c_void, path: *const StdString);

    #[link_name = "_ZN17storeservicescore14RequestContext24setPresentationInterfaceERKNSt6__ndk110shared_ptrINS_21PresentationInterfaceEEE"]
    pub fn RequestContext_setPresentationInterface(this: *mut c_void, iface: *mut SharedPtr);

    // ---- URLRequest ----
    #[link_name = "_ZN17storeservicescore10URLRequestC2ERKNSt6__ndk110shared_ptrIN13mediaplatform11HTTPMessageEEERKNS2_INS_14RequestContextEEE"]
    pub fn URLRequest_new(this: *mut c_void, message: *mut SharedPtr, context: *mut SharedPtr);

    /// Ejecuta el request. Acá es donde la librería inyecta las cabeceras de
    /// autenticación y Anisette (`X-Dsid`, `X-Token`, `X-Apple-AMD`, …).
    #[link_name = "_ZN17storeservicescore10URLRequest3runEv"]
    pub fn URLRequest_run(this: *mut c_void);

    #[link_name = "_ZN17storeservicescore10URLRequest19setRequestParameterERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_"]
    pub fn URLRequest_setRequestParameter(
        this: *mut c_void,
        name: *const StdString,
        value: *const StdString,
    );

    #[link_name = "_ZNK17storeservicescore10URLRequest5errorEv"]
    pub fn URLRequest_error(this: *mut c_void) -> *mut SharedPtr;
    #[link_name = "_ZNK17storeservicescore10URLRequest8responseEv"]
    pub fn URLRequest_response(this: *mut c_void) -> *mut SharedPtr;
    #[link_name = "_ZNK17storeservicescore11URLResponse18underlyingResponseEv"]
    pub fn URLResponse_underlyingResponse(this: *mut c_void) -> *mut SharedPtr;

    // ---- autenticación ----
    #[link_name = "_ZNSt6__ndk110shared_ptrIN17storeservicescore16AuthenticateFlowEE11make_sharedIJRNS0_INS1_14RequestContextEEEEEES3_DpOT_"]
    pub fn make_authenticate_flow(ctx: *const SharedPtr) -> SretShared;

    #[link_name = "_ZN17storeservicescore16AuthenticateFlow3runEv"]
    pub fn AuthenticateFlow_run(this: *mut c_void);
    #[link_name = "_ZNK17storeservicescore16AuthenticateFlow8responseEv"]
    pub fn AuthenticateFlow_response(this: *mut c_void) -> *mut SharedPtr;

    /// Devuelve [`AUTH_RESPONSE_SIGNED_IN`] cuando la sesión quedó iniciada.
    #[link_name = "_ZNK17storeservicescore20AuthenticateResponse12responseTypeEv"]
    pub fn AuthenticateResponse_responseType(this: *mut c_void) -> c_int;

    #[link_name = "_ZNK17storeservicescore18CredentialsRequest28requiresHSA2VerificationCodeEv"]
    pub fn CredentialsRequest_requiresHSA2VerificationCode(this: *mut c_void) -> u8;

    #[link_name = "_ZN17storeservicescore19CredentialsResponseC1Ev"]
    pub fn CredentialsResponse_new(this: *mut c_void);
    #[link_name = "_ZN17storeservicescore19CredentialsResponse11setUserNameERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn CredentialsResponse_setUserName(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore19CredentialsResponse11setPasswordERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    pub fn CredentialsResponse_setPassword(this: *mut c_void, v: *const StdString);
    #[link_name = "_ZN17storeservicescore19CredentialsResponse15setResponseTypeENS0_12ResponseTypeE"]
    pub fn CredentialsResponse_setResponseType(this: *mut c_void, ty: c_int);

    // ---- diálogos del protocolo ----
    #[link_name = "_ZNK17storeservicescore14ProtocolDialog5titleEv"]
    pub fn ProtocolDialog_title(this: *mut c_void) -> *mut StdString;
    #[link_name = "_ZNK17storeservicescore14ProtocolDialog7buttonsEv"]
    pub fn ProtocolDialog_buttons(this: *mut c_void) -> *mut StdVector;
    #[link_name = "_ZNK17storeservicescore14ProtocolButton5titleEv"]
    pub fn ProtocolButton_title(this: *mut c_void) -> *mut StdString;
    #[link_name = "_ZN17storeservicescore22ProtocolDialogResponseC1Ev"]
    pub fn ProtocolDialogResponse_new(this: *mut c_void);
    #[link_name = "_ZN17storeservicescore22ProtocolDialogResponse17setSelectedButtonERKNSt6__ndk110shared_ptrINS_14ProtocolButtonEEE"]
    pub fn ProtocolDialogResponse_setSelectedButton(this: *mut c_void, button: *mut SharedPtr);

    // ---- errores ----
    #[link_name = "_ZNK17storeservicescore19StoreErrorCondition4whatEv"]
    pub fn StoreErrorCondition_what(this: *mut c_void) -> *const c_char;
    #[link_name = "_ZNK17storeservicescore19StoreErrorCondition9errorCodeEv"]
    pub fn StoreErrorCondition_errorCode(this: *mut c_void) -> *const i32;
    #[link_name = "_ZNK17storeservicescore19StoreErrorCondition17externalErrorCodeEv"]
    pub fn StoreErrorCondition_externalErrorCode(this: *mut c_void) -> *const i64;

    #[link_name = "_ZNK17storeservicescore13PlaybackAsset9URLStringEv"]
    pub fn PlaybackAsset_URLString(ret: *mut StdString, this: *mut c_void);
}

// =====================================================================================
// libmediaplatform.so
// =====================================================================================

unsafe extern "C-unwind" {
    /// `mediaplatform::HTTPMessage::HTTPMessage(std::string url, std::string method)`
    ///
    /// Ambos argumentos van **por valor**, no por referencia.
    #[link_name = "_ZN13mediaplatform11HTTPMessageC2ENSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES7_"]
    pub fn HTTPMessage_new(this: *mut c_void, url: StdString, method: StdString);

    #[link_name = "_ZNK13mediaplatform4Data5bytesEv"]
    pub fn Data_bytes(this: *mut c_void) -> *const c_char;
}

/// Resuelve un símbolo en cualquier librería ya cargada.
///
/// Útil para comprobar, antes de llamar nada, que la blob esperada está presente: si
/// esto devuelve `None` para `SVFootHillSessionCtrl_instance`, no tiene sentido seguir.
///
/// # Safety
/// `name` tiene que terminar en NUL.
pub unsafe fn resolve(name: &core::ffi::CStr) -> Option<*mut c_void> {
    unsafe extern "C-unwind" {
        fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    }
    // RTLD_DEFAULT en bionic LP64
    let p = unsafe { dlsym(core::ptr::null_mut(), name.as_ptr()) };
    if p.is_null() { None } else { Some(p) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn las_constantes_coinciden_con_lo_observado() {
        // mode=5 e in-place, verificado en 30 267 llamadas reales
        assert_eq!(DECRYPT_MODE_CONTENT_KEY, 5);
        assert_eq!(AUTH_RESPONSE_SIGNED_IN, 6);
    }
}
