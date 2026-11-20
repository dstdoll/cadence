//! Bindings to the closed Apple Music for Android libraries.
//!
//! These are the **57 symbols** the client actually uses, out of ~7000 exported by the
//! libraries (audited from the symbol inventory of the shipped Apple `.so` files).
//!
//! | library | symbols used | total |
//! |----------|-----------------|-----|
//! | `libstoreservicescore.so` | 38 | 1702 |
//! | `libandroidappmusic.so`   | 17 | 4005 |
//! | `libmediaplatform.so`     |  2 | 1419 |
//!
//! (The other 10 appearing in the analysis were `_Unwind_*` from libunwind, provided by the
//! runtime and unused in Rust.)
//!
//! ## What remains closed
//!
//! Only two things, both inside `libandroidappmusic.so`:
//!
//! * SPC generation, behind `SVFootHillPExchange::spc()`;
//! * GUID F1/F2 fields, behind `FairPlayGetAndroidGUIDFields`.
//!
//! Everything else in the playback path is ported in the `device-guid`, `fairplay-proto`, and
//! `store-config` crates.
//!
//! ## Obfuscated symbols
//!
//! Apple renamed part of the key-delivery API. The mapping was recovered by correlating call
//! order with error strings printed by the library itself:
//!
//! | real name | symbol |
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
// SAFETY: these are Apple C++ functions and they **throw exceptions**.
//
// That is why the blocks are declared `extern "C-unwind"` instead of `extern "C"`: an
// exception crossing an `extern "C"` boundary is UB in Rust and aborts the process.
// `C-unwind` lets unwinding pass through.
//
// Allowing unwind is not catching it. Any production caller of these functions must wrap them
// in the exception guard (`cxx_guard` in `cadence-native-rs`, macro `apple_externs!`), which
// turns a `throw` into a zero return instead of letting it unwind through Rust frames. This
// crate declares the ABI; it does not guard it.
// ---------------------------------------------------------------------------------------

pub mod abi;

use abi::{DevGuidConf256, ReqCtxInit88, SharedPtr, SretData, SretShared, StdString, StdVector};
use core::ffi::{c_char, c_int, c_long, c_ulong, c_void};

/// Decryption mode used by Apple Music for content keys.
pub const DECRYPT_MODE_CONTENT_KEY: u32 = 5;

/// `AuthenticateResponse::responseType()` value when sign-in succeeded.
pub const AUTH_RESPONSE_SIGNED_IN: c_int = 6;

// =====================================================================================
// libandroidappmusic.so -- FairPlay and playback lease
// =====================================================================================

unsafe extern "C-unwind" {
    /// `KDDecryptSample(ctx, mode, in, out, len) -> status`
    ///
    /// Decrypts an audio sample. With `mode` = [`DECRYPT_MODE_CONTENT_KEY`] and
    /// `input == output` it works in-place, which is how the app calls it: 30,267 calls
    /// were observed for one album, with `len` between 8500 and 10500 bytes.
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
    /// Converts a CKC into a persistent key. Only called on the offline branch.
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
    /// The eight arguments, in order, verified against real traffic:
    /// `defaultId`, `adamId` (same value repeated), `keyUri`, `keyFormat`
    /// (`"com.apple.streamingkeydelivery"`), `keyFormatVersion` (`"1"`), `serverUri`,
    /// `protocolType` (`"simplified"`), and the base64 FPS certificate.
    ///
    /// Returns `shared_ptr<SVFootHillPKey>` by value (sret).
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

    /// `SVFootHillPContext::kdContext() const` -- returns the handle consumed by
    /// [`KDDecryptSample`].
    #[link_name = "_ZNK18SVFootHillPContext9kdContextEv"]
    pub fn SVFootHillPContext_kdContext(this: *mut c_void) -> *mut *mut c_void;

    /// `SVPlaybackLeaseManager::SVPlaybackLeaseManager(std::function const&, std::function const&)`
    ///
    /// Both callbacks are `std::function`, which must be constructed matching the libc++ ABI
    /// (24-byte buffer + pointer, with the `__func` vtable).
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
// libstoreservicescore.so -- identity, context, HTTP, and authentication
// =====================================================================================

unsafe extern "C-unwind" {
    /// `DeviceGUID::instance()`
    #[link_name = "_ZN17storeservicescore10DeviceGUID8instanceEv"]
    pub fn DeviceGUID_instance() -> SretShared;

    /// `DeviceGUID::guid()` -- the built GUID, as `mediaplatform::Data`.
    #[link_name = "_ZN17storeservicescore10DeviceGUID4guidEv"]
    pub fn DeviceGUID_guid(this: *mut c_void) -> SretData;

    /// `DeviceGUID::configure(id, serial, apiNum, forceConfig)`
    ///
    /// `id` is the Android ID, `apiNum` is the SDK level. The algorithm that builds the GUID from
    /// this is ported in `device-guid`; this call is still needed because two of the six fields
    /// come from FairPlay.
    #[link_name = "_ZN17storeservicescore10DeviceGUID9configureERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_RKjRKb"]
    pub fn DeviceGUID_configure(
        this: *mut c_void,
        android_id: *const StdString,
        serial: *const StdString,
        api_num: *const u32,
        force_config: *const bool,
    ) -> DevGuidConf256;

    // ---- RequestContextConfig: the nine setters ----
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

    /// Executes the request. This is where the library injects auth and Anisette headers
    /// (`X-Dsid`, `X-Token`, `X-Apple-AMD`, ...).
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

    // ---- authentication ----
    #[link_name = "_ZNSt6__ndk110shared_ptrIN17storeservicescore16AuthenticateFlowEE11make_sharedIJRNS0_INS1_14RequestContextEEEEEES3_DpOT_"]
    pub fn make_authenticate_flow(ctx: *const SharedPtr) -> SretShared;

    #[link_name = "_ZN17storeservicescore16AuthenticateFlow3runEv"]
    pub fn AuthenticateFlow_run(this: *mut c_void);
    #[link_name = "_ZNK17storeservicescore16AuthenticateFlow8responseEv"]
    pub fn AuthenticateFlow_response(this: *mut c_void) -> *mut SharedPtr;

    /// Returns [`AUTH_RESPONSE_SIGNED_IN`] when sign-in succeeded.
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

    // ---- protocol dialogs ----
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

/// Resolves a symbol in any already-loaded library.
///
/// Useful for checking before calling anything that the expected native library is present: if
/// this returns `None` for `SVFootHillSessionCtrl_instance`, there is no point continuing.
///
/// # Safety
/// `name` must be NUL-terminated.
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
    fn constants_match_observed_values() {
        // mode=5 and in-place, verified across 30,267 real calls
        assert_eq!(DECRYPT_MODE_CONTENT_KEY, 5);
        assert_eq!(AUTH_RESPONSE_SIGNED_IN, 6);
    }
}
