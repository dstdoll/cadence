//! The StoreServices orchestration ported from main_native.c + native_core.c to Rust: FootHill /
//! RequestContext init, the sign-in dialog/credential handlers, the playback lease, m3u8 + FairPlay
//! key fetch, and the media-user-token mint. Every call into the Apple blobs is `unsafe` FFI by
//! mangled symbol, using the ABI primitives in `sys` (std::string view, shared_ptr, sret). The flat-C
//! `core_fh_*` layer is gone; `engine.rs` calls these functions directly.
//!
//! Process-wide mutable state (the C globals) lives here behind atomics / a mutex; `engine.rs`
//! serialises init and lease construction with its own lifecycle lock, matching the single-threaded
//! init the C relied on. The credential handler runs on a StoreServices thread during login and reads
//! the credentials + 2FA state under the mutex.

#![allow(non_upper_case_globals)]

use crate::bridges;
use crate::sys::{self, SharedPtr, StdString, StdVector};
use std::ffi::{c_char, c_int, c_long, c_ulong, c_void, CStr, CString};
use std::io::Write;
use std::ptr;
use std::sync::atomic::{AtomicPtr, Ordering};
use std::sync::{Condvar, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::cxx_guard;

// ---- StoreServices symbol bindings (methods/accessors; the sret-returning ones go through bridges) -
// libc / bionic (plain C: nothing here throws).
extern "C" {
    fn _resolv_set_nameservers_for_net(netid: u32, servers: *const *const c_char, num: c_int, domains: *const c_char, params: *const c_void);
    fn setenv(name: *const c_char, value: *const c_char, overwrite: c_int) -> c_int;
}

// Addresses only: these are called through inline asm below (sret/arg layout), each inside its own
// guarded thunk, never directly.
extern "C" {
    #[link_name = "_ZN22SVPlaybackLeaseManager12requestAssetERKmRKNSt6__ndk16vectorINS2_12basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEENS7_IS9_EEEERKb"]
    fn lease_mgr_request_asset_raw();
    #[link_name = "_ZNK17storeservicescore13PlaybackAsset9URLStringEv"]
    fn playback_asset_url_raw();
    #[link_name = "_ZN10FootHillSF9getSFDataEv"]
    fn foothill_sf_get_sf_data_raw();
    #[link_name = "_ZN10FootHillSF10getSF2DataEv"]
    fn foothill_sf_get_sf2_data_raw();
    #[link_name = "_ZN10FootHillSF9getDpInfoEv"]
    fn foothill_sf_get_dp_info_raw();
    #[link_name = "_ZN18SVDecryptorFactory6createERKN11SVDecryptor15SVDecryptorTypeEPKhRKjS5_S7_RKNS0_20SVDecryptorTrackTypeERKbSC_"]
    fn sv_decryptor_factory_create_raw();
}

// `__shared_ptr_emplace` vtables for the objects we construct in place.
extern "C" {
    // __shared_ptr_emplace vtables for the objects we construct in place.
    static _ZTVNSt6__ndk120__shared_ptr_emplaceIN17storeservicescore22ProtocolDialogResponseENS_9allocatorIS2_EEEE: usize;
    static _ZTVNSt6__ndk120__shared_ptr_emplaceIN17storeservicescore19CredentialsResponseENS_9allocatorIS2_EEEE: usize;
    static _ZTVNSt6__ndk120__shared_ptr_emplaceIN17storeservicescore20RequestContextConfigENS_9allocatorIS2_EEEE: usize;
    static _ZTVNSt6__ndk120__shared_ptr_emplaceIN13mediaplatform11HTTPMessageENS_9allocatorIS2_EEEE: usize;
}

// Apple C++ functions. Each becomes a same-named `unsafe fn` that runs under the C++ exception catch
// (see `cxx_guard`); a throw returns zero and skips the rest of the entry point.
apple_externs! {
    #[link_name = "_ZN14FootHillConfig6configERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE"]
    fn foothill_config(s: *const StdString);
    #[link_name = "_ZN21SVFootHillSessionCtrl8instanceEv"]
    fn fhsession_instance() -> *mut c_void;
    #[link_name = "_ZNK18SVFootHillPContext9kdContextEv"]
    fn pcontext_kd_context(this: *mut c_void) -> *mut *mut c_void;
    // RequestContext / RequestContextConfig / Manager.
    #[link_name = "_ZN21RequestContextManager9configureERKNSt6__ndk110shared_ptrIN17storeservicescore14RequestContextEEE"]
    fn reqctx_manager_configure(reqctx_sp: *mut SharedPtr);
    #[link_name = "_ZN17storeservicescore14RequestContext24setFairPlayDirectoryPathERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqctx_set_fairplay_dir(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore14RequestContext24setPresentationInterfaceERKNSt6__ndk110shared_ptrINS_21PresentationInterfaceEEE"]
    fn reqctx_set_presentation(this: *mut c_void, pres_sp: *mut SharedPtr);
    #[link_name = "_ZN17storeservicescore20RequestContextConfigC2Ev"]
    fn reqcfg_ctor(this: *mut c_void);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig20setBaseDirectoryPathERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_base_dir(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig19setClientIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_client_id(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig20setVersionIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_version_id(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig21setPlatformIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_platform_id(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig17setProductVersionERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_product_version(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig14setDeviceModelERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_device_model(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig15setBuildVersionERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_build_version(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig19setLocaleIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_locale_id(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore20RequestContextConfig21setLanguageIdentifierERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn reqcfg_set_language_id(this: *mut c_void, s: *const StdString);
    // AndroidPresentationInterface handlers + response delivery.
    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface16setDialogHandlerEPFvlNSt6__ndk110shared_ptrIN17storeservicescore14ProtocolDialogEEENS2_INS_36AndroidProtocolDialogResponseHandlerEEEE"]
    fn ap_set_dialog_handler(this: *mut c_void, cb: extern "C" fn(c_long, *mut SharedPtr, *mut SharedPtr));
    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface21setCredentialsHandlerEPFvNSt6__ndk110shared_ptrIN17storeservicescore18CredentialsRequestEEENS2_INS_33AndroidCredentialsResponseHandlerEEEE"]
    fn ap_set_credentials_handler(this: *mut c_void, cb: extern "C" fn(*mut SharedPtr, *mut SharedPtr));
    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface28handleProtocolDialogResponseERKlRKNSt6__ndk110shared_ptrIN17storeservicescore22ProtocolDialogResponseEEE"]
    fn ap_handle_dialog_response(this: *mut c_void, j: *const c_long, resp: *mut SharedPtr);
    #[link_name = "_ZN20androidstoreservices28AndroidPresentationInterface25handleCredentialsResponseERKNSt6__ndk110shared_ptrIN17storeservicescore19CredentialsResponseEEE"]
    fn ap_handle_credentials_response(this: *mut c_void, resp: *mut SharedPtr);
    // ProtocolDialog / ProtocolButton / ProtocolDialogResponse.
    #[link_name = "_ZNK17storeservicescore14ProtocolDialog5titleEv"]
    fn dialog_title(this: *mut c_void) -> *mut StdString;
    #[link_name = "_ZNK17storeservicescore14ProtocolDialog7buttonsEv"]
    fn dialog_buttons(this: *mut c_void) -> *mut StdVector;
    #[link_name = "_ZNK17storeservicescore14ProtocolButton5titleEv"]
    fn button_title(this: *mut c_void) -> *mut StdString;
    #[link_name = "_ZN17storeservicescore22ProtocolDialogResponseC1Ev"]
    fn dialog_response_ctor(this: *mut c_void);
    #[link_name = "_ZN17storeservicescore22ProtocolDialogResponse17setSelectedButtonERKNSt6__ndk110shared_ptrINS_14ProtocolButtonEEE"]
    fn dialog_response_set_button(this: *mut c_void, button: *mut SharedPtr);
    // CredentialsRequest / CredentialsResponse.
    #[link_name = "_ZNK17storeservicescore18CredentialsRequest28requiresHSA2VerificationCodeEv"]
    fn cred_req_requires_2fa(this: *mut c_void) -> u8;
    #[link_name = "_ZN17storeservicescore19CredentialsResponseC1Ev"]
    fn cred_response_ctor(this: *mut c_void);
    #[link_name = "_ZN17storeservicescore19CredentialsResponse11setUserNameERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn cred_response_set_user(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore19CredentialsResponse11setPasswordERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"]
    fn cred_response_set_pass(this: *mut c_void, s: *const StdString);
    #[link_name = "_ZN17storeservicescore19CredentialsResponse15setResponseTypeENS0_12ResponseTypeE"]
    fn cred_response_set_type(this: *mut c_void, ty: c_int);
    // AuthenticateFlow / AuthenticateResponse.
    #[link_name = "_ZN17storeservicescore16AuthenticateFlow3runEv"]
    fn auth_flow_run(this: *mut c_void);
    #[link_name = "_ZNK17storeservicescore16AuthenticateFlow8responseEv"]
    fn auth_flow_response(this: *mut c_void) -> *mut SharedPtr;
    #[link_name = "_ZNK17storeservicescore20AuthenticateResponse12responseTypeEv"]
    fn auth_response_type(this: *mut c_void) -> c_int;
    // Playback lease + asset.
    #[link_name = "_ZN22SVPlaybackLeaseManagerC2ERKNSt6__ndk18functionIFvRKiEEERKNS1_IFvRKNS0_10shared_ptrIN17storeservicescore19StoreErrorConditionEEEEEE"]
    fn lease_mgr_ctor(this: *mut c_void, end_cb: *const c_void, err_cb: *const c_void);
    #[link_name = "_ZN22SVPlaybackLeaseManager25refreshLeaseAutomaticallyERKb"]
    fn lease_mgr_refresh_auto(this: *mut c_void, b: *const u8);
    #[link_name = "_ZN22SVPlaybackLeaseManager12requestLeaseERKb"]
    fn lease_mgr_request_lease(this: *mut c_void, b: *const u8);
    #[link_name = "_ZNK23SVPlaybackAssetResponse13hasValidAssetEv"]
    fn asset_resp_has_valid(this: *mut c_void) -> c_int;
    #[link_name = "_ZNK23SVPlaybackAssetResponse13playbackAssetEv"]
    fn asset_resp_playback_asset(this: *mut c_void) -> *mut SharedPtr;
    // Token mint: HTTPMessage / URLRequest / URLResponse / Data.
    #[link_name = "_ZN13mediaplatform11HTTPMessageC2ENSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES7_"]
    fn http_message_ctor(this: *mut c_void, url: *const StdString, method: *const StdString);
    #[link_name = "_ZN13mediaplatform11HTTPMessage9setHeaderERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_"]
    fn http_message_set_header(this: *mut c_void, name: *const StdString, value: *const StdString);
    #[link_name = "_ZN13mediaplatform11HTTPMessage11setBodyDataEPcm"]
    fn http_message_set_body(this: *mut c_void, data: *mut c_char, len: usize);
    #[link_name = "_ZN17storeservicescore10URLRequestC2ERKNSt6__ndk110shared_ptrIN13mediaplatform11HTTPMessageEEERKNS2_INS_14RequestContextEEE"]
    fn url_request_ctor(this: *mut c_void, msg: *mut SharedPtr, reqctx: *mut SharedPtr);
    #[link_name = "_ZN17storeservicescore10URLRequest19setRequestParameterERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_"]
    fn url_request_set_param(this: *mut c_void, name: *const StdString, value: *const StdString);
    #[link_name = "_ZN17storeservicescore10URLRequest3runEv"]
    fn url_request_run(this: *mut c_void);
    #[link_name = "_ZNK17storeservicescore10URLRequest5errorEv"]
    fn url_request_error(this: *mut c_void) -> *mut SharedPtr;
    #[link_name = "_ZNK17storeservicescore10URLRequest8responseEv"]
    fn url_request_response(this: *mut c_void) -> *mut SharedPtr;
    #[link_name = "_ZNK17storeservicescore11URLResponse18underlyingResponseEv"]
    fn url_response_underlying(this: *mut c_void) -> *mut SharedPtr;
    // StoreErrorCondition accessors, to log why a request failed. `what` is the message (the
    // condition's own string); the two codes are references to values stored inside the object.
    #[link_name = "_ZNK17storeservicescore19StoreErrorCondition4whatEv"]
    fn store_error_what(this: *mut c_void) -> *const c_char;
    #[link_name = "_ZNK17storeservicescore19StoreErrorCondition9errorCodeEv"]
    fn store_error_code(this: *mut c_void) -> *const i32;
    #[link_name = "_ZNK17storeservicescore19StoreErrorCondition17externalErrorCodeEv"]
    fn store_error_external(this: *mut c_void) -> *const i64;
    #[link_name = "_ZNK13mediaplatform4Data5bytesEv"]
    fn data_bytes(this: *mut c_void) -> *const c_char;
    #[link_name = "_ZNK13mediaplatform4Data6lengthEv"]
    fn data_length(this: *const c_void) -> usize;
    #[link_name = "_ZN10FootHillSFC1ERKNSt6__ndk110shared_ptrIN17storeservicescore13PlaybackAssetEEE"]
    fn foothill_sf_ctor(this: *mut c_void, asset: *const SharedPtr);
    #[allow(non_snake_case)]
    fn YHrWZQ6wU(state: *mut c_void, inp: *const u8, len: u32, out: *mut u8, flags: usize) -> i32;
    #[link_name = "_ZNSt6__ndk119__shared_weak_count16__release_sharedEv"]
    fn cpp_release_shared(ctrl: *mut c_void);
}

// ---- guarded calls made through inline asm -------------------------------------------------------
// A few Apple methods return a `shared_ptr`/`std::string` in x8 with an argument layout Rust cannot
// express, so they are called with `blr`. Each such call runs inside this one thunk, under the C++
// exception catch (see `cxx_guard`): x8 is the sret slot, x0..x7 the arguments. Returns false when
// the call was skipped or threw (the sret slot is then left as the caller initialised it).
#[repr(C)]
struct AsmCall {
    f: usize,
    x8: usize,
    x: [usize; 8],
}
const _: () = assert!(!std::mem::needs_drop::<AsmCall>());
extern "C-unwind" fn asm_call_thunk(p: *mut c_void) {
    let a = unsafe { &*(p as *const AsmCall) };
    // SAFETY: `a.f` is the address of an Apple function taking these registers (see each caller).
    unsafe {
        std::arch::asm!(
            "blr {f}",
            f = in(reg) a.f,
            in("x8") a.x8,
            in("x0") a.x[0],
            in("x1") a.x[1],
            in("x2") a.x[2],
            in("x3") a.x[3],
            in("x4") a.x[4],
            in("x5") a.x[5],
            in("x6") a.x[6],
            in("x7") a.x[7],
            clobber_abi("C"),
        );
    }
}
pub(crate) unsafe fn asm_call(name: &'static str, f: unsafe extern "C" fn(), x8: usize, x: [usize; 8]) -> bool {
    let mut c = AsmCall { f: f as *const () as usize, x8, x };
    cxx_guard::run(name, f as *const () as usize, asm_call_thunk, &mut c as *mut AsmCall as *mut c_void)
}

// ---- process-wide state (the C globals) --------------------------------------------------------
static FH_INSTANCE: AtomicPtr<c_void> = AtomicPtr::new(ptr::null_mut());
static REQCTX: AtomicPtr<c_void> = AtomicPtr::new(ptr::null_mut()); // heap shared_ptr<RequestContext>*
static AP_INF: AtomicPtr<c_void> = AtomicPtr::new(ptr::null_mut()); // heap shared_ptr<Presentation>*
static PRESHARE_CTX: AtomicPtr<c_void> = AtomicPtr::new(ptr::null_mut());

/// The 16-byte SVPlaybackLeaseManager, constructed in place once (guarded by engine's lifecycle lock).
struct LeaseBuf(std::cell::UnsafeCell<[u8; 16]>);
unsafe impl Sync for LeaseBuf {}
static LEASE_MGR: LeaseBuf = LeaseBuf(std::cell::UnsafeCell::new([0u8; 16]));

struct M4pEntry {
    sp: SharedPtr,
}
unsafe impl Send for M4pEntry {}
static M4P_CTXS: Mutex<Option<std::collections::HashMap<usize, M4pEntry>>> = Mutex::new(None);
static NEXT_M4P_ID: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(1);

/// Base storefront dir + configured android id (set once at configure()).
struct ConfigState {
    base_dir: String,
    dns_servers: String,
    android_id: String,
}
static CONFIG: Mutex<Option<ConfigState>> = Mutex::new(None);

/// Sign-in credentials + 2FA handoff (the credential handler, on a StoreServices thread, reads these).
struct AuthState {
    username: String,
    password: String, // the 2FA code is appended here when needed, as the C did
    code: String,
    code_ready: bool,
    code_wanted: bool,
}
static AUTH: Mutex<AuthState> = Mutex::new(AuthState {
    username: String::new(),
    password: String::new(),
    code: String::new(),
    code_ready: false,
    code_wanted: false,
});
static CODE_CV: Condvar = Condvar::new();

/// Donde se guarda el campo 1 del GUID (los 36 hex de FairPlay).
///
/// Es el unico de los seis campos que el port no sabe calcular: sale de
/// `FairPlayGetAndroidGUIDFields`, que es un stub de maquina virtual con aplanado de
/// control de flujo y no se puede llamar fuera de su contexto (se intento: SIGSEGV). Pero
/// es **estable para el dispositivo**, asi que basta leerlo una vez de la libreria y
/// guardarlo; a partir de ahi el GUID lo construye el port entero.
///
/// Vive junto a `ANDROID_ID`, al que esta atado y que ya se guarda igual de plano.
const CACHE_CAMPO1: &str = "fairplay_f1";

/// El campo 1 guardado para `android_id`, si esta y tiene la forma correcta.
///
/// La cache guarda junto al campo el ID al que pertenece, y solo sirve para ese ID. El campo de
/// FairPlay se deriva del ID con que se configuraron las librerias: si el ID cambia (identidad
/// rotada, sesion importada de otro telefono) el campo viejo ya no corresponde, y un GUID armado
/// con el hash del ID nuevo y el campo del viejo no es el de ningun dispositivo.
fn campo1_cacheado(android_id: &str) -> Option<String> {
    let v = std::fs::read_to_string(std::path::Path::new(&base_dir()).join(CACHE_CAMPO1)).ok()?;
    let (id, campo) = v.trim().split_once(':')?;
    (id == android_id && campo.len() == device_guid::FAIRPLAY_LEN && campo.bytes().all(|b| b.is_ascii_hexdigit()))
        .then(|| campo.to_owned())
}

/// Guarda el campo 1 de `android_id` para los arranques siguientes. Un fallo no es grave: se
/// vuelve a leer de la libreria la proxima vez.
fn cachear_campo1(android_id: &str, campo1: &str) {
    let ruta = std::path::Path::new(&base_dir()).join(CACHE_CAMPO1);
    match std::fs::write(&ruta, format!("{android_id}:{campo1}")) {
        Ok(()) => nlog("guid: campo de FairPlay guardado, los proximos arranques no lo piden"),
        Err(e) => nlog(&format!("guid: no se pudo guardar el campo de FairPlay ({e})")),
    }
}

/// El GUID del dispositivo construido **enteramente por el port**, sin llamar a `DeviceGUID`.
///
/// Devuelve `None` la primera vez (no hay campo 1 guardado todavia) y cada vez que falte el
/// ANDROID_ID; el llamador cae a la libreria cerrada. Una vez que hay cache, esta es la ruta
/// normal y no se toca codigo cerrado para armar el GUID.
fn guid_del_port() -> Option<String> {
    let android_id = {
        let g = CONFIG.lock().ok()?;
        let c = g.as_ref()?;
        (!c.android_id.is_empty()).then(|| c.android_id.clone())?
    };
    let campo1 = campo1_cacheado(&android_id)?;
    Some(device_guid::build(&device_guid::DeviceInfo {
        android_id,
        serial: String::new(), // la mayoria de los Android no lo exponen
        fairplay_field1: Some(campo1),
        fairplay_field2: None, // en el dispositivo de referencia sale en ceros
    }))
}

/// Comprueba que el port `device-guid` reproduce el GUID que arma la libreria cerrada.
///
/// No llama a nada nuevo: toma el GUID que `DeviceGUID::guid()` ya devolvio, le saca el
/// campo 1 (los 36 hex de FairPlay, lo unico del GUID que no esta portado) y reconstruye
/// los otros cinco campos con el port. Si el resultado es identico, queda demostrado sobre
/// el dispositivo real que el algoritmo del port es el correcto: el hash del ANDROID_ID, el
/// ancho de los campos y el orden.
///
/// Es la comprobacion previa a dejar de llamar a `DeviceGUID`. Hacerlo asi, reusando el
/// campo 1 en vez de pedirselo a `FairPlayGetAndroidGUIDFields`, es deliberado: esa funcion
/// no se importa estaticamente en ningun lado, la resuelven por `dlsym` desde
/// `libmedialibrarycore.so`, y llamarla sin las precondiciones de su caller real mata el
/// proceso con SIGSEGV (probado). Mientras no se sepa como prepararla, el campo 1 se saca
/// de donde ya esta.
///
/// **Nunca registra el GUID**, que identifica el dispositivo: solo el veredicto.
fn verificar_guid(de_la_libreria: &str) {
    let Some(partes) = device_guid::parse(de_la_libreria) else {
        nlog("guid: la libreria devolvio algo que no tiene la forma esperada");
        return;
    };
    let android_id = {
        let Ok(g) = CONFIG.lock() else { return };
        match g.as_ref() {
            Some(c) if !c.android_id.is_empty() => c.android_id.clone(),
            _ => {
                nlog("guid: sin ANDROID_ID, no puedo reconstruirlo");
                return;
            }
        }
    };
    let campo1_leido = de_la_libreria.split('.').next().unwrap_or_default().to_owned();
    let nuestro = device_guid::build(&device_guid::DeviceInfo {
        android_id: android_id.clone(),
        serial: String::new(), // la mayoria de los Android no lo exponen
        fairplay_field1: Some(campo1_leido.clone()),
        fairplay_field2: None, // en el dispositivo de referencia sale en ceros
    });
    if nuestro == de_la_libreria {
        nlog(&format!(
            "guid: el port lo reproduce identico ({} chars, {} campos, hash del ANDROID_ID ok)",
            nuestro.len(),
            nuestro.split('.').count(),
        ));
        // Recien ahora, con la igualdad comprobada en este dispositivo, se guarda el campo 1.
        // Guardarlo antes seria confiar en que el port acierta; asi se guarda porque acerto.
        if campo1_cacheado(&android_id).as_deref() != Some(campo1_leido.as_str()) {
            cachear_campo1(&android_id, &campo1_leido);
        }
        return;
    }
    let a: Vec<&str> = nuestro.split('.').collect();
    let b: Vec<&str> = de_la_libreria.split('.').collect();
    if a.len() != b.len() {
        nlog(&format!("guid: DIFIERE en cantidad de campos ({} contra {})", a.len(), b.len()));
        return;
    }
    let distintos: Vec<String> = a
        .iter()
        .zip(b.iter())
        .enumerate()
        .filter(|(_, (x, y))| x != y)
        .map(|(i, (x, y))| format!("campo{} (largo {} contra {})", i + 1, x.len(), y.len()))
        .collect();
    nlog(&format!(
        "guid: DIFIERE en {} | coincide con el ANDROID_ID: {}",
        distintos.join(", "),
        partes.matches_android_id(&android_id),
    ));
}

/// El `(dt:NN)` del User-Agent. No sale de la config: lo agrega la capa de red, y en el
/// trafico capturado del dispositivo es 66.
const MINT_DT: u32 = 66;

/// El release de Android que corre de verdad (`ro.build.version.release`).
///
/// Antes iba `"10"` a mano en `init_ctx`, asi que la app declaraba Android 10 corriera
/// donde corriera. El valor sale de la propiedad del sistema; si no se puede leer se cae
/// al `10` historico para no cambiar el comportamiento observado.
fn android_release() -> String {
    const FALLBACK: &str = "10";
    let mut buf = [0u8; 92]; // PROP_VALUE_MAX de bionic
    let n = unsafe {
        libc::__system_property_get(
            c"ro.build.version.release".as_ptr(),
            buf.as_mut_ptr() as *mut c_char,
        )
    };
    if n <= 0 {
        nlog("init: sin ro.build.version.release, uso el valor por defecto");
        return FALLBACK.to_owned();
    }
    let v = String::from_utf8_lossy(&buf[..n as usize]).trim().to_owned();
    if v.is_empty() { FALLBACK.to_owned() } else { v }
}

/// La identidad que la app declara a la tienda, en un solo lugar.
///
/// Es el port `store-config` (`storeservicescore::RequestContextConfig`). Importa que sea
/// una sola instancia: estos nueve campos alimentan tanto los setters de
/// `RequestContextConfig` en `init_ctx` como el `User-Agent` del mint, y antes se
/// escribian por separado **y no coincidian** -- la config decia `5.0.2 / Pixel 8 /
/// 7663314` y la cabecera decia `Music/4.9 ... Samsung S9 ... 7663313`. Dos identidades
/// distintas en la misma sesion son una huella trivial de detectar.
fn store_cfg() -> &'static store_config::RequestContextConfig {
    static CFG: OnceLock<store_config::RequestContextConfig> = OnceLock::new();
    CFG.get_or_init(|| {
        store_config::RequestContextConfig::for_android(
            format!("{}/mpl_db", base_dir()),
            &android_release(),
        )
    })
}

fn base_dir() -> String {
    CONFIG.lock().unwrap().as_ref().map(|c| c.base_dir.clone()).unwrap_or_default()
}

// ---- small helpers ------------------------------------------------------------------------------

/// Address of an emplace vtable's first virtual slot (`&_ZTV... + 2` in the C: skip offset-to-top +
/// typeinfo). The object's control block stores this as its vptr.
unsafe fn vptr(sym: *const usize) -> *mut c_void {
    sym.add(2) as *mut c_void
}

/// Lay a `__shared_ptr_emplace<T>` into `buf`: vptr at [0], shared/weak owner counts at [8]/[16]
/// (0 == "one owner", as libc++ uses), the T at `obj_off`. Returns the owning shared_ptr.
unsafe fn emplace(buf: *mut u8, vtable: *const usize, obj_off: usize) -> SharedPtr {
    *(buf as *mut *mut c_void) = vptr(vtable);
    *(buf.add(8) as *mut usize) = 0;
    *(buf.add(16) as *mut usize) = 0;
    SharedPtr { obj: buf.add(obj_off) as *mut c_void, ctrl: buf as *mut c_void }
}

/// A short-mode libc++ std::string holding `s` inline (mark = len<<1), matching
/// `new_std_string_short_mode`. For the "HLS" asset param. Max 22 bytes of payload.
#[repr(C)]
struct ShortStr {
    bytes: [u8; 24],
}
impl ShortStr {
    fn new(s: &str) -> ShortStr {
        let mut bytes = [0u8; 24];
        let b = s.as_bytes();
        let n = b.len().min(22);
        bytes[0] = (n as u8) << 1;
        bytes[1..1 + n].copy_from_slice(&b[..n]);
        ShortStr { bytes }
    }
    fn as_stdstring_ptr(&self) -> *const StdString {
        self.bytes.as_ptr() as *const StdString
    }
}

// ---- configure + init ---------------------------------------------------------------------------

/// FootHill init: DNS (active-network resolvers), FootHillConfig, DeviceGUID configure. Mirrors
/// native_core.c's core_fh_configure + main_native.c's init().
pub fn configure(base_dir_in: &str, dns_servers: &str, android_id_in: &str) {
    {
        let mut cfg = CONFIG.lock().unwrap();
        *cfg = Some(ConfigState {
            base_dir: if base_dir_in.is_empty() { "/data/data/app.cadence/files".to_owned() } else { base_dir_in.to_owned() },
            dns_servers: dns_servers.to_owned(),
            android_id: android_id_in.to_owned(), // always set: see identity::resolve
        });
    }
    let (base, dns, aid) = {
        let g = CONFIG.lock().unwrap();
        let c = g.as_ref().unwrap();
        (c.base_dir.clone(), c.dns_servers.clone(), c.android_id.clone())
    };
    let dns_count = if dns.is_empty() { 0 } else { dns.split(',').count() };
    nlog(&format!("init: dns_count={dns_count}"));
    nlog(&format!("init: start base={base}"));
    nlog(&format!("init: android_id=<redacted len={}>", aid.len()));

    unsafe {
        let mode = CString::new("ANDROID_DNS_MODE").unwrap();
        let local = CString::new("local").unwrap();
        setenv(mode.as_ptr(), local.as_ptr(), 1);

        // Resolve through the device's active-network DNS (two servers), defaulting to public ones.
        let (s0, s1): (CString, CString) = if dns.is_empty() {
            (CString::new("1.1.1.1").unwrap(), CString::new("1.0.0.1").unwrap())
        } else {
            let mut it = dns.splitn(2, ',');
            let a = it.next().unwrap_or("1.1.1.1").trim();
            let b = it.next().unwrap_or(a).trim();
            (CString::new(a).unwrap_or_default(), CString::new(b).unwrap_or_default())
        };
        let servers = [s0.as_ptr(), s1.as_ptr()];
        let dot = CString::new(".").unwrap();
        _resolv_set_nameservers_for_net(0, servers.as_ptr(), 2, dot.as_ptr(), ptr::null());

        // FootHillConfig::config(android_id); DeviceGUID::configure(android_id, "", 29, true).
        let aid_c = CString::new(aid.as_str()).unwrap_or_default();
        let empty_c = CString::new("").unwrap();
        let conf1 = StdString::borrow_cstr(&aid_c);
        let conf2 = StdString::borrow_cstr(&empty_c);
        foothill_config(&conf1);

        let guid = bridges::cadence_device_guid_get();
        let conf3: u32 = 29;
        let conf4: bool = true;
        if !guid.is_null() {
            let guid_obj = (*(guid as *mut SharedPtr)).obj;
            bridges::cadence_device_guid_configure(
                guid_obj,
                &conf1 as *const StdString as *mut c_void,
                &conf2 as *const StdString as *mut c_void,
                &conf3 as *const u32 as *mut c_void,
                &conf4 as *const bool as *mut c_void,
            );
            // Ya configurado, se puede leer el GUID y contrastarlo con el del port. Va aca y
            // no solo en el mint porque el mint no corre en cada arranque: si la sesion sigue
            // valida se lo saltea, y el contraste tardaria dias en aparecer.
            let mut buf = [0u8; 128];
            if bridges::cadence_device_guid_string(
                guid_obj,
                buf.as_mut_ptr() as *mut c_char,
                buf.len() as c_int,
            ) != 0
            {
                let de_la_libreria =
                    CStr::from_ptr(buf.as_ptr() as *const c_char).to_string_lossy().into_owned();
                verificar_guid(&de_la_libreria);
            }
        }
    }
    nlog("init: FootHill done");
}

/// Build + configure the RequestContext, install the presentation interface and sign-in handlers.
/// Mirrors main_native.c's init_ctx(). Returns the heap shared_ptr<RequestContext>* (cached globally).
pub fn init_ctx() -> *mut c_void {
    nlog("init: ctx start");
    let base = base_dir();
    let mpl_db = format!("{base}/mpl_db");
    let mpl_db_c = CString::new(mpl_db).unwrap_or_default();
    let path = StdString::borrow_cstr(&mpl_db_c);

    unsafe {
        let reqctx = bridges::cadence_make_request_ctx(&path as *const StdString as *mut c_void);
        if reqctx.is_null() {
            return ptr::null_mut();
        }
        REQCTX.store(reqctx, Ordering::Release);
        let reqctx_sp = reqctx as *mut SharedPtr;
        let reqctx_obj = (*reqctx_sp).obj;

        // RequestContextConfig via in-place emplace (leaked calloc buffer; init_ctx runs once).
        let cfg_buf = libc_calloc(480);
        let cfg = emplace(cfg_buf, ptr::addr_of!(_ZTVNSt6__ndk120__shared_ptr_emplaceIN17storeservicescore20RequestContextConfigENS_9allocatorIS2_EEEE), 32);
        reqcfg_ctor(cfg.obj);

        reqcfg_set_base_dir(cfg.obj, &path as *const StdString);
        let set = |f: unsafe fn(*mut c_void, *const StdString), v: &str| {
            let c = CString::new(v).unwrap_or_default();
            let s = StdString::borrow_cstr(&c);
            f(cfg.obj, &s as *const StdString);
        };
        // Los nueve campos salen de store_cfg(): el port de RequestContextConfig. Cada
        // campo es el argumento de su setter homonimo, por eso se pasan tal cual.
        let scfg = store_cfg();
        set(reqcfg_set_client_id, &scfg.client_identifier);
        set(reqcfg_set_version_id, &scfg.version_identifier);
        set(reqcfg_set_platform_id, &scfg.platform_identifier);
        set(reqcfg_set_product_version, &scfg.product_version);
        set(reqcfg_set_device_model, &scfg.device_model);
        set(reqcfg_set_build_version, &scfg.build_version);
        let locale = CString::new(scfg.locale_identifier.as_str()).unwrap_or_default();
        let locale_s = StdString::borrow_cstr(&locale);
        reqcfg_set_locale_id(cfg.obj, &locale_s);
        let language = CString::new(scfg.language_identifier.as_str()).unwrap_or_default();
        let language_s = StdString::borrow_cstr(&language);
        reqcfg_set_language_id(cfg.obj, &language_s);

        reqctx_manager_configure(reqctx_sp);
        bridges::cadence_request_ctx_init(reqctx_obj, &cfg as *const SharedPtr as *mut c_void);

        let base_c = CString::new(base.as_str()).unwrap_or_default();
        let base_s = StdString::borrow_cstr(&base_c);
        reqctx_set_fairplay_dir(reqctx_obj, &base_s);

        // Presentation interface + sign-in handlers.
        let apinf = bridges::cadence_make_presentation();
        if apinf.is_null() {
            nlog("init: ctx done");
            return reqctx;
        }
        AP_INF.store(apinf, Ordering::Release);
        let apinf_sp = apinf as *mut SharedPtr;
        let apinf_obj = (*apinf_sp).obj;
        ap_set_dialog_handler(apinf_obj, dialog_handler);
        ap_set_credentials_handler(apinf_obj, credential_handler);
        reqctx_set_presentation(reqctx_obj, apinf_sp);
    }
    nlog("init: ctx done");
    REQCTX.load(Ordering::Acquire)
}

/// Resolve + cache the FootHill session singleton (the key path reads it).
pub fn instance() -> *mut c_void {
    nlog("init: instance start");
    let inst = unsafe { fhsession_instance() };
    FH_INSTANCE.store(inst, Ordering::Release);
    nlog(&format!("init: instance={inst:?}"));
    inst
}

// ---- sign-in handlers (run on a StoreServices thread during login) ------------------------------

extern "C" fn dialog_handler(j: c_long, proto_dialog: *mut SharedPtr, _resp_handler: *mut SharedPtr) {
    // Runs on an Apple-created thread. The Apple calls made here are ours to guard; a throw that
    // starts above this frame cannot be caught. A throw skips the response (the flow then times out).
    let _scope = cxx_guard::Scope::named("dialog_handler", "");
    unsafe {
        let apinf_sp = AP_INF.load(Ordering::Acquire);
        if apinf_sp.is_null() || proto_dialog.is_null() {
            return;
        }
        let apinf = (*(apinf_sp as *mut SharedPtr)).obj; // the AndroidPresentationInterface object
        if apinf.is_null() {
            return;
        }
        let dialog_obj = (*proto_dialog).obj;
        let title_ptr = dialog_title(dialog_obj);
        if title_ptr.is_null() {
            return;
        }
        let title = cstr_of(sys::StdString::data(title_ptr));

        // ProtocolDialogResponse in heap (libc::calloc so retaining the shared_ptr past return cannot UAF stack).
        let buf = libc::calloc(1, 72) as *mut u8;
        if buf.is_null() {
            return;
        }
        let resp = emplace(buf, ptr::addr_of!(_ZTVNSt6__ndk120__shared_ptr_emplaceIN17storeservicescore22ProtocolDialogResponseENS_9allocatorIS2_EEEE), 24);
        dialog_response_ctor(resp.obj);

        if title == "Sign In" {
            let buttons = dialog_buttons(dialog_obj);
            if !buttons.is_null() {
                let mut b = (*buttons).begin as *mut SharedPtr;
                let end = (*buttons).end as *mut SharedPtr;
                while b != end {
                    let bt_ptr = button_title((*b).obj);
                    if bt_ptr.is_null() {
                        break;
                    }
                    let bt = cstr_of(sys::StdString::data(bt_ptr));
                    if bt == "Use Existing Apple ID" {
                        dialog_response_set_button(resp.obj, b);
                        break;
                    }
                    b = b.add(1);
                }
            }
        }
        let mut resp = resp;
        ap_handle_dialog_response(apinf, &j as *const c_long, &mut resp as *mut SharedPtr);
    }
}

extern "C" fn credential_handler(cred_req: *mut SharedPtr, _cred_resp_handler: *mut SharedPtr) {
    let _scope = cxx_guard::Scope::named("credential_handler", ""); // see dialog_handler
    unsafe {
        let apinf_sp = AP_INF.load(Ordering::Acquire);
        if apinf_sp.is_null() || cred_req.is_null() {
            return;
        }
        let apinf = (*(apinf_sp as *mut SharedPtr)).obj; // the AndroidPresentationInterface object
        if apinf.is_null() {
            return;
        }
        let need_2fa = cred_req_requires_2fa((*cred_req).obj) != 0;

        // Snapshot credentials; block for a 2FA code if required (woken by submit_code).
        let (username, password) = {
            let mut st = AUTH.lock().unwrap();
            if need_2fa {
                st.code_wanted = true;
                while !st.code_ready {
                    st = CODE_CV.wait(st).unwrap();
                }
                let code = st.code.clone();
                let n: String = code.chars().take(6).collect();
                st.password.push_str(&n);
                st.code_ready = false;
                st.code_wanted = false;
            }
            (st.username.clone(), st.password.clone())
        };

        // CredentialsResponse in place (leaked heap buffer, like the C's malloc(80) that is never freed).
        let buf = libc_calloc(80);
        let resp = emplace(buf, ptr::addr_of!(_ZTVNSt6__ndk120__shared_ptr_emplaceIN17storeservicescore19CredentialsResponseENS_9allocatorIS2_EEEE), 24);
        cred_response_ctor(resp.obj);

        let user_c = CString::new(username).unwrap_or_default();
        let pass_c = CString::new(password).unwrap_or_default();
        let user_s = StdString::borrow_cstr(&user_c);
        let pass_s = StdString::borrow_cstr(&pass_c);
        cred_response_set_user(resp.obj, &user_s);
        cred_response_set_pass(resp.obj, &pass_s);
        cred_response_set_type(resp.obj, 2);

        let mut resp = resp;
        ap_handle_credentials_response(apinf, &mut resp as *mut SharedPtr);
    }
}

// ---- credentials / 2FA --------------------------------------------------------------------------

pub fn set_credentials(user: &str, pass: &str) {
    let mut st = AUTH.lock().unwrap();
    st.username = user.to_owned();
    st.password = pass.to_owned();
    st.code.clear();
    st.code_ready = false;
    st.code_wanted = false;
    nlog(&format!("auth: credentials set user=<redacted len={}>", user.len()));
}

pub fn submit_code(code: &str) {
    let mut st = AUTH.lock().unwrap();
    st.code = code.to_owned();
    st.code_ready = true;
    CODE_CV.notify_all();
    drop(st);
    nlog("auth: 2FA code submitted");
}

pub fn awaiting_code() -> bool {
    AUTH.lock().unwrap().code_wanted
}

// ---- lease / m3u8 / kd --------------------------------------------------------------------------

pub fn ensure_lease() {
    nlog("lease: start");
    unsafe {
        let mgr = LEASE_MGR.0.get() as *mut c_void;
        lease_mgr_ctor(mgr, crate::cppfn::end_lease_ptr(), crate::cppfn::pb_err_ptr());
        let autom: u8 = 1;
        lease_mgr_refresh_auto(mgr, &autom);
        lease_mgr_request_lease(mgr, &autom);
    }
    nlog("lease: ready");
}

pub fn m3u8(adam: c_ulong) -> Option<String> {
    nlog(&format!("m3u8: adam={adam}"));
    unsafe {
        let mgr = LEASE_MGR.0.get() as *mut c_void;
        let hls = ShortStr::new("HLS");
        // vector<string> of one element: end/end_cap sit 1 StdString (24 bytes) past begin.
        let elem = hls.as_stdstring_ptr() as *mut c_void;
        let elem_end = (elem as *mut u8).add(std::mem::size_of::<StdString>()) as *mut c_void;
        let params = StdVector {
            begin: elem,
            end: elem_end,
            end_cap: elem_end,
        };
        let z0: u8 = 0;
        let mut result = SharedPtr::NULL;
        let req_fn = lease_mgr_request_asset_raw as unsafe extern "C" fn();
        asm_call("requestAsset", req_fn, (&mut result as *mut SharedPtr) as usize, [(mgr) as usize, (&adam as *const c_ulong) as usize, (&params as *const StdVector) as usize, (&z0 as *const u8) as usize, 0, 0, 0, 0]);
        if result.obj.is_null() || asset_resp_has_valid(result.obj) == 0 {
            nlog("m3u8: url=(null)");
            return None;
        }
        let asset = asset_resp_playback_asset(result.obj);
        if asset.is_null() || (*asset).obj.is_null() {
            return None;
        }
        // URLString writes a std::string into our 24-byte slot (ARM64 sret in x8); read + copy it.
        let mut urlstr = [0u8; 24];
        let url_fn = playback_asset_url_raw as unsafe extern "C" fn();
        asm_call("playbackAsset.URLString", url_fn, (urlstr.as_mut_ptr() as *mut StdString) as usize, [((*asset).obj) as usize, 0, 0, 0, 0, 0, 0, 0]);
        let data = sys::StdString::data(urlstr.as_ptr() as *const StdString);
        if data.is_null() {
            return None;
        }
        let s = CStr::from_ptr(data).to_string_lossy().into_owned();
        let safe_url = s.split('?').next().unwrap_or(&s);
        nlog(&format!("m3u8: url={safe_url} (query redacted)"));
        Some(s)
    }
}

/// Request a progressive `.m4p` playback asset and build an `SVSampleDecryptor` context from its
/// `FootHillSF` sinf/dpInfo blobs. Returns `(url, boxed_shared_ptr_ctx)` on success.
pub fn m4p_asset(adam_id: c_ulong) -> Option<(String, *mut c_void)> {
    ensure_lease();
    unsafe {
        let mgr = LEASE_MGR.0.get() as *mut c_void;
        let z0: u8 = 0;
        let params = StdVector {
            begin: ptr::null_mut(),
            end: ptr::null_mut(),
            end_cap: ptr::null_mut(),
        };
        let mut result = SharedPtr::NULL;
        let req_fn = lease_mgr_request_asset_raw as unsafe extern "C" fn();
        asm_call("requestAsset", req_fn, (&mut result as *mut SharedPtr) as usize, [(mgr) as usize, (&adam_id as *const c_ulong) as usize, (&params as *const StdVector) as usize, (&z0 as *const u8) as usize, 0, 0, 0, 0]);
        if result.obj.is_null() || asset_resp_has_valid(result.obj) == 0 {
            nlog("m4p_asset: asset response invalid or null");
            if !result.ctrl.is_null() {
                cpp_release_shared(result.ctrl);
            }
            return None;
        }
        let asset = asset_resp_playback_asset(result.obj);
        if asset.is_null() || (*asset).obj.is_null() {
            nlog("m4p_asset: playback asset null");
            if !result.ctrl.is_null() {
                cpp_release_shared(result.ctrl);
            }
            return None;
        }

        let mut urlstr = [0u8; 24];
        let url_fn = playback_asset_url_raw as unsafe extern "C" fn();
        asm_call("playbackAsset.URLString", url_fn, (urlstr.as_mut_ptr() as *mut StdString) as usize, [((*asset).obj) as usize, 0, 0, 0, 0, 0, 0, 0]);
        let url_ptr = sys::StdString::data(urlstr.as_ptr() as *const StdString);
        let url = if url_ptr.is_null() {
            String::new()
        } else {
            CStr::from_ptr(url_ptr).to_string_lossy().into_owned()
        };
        if url.is_empty() {
            nlog("m4p_asset: empty url");
            if !result.ctrl.is_null() {
                cpp_release_shared(result.ctrl);
            }
            return None;
        }

        let mut fh_buf = [0u8; 24];
        foothill_sf_ctor(fh_buf.as_mut_ptr() as *mut c_void, asset);

        let mut sf_data = SharedPtr::NULL;
        let get_sf_fn = foothill_sf_get_sf_data_raw as unsafe extern "C" fn();
        asm_call("FootHillSF.getSFData", get_sf_fn, (&mut sf_data as *mut SharedPtr) as usize, [(fh_buf.as_ptr()) as usize, 0, 0, 0, 0, 0, 0, 0]);

        let mut sf2_data = SharedPtr::NULL;
        let get_sf2_fn = foothill_sf_get_sf2_data_raw as unsafe extern "C" fn();
        asm_call("FootHillSF.getSF2Data", get_sf2_fn, (&mut sf2_data as *mut SharedPtr) as usize, [(fh_buf.as_ptr()) as usize, 0, 0, 0, 0, 0, 0, 0]);

        let mut dp_info = SharedPtr::NULL;
        let get_dp_fn = foothill_sf_get_dp_info_raw as unsafe extern "C" fn();
        asm_call("FootHillSF.getDpInfo", get_dp_fn, (&mut dp_info as *mut SharedPtr) as usize, [(fh_buf.as_ptr()) as usize, 0, 0, 0, 0, 0, 0, 0]);

        let fh_vec = &*(fh_buf.as_ptr() as *const StdVector);
        let sinfs_count = if fh_vec.end >= fh_vec.begin {
            (fh_vec.end as usize - fh_vec.begin as usize) / std::mem::size_of::<SharedPtr>()
        } else {
            0
        };
        let sf2_ptr = if sf2_data.obj.is_null() {
            ptr::null()
        } else {
            data_bytes(sf2_data.obj) as *const u8
        };
        let sf2_len = if sf2_data.obj.is_null() {
            0u32
        } else {
            data_length(sf2_data.obj) as u32
        };
        nlog(&format!("m4p_asset: sinfs_count={sinfs_count} sf2_len={sf2_len}"));

        let sinf_ptr = if sf_data.obj.is_null() {
            ptr::null()
        } else {
            data_bytes(sf_data.obj) as *const u8
        };
        let sinf_len = if sf_data.obj.is_null() {
            0u32
        } else {
            data_length(sf_data.obj) as u32
        };
        let dp_ptr = if dp_info.obj.is_null() {
            ptr::null()
        } else {
            data_bytes(dp_info.obj) as *const u8
        };
        let dp_len = if dp_info.obj.is_null() {
            0u32
        } else {
            data_length(dp_info.obj) as u32
        };
        let (aux_ptr, aux_len) = if !dp_ptr.is_null() && dp_len > 0 {
            (dp_ptr, dp_len)
        } else {
            (sf2_ptr, sf2_len)
        };

        let mut decryptor = SharedPtr::NULL;
        if !sinf_ptr.is_null() && sinf_len > 0 {
            let dec_type: u32 = if !aux_ptr.is_null() && aux_len > 0 { 3 } else { 2 };
            let track_type: u32 = 0; // audio
            let b1: u8 = 0;
            let b2: u8 = 0;
            let create_fn = sv_decryptor_factory_create_raw as unsafe extern "C" fn();
            asm_call("SVDecryptorFactory.create", create_fn, (&mut decryptor as *mut SharedPtr) as usize, [(&dec_type as *const u32) as usize, (sinf_ptr) as usize, (&sinf_len as *const u32) as usize, (aux_ptr) as usize, (&aux_len as *const u32) as usize, (&track_type as *const u32) as usize, (&b1 as *const u8) as usize, (&b2 as *const u8) as usize]);
        }

        if !sf2_data.ctrl.is_null() {
            cpp_release_shared(sf2_data.ctrl);
        }

        if !sf_data.ctrl.is_null() {
            cpp_release_shared(sf_data.ctrl);
        }
        if !dp_info.ctrl.is_null() {
            cpp_release_shared(dp_info.ctrl);
        }
        if !result.ctrl.is_null() {
            cpp_release_shared(result.ctrl);
        }

        if decryptor.obj.is_null() {
            nlog(&format!("m4p_asset: SVDecryptorFactory::create returned null (sinf_len={sinf_len}, dp_len={dp_len})"));
            if !decryptor.ctrl.is_null() {
                cpp_release_shared(decryptor.ctrl);
            }
            return None;
        }

        let id = NEXT_M4P_ID.fetch_add(1, Ordering::Relaxed);
        {
            let mut guard = M4P_CTXS.lock().unwrap_or_else(|e| e.into_inner());
            guard.get_or_insert_with(Default::default).insert(id, M4pEntry { sp: decryptor });
        }
        let ctx = id as *mut c_void;
        let safe_url = url.split('?').next().unwrap_or(&url);
        nlog(&format!("m4p_asset: url={safe_url} sinf_len={sinf_len} dp_len={dp_len} ctx_id={id}"));
        Some((url, ctx))
    }
}

/// Decrypt a single `.m4p` audio sample in place using an `SVSampleDecryptor` context.
pub unsafe fn decrypt_m4p_sample(ctx: *mut c_void, sample: *mut u8, size: usize) -> c_int {
    if ctx.is_null() || sample.is_null() || size == 0 || size > u32::MAX as usize {
        return -1;
    }
    let id = ctx as usize;
    let obj = {
        let guard = M4P_CTXS.lock().unwrap_or_else(|e| e.into_inner());
        guard.as_ref().and_then(|m| m.get(&id)).map(|e| e.sp.obj)
    };
    let Some(obj) = obj else {
        nlog(&format!("decrypt_m4p_sample: unknown ctx_id={id}"));
        return -2;
    };
    if obj.is_null() {
        return -2;
    }
    let state = *((obj as *const *mut c_void).add(3));
    if state.is_null() {
        nlog("decrypt_m4p_sample: state at +0x18 is null");
        return -3;
    }
    let scope = cxx_guard::Scope::named("decrypt_m4p_sample", "");
    let rc = YHrWZQ6wU(state, sample, size as u32, sample, 0);
    if scope.threw() {
        nlog("decrypt_m4p_sample: Apple decrypt threw a C++ exception (sample skipped)");
        return -4;
    }
    if rc != 0 {
        nlog(&format!("decrypt_m4p_sample: YHrWZQ6wU rc={rc} size={size} state={state:?}"));
    }
    rc
}

/// Release an `SVDecryptor` context created by [`m4p_asset`].
pub unsafe fn release_m4p_context(ctx: *mut c_void) {
    if ctx.is_null() {
        return;
    }
    let id = ctx as usize;
    let removed = {
        let mut guard = M4P_CTXS.lock().unwrap_or_else(|e| e.into_inner());
        guard.as_mut().and_then(|m| m.remove(&id))
    };
    if let Some(entry) = removed {
        if !entry.sp.ctrl.is_null() {
            cpp_release_shared(entry.sp.ctrl);
        }
    }
}

/// Fetch the FairPlay decrypt (kd) context for an adam id + key URI. The Apple key path can in
/// principle throw a C++ exception, which Rust cannot catch; in practice it signals errors by
/// returning null shared_ptrs, which `get_kd_context` checks at every step and turns into a null
/// result (a skipped track) -- the same graceful outcome the old C++ try/catch produced for the error
/// path we have observed.
pub fn kd(adam_id: &str, key_uri: &str) -> *mut c_void {
    nlog(&format!("kd: adam={adam_id}"));
    let adam = CString::new(adam_id).unwrap_or_default();
    let uri = CString::new(key_uri).unwrap_or_default();
    let kd = unsafe { get_kd_context(adam.as_ptr(), uri.as_ptr()) };
    nlog(&format!("kd: result={kd:?}"));
    kd
}

unsafe fn get_kd_context(adam: *const c_char, uri: *const c_char) -> *mut c_void {
    let adam_cstr = CStr::from_ptr(adam);
    let is_preshare = adam_cstr.to_bytes() == b"0";
    if is_preshare {
        let cached = PRESHARE_CTX.load(Ordering::Acquire);
        if !cached.is_null() {
            return cached;
        }
    }
    let fh = FH_INSTANCE.load(Ordering::Acquire);
    let uri_cstr = CStr::from_ptr(uri);
    // Constantes del protocolo desde el port `fairplay-proto`, que las valida contra la
    // captura del dispositivo (vectors.json) en vez de repetirlas a mano aca.
    let key_format = CString::new(fairplay_proto::KEY_FORMAT).unwrap();
    let key_format_ver = CString::new(fairplay_proto::KEY_FORMAT_VERSION).unwrap();
    let server_uri = CString::new(fairplay_proto::KEY_SERVER_URL).unwrap();
    let protocol_type = CString::new(fairplay_proto::PROTOCOL_SIMPLIFIED).unwrap();
    let fps_cert = CString::new(FAIRPLAY_CERT).unwrap();

    let default_id = StdString::borrow_cstr(adam_cstr);
    let key_uri = StdString::borrow_cstr(uri_cstr);
    let kf = StdString::borrow_cstr(&key_format);
    let kfv = StdString::borrow_cstr(&key_format_ver);
    let su = StdString::borrow_cstr(&server_uri);
    let pt = StdString::borrow_cstr(&protocol_type);
    let cert = StdString::borrow_cstr(&fps_cert);

    let persist = bridges::cadence_get_persistent_key(
        fh,
        &default_id as *const StdString as *mut c_void,
        &default_id as *const StdString as *mut c_void,
        &key_uri as *const StdString as *mut c_void,
        &kf as *const StdString as *mut c_void,
        &kfv as *const StdString as *mut c_void,
        &su as *const StdString as *mut c_void,
        &pt as *const StdString as *mut c_void,
        &cert as *const StdString as *mut c_void,
    );
    if persist.is_null() || (*(persist as *mut SharedPtr)).obj.is_null() {
        return ptr::null_mut();
    }
    let persist_obj = (*(persist as *mut SharedPtr)).obj;

    let pctx = bridges::cadence_decrypt_context(fh, persist_obj);
    if pctx.is_null() || (*(pctx as *mut SharedPtr)).obj.is_null() {
        return ptr::null_mut();
    }
    let pctx_obj = (*(pctx as *mut SharedPtr)).obj;

    let kd_slot = pcontext_kd_context(pctx_obj);
    if kd_slot.is_null() {
        return ptr::null_mut();
    }
    let kd = *kd_slot;
    if !kd.is_null() && is_preshare {
        PRESHARE_CTX.store(kd, Ordering::Release);
    }
    kd
}

// ---- login (AuthenticateFlow) -------------------------------------------------------------------

pub fn login() -> bool {
    let reqctx = REQCTX.load(Ordering::Acquire);
    if reqctx.is_null() {
        nlog("auth: no request context (init_ctx not run?)");
        return false;
    }
    nlog("auth: AuthenticateFlow start");
    unsafe {
        let flow = bridges::cadence_make_authenticate_flow(reqctx);
        if flow.is_null() || (*(flow as *mut SharedPtr)).obj.is_null() {
            nlog("auth: could not create AuthenticateFlow");
            return false;
        }
        let flow_obj = (*(flow as *mut SharedPtr)).obj;
        auth_flow_run(flow_obj);
        let resp = auth_flow_response(flow_obj);
        if resp.is_null() || (*resp).obj.is_null() {
            nlog("auth: AuthenticateFlow returned no response");
            return false;
        }
        let rtype = auth_response_type((*resp).obj);
        nlog(&format!("auth: AuthenticateFlow responseType={rtype} (6=signed in)"));
        nlog(&format!("auth: identity {}", crate::identity::adi_state(&base_dir())));
        if rtype != 6 {
            return false;
        }
    }
    // Signed in -- mint the token on this same thread and persist it for finishNative.
    if let Some(tok) = mint_music_token() {
        let path = format!("{}/MUSIC_TOKEN", base_dir());
        if let Ok(mut f) = std::fs::File::create(&path) {
            let _ = f.write_all(tok.as_bytes());
            nlog(&format!("auth: wrote MUSIC_TOKEN ({} chars)", tok.len()));
        }
    }
    true
}

// ---- media-user-token mint (createMusicToken) ---------------------------------------------------

/// Build a `shared_ptr<HTTPMessage>` in place (manual emplace), constructed with url + method.
unsafe fn mint_http_message(url: &str, method: &str) -> SharedPtr {
    let buf = libc_calloc(512);
    // HTTPMessage emplace sets owner counts to 1 (two references), as the C did.
    *(buf as *mut *mut c_void) = vptr(ptr::addr_of!(_ZTVNSt6__ndk120__shared_ptr_emplaceIN13mediaplatform11HTTPMessageENS_9allocatorIS2_EEEE));
    *(buf.add(8) as *mut usize) = 1;
    *(buf.add(16) as *mut usize) = 1;
    let m = SharedPtr { obj: buf.add(32) as *mut c_void, ctrl: buf as *mut c_void };
    let url_c = CString::new(url).unwrap_or_default();
    let method_c = CString::new(method).unwrap_or_default();
    let u = StdString::borrow_cstr(&url_c);
    let me = StdString::borrow_cstr(&method_c);
    http_message_ctor(m.obj, &u, &me);
    m
}

/// Why a request failed, from its `StoreErrorCondition`, for the log (None when it carries no error).
unsafe fn describe_error(err: *mut SharedPtr) -> Option<String> {
    if err.is_null() || (*err).obj.is_null() {
        return None;
    }
    let e = (*err).obj;
    let what = cstr_of(store_error_what(e));
    let code = store_error_code(e);
    let external = store_error_external(e);
    Some(format!(
        "code={} external={} what={}",
        if code.is_null() { 0 } else { *code },
        if external.is_null() { 0 } else { *external },
        what,
    ))
}

/// Read a run URLRequest's response body (points into the response; copy before the request dies).
/// Every way it can come back empty is logged under [step], so a failed mint says where and why.
unsafe fn mint_response_body(url_req: *mut c_void, step: &str) -> Option<String> {
    let err = url_request_error(url_req);
    if let Some(why) = describe_error(err) {
        nlog(&format!("mint: {step} request failed: {why}"));
        return None;
    }
    let url_resp = url_request_response(url_req);
    if url_resp.is_null() || (*url_resp).obj.is_null() {
        nlog(&format!("mint: {step} finished without a response"));
        return None;
    }
    let resp = url_response_underlying((*url_resp).obj);
    if resp.is_null() || (*resp).obj.is_null() {
        nlog(&format!("mint: {step} response has no underlying HTTP response"));
        return None;
    }
    // The underlying response's Data* lives at offset 48 (matching native_core.c).
    let data = *(((*resp).obj as *const u8).add(48) as *const *mut c_void);
    if data.is_null() {
        nlog(&format!("mint: {step} response has no body"));
        return None;
    }
    let bytes = data_bytes(data);
    if bytes.is_null() {
        // No accessor for the HTTP status is exported, but it is stored in the response object as a small
        // integer: list its first words (pointers are large, a status such as 403 stands out).
        let words = std::slice::from_raw_parts((*resp).obj as *const u64, 6);
        let dump: Vec<String> = words.iter().map(|w| format!("{w:x}")).collect();
        nlog(&format!("mint: {step} response body is empty; response words: {}", dump.join(" ")));
        return None;
    }
    Some(CStr::from_ptr(bytes).to_string_lossy().into_owned())
}

/// Extract `"key":"value"` out of a JSON body.
fn json_str(body: &str, key: &str) -> Option<String> {
    let pat = format!("\"{key}\":\"");
    let start = body.find(&pat)? + pat.len();
    let rest = &body[start..];
    let end = rest.find('"')?;
    Some(rest[..end].to_owned())
}

pub fn mint_music_token() -> Option<String> {
    let reqctx = REQCTX.load(Ordering::Acquire);
    nlog(&format!("mint: start reqctx={reqctx:?}"));
    if reqctx.is_null() {
        return None;
    }
    unsafe {
        let reqctx_sp = reqctx as *mut SharedPtr;

        // Device GUID string.
        // Con el campo de FairPlay ya guardado, el GUID lo arma el port y no se llama a
        // `DeviceGUID` en absoluto. Sin cache (primer arranque) se cae a la libreria, que es
        // de donde sale el campo que despues se guarda.
        let guid = match guid_del_port() {
            Some(g) => {
                nlog("mint: guid armado por el port (sin DeviceGUID)");
                g
            }
            None => {
                let guid = bridges::cadence_device_guid_get();
                if guid.is_null() || (*(guid as *mut SharedPtr)).obj.is_null() {
                    nlog("mint: no device guid");
                    return None;
                }
                let mut guid_buf = [0u8; 128];
                if bridges::cadence_device_guid_string((*(guid as *mut SharedPtr)).obj, guid_buf.as_mut_ptr() as *mut c_char, guid_buf.len() as c_int) == 0 {
                    nlog("mint: could not read device guid string");
                    return None;
                }
                nlog("mint: guid leido de DeviceGUID (sin cache todavia)");
                CStr::from_ptr(guid_buf.as_ptr() as *const c_char).to_string_lossy().into_owned()
            }
        };
        // El port `device-guid` conoce la forma del GUID (6 campos, el primero FairPlay de
        // 36 hex). Si la libreria cerrada devolvio algo con otra forma, el mint va a fallar
        // mas adelante con un error opaco de la tienda: mejor dejarlo dicho en el log aqui.
        match device_guid::parse(&guid) {
            Some(partes) if partes.looks_valid() => {
                if !partes.tiene_fairplay() {
                    nlog("mint: guid sin campo FairPlay (dispositivo sin aprovisionar?)");
                }
            }
            _ => nlog("mint: el guid no tiene la forma esperada"),
        }

        // 1) Commerce dev token.
        let sf_token;
        {
            let mut msg = mint_http_message("https://sf-api-token-service.itunes.apple.com/apiToken", "GET");
            if msg.obj.is_null() {
                return None;
            }
            let mut url_req = [0u8; 2048];
            url_request_ctor(url_req.as_mut_ptr() as *mut c_void, &mut msg, reqctx_sp);
            set_param(url_req.as_mut_ptr() as *mut c_void, "clientId", "musicAndroid");
            set_param(url_req.as_mut_ptr() as *mut c_void, "version", "1");
            url_request_run(url_req.as_mut_ptr() as *mut c_void);
            let body = mint_response_body(url_req.as_mut_ptr() as *mut c_void, "apiToken");
            match body.as_deref().and_then(|b| json_str(b, "token")) {
                Some(t) => sf_token = t,
                None => {
                    nlog(&format!("mint: no dev token from apiToken (body_len={})", body.as_deref().map_or(0, str::len)));
                    return None;
                }
            }
            nlog("mint: apiToken ok");
        }

        // 2) createMusicToken.
        let mut msg = mint_http_message("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/createMusicToken", "POST");
        if msg.obj.is_null() {
            return None;
        }
        let user_agent = store_cfg().user_agent(MINT_DT);
        let headers = [
            ("Content-Type", "application/json; charset=UTF-8"),
            ("Expect", ""),
            ("X-Apple-Requesting-Bundle-Id", "com.apple.android.music"),
            // Misma identidad que la de RequestContextConfig (store_cfg()), no una copia
            // escrita a mano: antes aqui decia "Music/4.9 ... Samsung S9 ... 7663313".
            ("X-Apple-Requesting-Bundle-Version", user_agent.as_str()),
        ];
        for (k, v) in headers {
            let kc = CString::new(k).unwrap_or_default();
            let vc = CString::new(v).unwrap_or_default();
            let ks = StdString::borrow_cstr(&kc);
            let vs = StdString::borrow_cstr(&vc);
            http_message_set_header(msg.obj, &ks, &vs);
        }
        let now_ms = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
        let mut post_body = format!("{{\"guid\":\"{guid}\",\"assertion\":\"{sf_token}\",\"tcc-acceptance-date\":\"{now_ms}\"}}").into_bytes();
        http_message_set_body(msg.obj, post_body.as_mut_ptr() as *mut c_char, post_body.len());

        let mut url_req2 = [0u8; 2048];
        url_request_ctor(url_req2.as_mut_ptr() as *mut c_void, &mut msg, reqctx_sp);
        url_request_run(url_req2.as_mut_ptr() as *mut c_void);
        let rbody = mint_response_body(url_req2.as_mut_ptr() as *mut c_void, "createMusicToken");
        match rbody.as_deref().and_then(|b| json_str(b, "music_token")) {
            Some(t) if !t.is_empty() => {
                nlog(&format!("mint: music_token obtained ({} chars)", t.len()));
                Some(t)
            }
            _ => {
                nlog(&format!("mint: createMusicToken response (no token, body_len={})", rbody.as_deref().map_or(0, str::len)));
                None
            }
        }
    }
}

unsafe fn set_param(req: *mut c_void, name: &str, value: &str) {
    let nc = CString::new(name).unwrap_or_default();
    let vc = CString::new(value).unwrap_or_default();
    let ns = StdString::borrow_cstr(&nc);
    let vs = StdString::borrow_cstr(&vc);
    url_request_set_param(req, &ns, &vs);
}

// ---- misc ---------------------------------------------------------------------------------------

extern "C" {
    fn calloc(n: usize, size: usize) -> *mut c_void;
    fn __android_log_write(prio: c_int, tag: *const c_char, msg: *const c_char) -> c_int;
}

unsafe fn libc_calloc(n: usize) -> *mut u8 {
    calloc(1, n) as *mut u8
}

pub(crate) fn nlog(msg: &str) {
    if let Ok(c) = CString::new(msg) {
        let tag = CString::new("cadence-native").unwrap();
        unsafe { __android_log_write(4 /*INFO*/, tag.as_ptr(), c.as_ptr()) };
    }
}

unsafe fn cstr_of(p: *const c_char) -> std::borrow::Cow<'static, str> {
    if p.is_null() {
        std::borrow::Cow::Borrowed("")
    } else {
        CStr::from_ptr(p).to_string_lossy()
    }
}

const FAIRPLAY_CERT: &str = concat!(
    "MIIEzjCCA7agAwIBAgIIAXAVjHFZDjgwDQYJKoZIhvcNAQEFBQAwfzELMAkGA1UEBhMCVVMxEz",
    "ARBgNVBAoMCkFwcGxlIEluYy4xJjAkBgNVBAsMHUFwcGxlIENlcnRpZmljYXRpb24gQXV0aG9y",
    "aXR5MTMwMQYDVQQDDCpBcHBsZSBLZXkgU2VydmljZXMgQ2VydGlmaWNhdGlvbiBBdXRob3JpdH",
    "kwHhcNMTIwNzI1MTgwMjU4WhcNMTQwNzI2MTgwMjU4WjAwMQswCQYDVQQGEwJVUzESMBAGA1UE",
    "CgwJQXBwbGUgSW5jMQ0wCwYDVQQDDARGUFMxMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQ",
    "CqZ9IbMt0J0dTKQN4cUlfeQRY9bcnbnP95HFv9A16Yayh4xQzRLAQqVSmisZtBK2/nawZcDmcs",
    "+XapBojRb+jDM4Dzk6/Ygdqo8LoA+BE1zipVyalGLj8Y86hTC9QHX8i05oWNCDIlmabjjWvFBo",
    "EOk+ezOAPg8c0SET38x5u+TwIDAQABo4ICHzCCAhswHQYDVR0OBBYEFPP6sfTWpOQ5Sguf5W3Y",
    "0oibbEc3MAwGA1UdEwEB/wQCMAAwHwYDVR0jBBgwFoAUY+RHVMuFcVlGLIOszEQxZGcDLL4wge",
    "IGA1UdIASB2jCB1zCB1AYJKoZIhvdjZAUBMIHGMIHDBggrBgEFBQcCAjCBtgyBs1JlbGlhbmNl",
    "IG9uIHRoaXMgY2VydGlmaWNhdGUgYnkgYW55IHBhcnR5IGFzc3VtZXMgYWNjZXB0YW5jZSBvZi",
    "B0aGUgdGhlbiBhcHBsaWNhYmxlIHN0YW5kYXJkIHRlcm1zIGFuZCBjb25kaXRpb25zIG9mIHVz",
    "ZSwgY2VydGlmaWNhdGUgcG9saWN5IGFuZCBjZXJ0aWZpY2F0aW9uIHByYWN0aWNlIHN0YXRlbW",
    "VudHMuMDUGA1UdHwQuMCwwKqAooCaGJGh0dHA6Ly9jcmwuYXBwbGUuY29tL2tleXNlcnZpY2Vz",
    "LmNybDAOBgNVHQ8BAf8EBAMCBSAwFAYLKoZIhvdjZAYNAQUBAf8EAgUAMBsGCyqGSIb3Y2QGDQ",
    "EGAQH/BAkBAAAAAQAAAAEwKQYLKoZIhvdjZAYNAQMBAf8EFwF+bjsY57ASVFmeehD2bdu6HLGB",
    "xeC2MEEGCyqGSIb3Y2QGDQEEAQH/BC8BHrKviHJf/Se/ibc7T0/55Bt1GePzaYBVfgF3ZiNuV9",
    "3z8P3qsawAqAXzzh9o5DANBgkqhkiG9w0BAQUFAAOCAQEAVGyCtuLYcYb/aPijBCtaemxuV0Io",
    "kXJn3EgmwYHZynaR6HZmeGRUp9p3f8EXu6XPSekKCCQi+a86hXX9RfnGEjRdvtP+jts5MDSKuU",
    "Ioaqce8cLX2dpUOZXdf3lR0IQM0kXHb5boNGBsmbTLVifqeMsexfZryGw2hE/4WDOJdGQm1gMJ",
    "ZU4jP1b/HSLNIUhHWAaMeWtcJTPRBucR4urAtvvtOWD88mriZNHG+veYw55b+qA36PSqDPMbku",
    "9xTY7fsMa6mxIRmwULQgi8nOk1wNhw3ZO0qUKtaCO3gSqWdloecxpxUQSZCSW7tWPkpXXwDZqe",
    "gUkij9xMFS1pr37RIjCCBVAwggQ4oAMCAQICEEVKuaGraq1Cp4z6TFOeVfUwDQYJKoZIhvcNAQ",
    "ELBQAwUDEsMCoGA1UEAwwjQXBwbGUgRlAgU2VydmljZSBFbmFibGUgUlNBIENBIC0gRzExEzAR",
    "BgNVBAoMCkFwcGxlIEluYy4xCzAJBgNVBAYTAlVTMB4XDTIwMDQwNzIwMjY0NFoXDTIyMDQwNz",
    "IwMjY0NFowWjEhMB8GA1UEAwwYZnBzMjA0OC5pdHVuZXMuYXBwbGUuY29tMRMwEQYDVQQLDApB",
    "cHBsZSBJbmMuMRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzCCASIwDQYJKoZIhv",
    "cNAQEBBQADggEPADCCAQoCggEBAJNoUHuTRLafofQgIRgGa2TFIf+bsFDMjs+y3Ep1xCzFLE4Q",
    "bnwG6OG0duKUl5IoGUsouzZk9iGsXz5k3ESLOWKz2BFrDTvGrzAcuLpH66jJHGsk/l+ZzsDOJa",
    "oQ22pu0JvzYzW8/yEKvpE6JF/2dsC6V9RDTri3VWFxrl5uh8czzncoEQoRcQsSatHzs4tw/QdH",
    "FtBIigqxqr4R7XiCaHbsQmqbP9h7oxRs/6W/DDA2BgkuFY1ocX/8dTjmH6szKPfGt3KaYCwy3f",
    "uRC+FibTyohtvmlXsYhm7AUzorwWIwN/MbiFQ0OHHtDomIy71wDcTNMnY0jZYtGmIlJETAgYcC",
    "AwEAAaOCAhowggIWMAwGA1UdEwEB/wQCMAAwHwYDVR0jBBgwFoAUrI/yBkpV623/IeMrXzs8fC",
    "7VkZkwRQYIKwYBBQUHAQEEOTA3MDUGCCsGAQUFBzABhilodHRwOi8vb2NzcC5hcHBsZS5jb20v",
    "b2NzcDAzLWZwc3J2cnNhZzEwMzCBwwYDVR0gBIG7MIG4MIG1BgkqhkiG92NkBQEwgacwgaQGCC",
    "sGAQUFBwICMIGXDIGUUmVsaWFuY2Ugb24gdGhpcyBjZXJ0aWZpY2F0ZSBieSBhbnkgcGFydHkg",
    "YXNzdW1lcyBhY2NlcHRhbmNlIG9mIGFueSBhcHBsaWNhYmxlIHRlcm1zIGFuZCBjb25kaXRpb2",
    "5zIG9mIHVzZSBhbmQvb3IgY2VydGlmaWNhdGlvbiBwcmFjdGljZSBzdGF0ZW1lbnRzLjAdBgNV",
    "HQ4EFgQU2RpCSSHFXeoZQQWxbwJuRZ9RrIEwDgYDVR0PAQH/BAQDAgUgMBQGCyqGSIb3Y2QGDQ",
    "EFAQH/BAIFADAjBgsqhkiG92NkBg0BBgEB/wQRAQAAAAMAAAABAAAAAgAAAAMwOQYLKoZIhvdj",
    "ZAYNAQMBAf8EJwG+pUeWbeZBUI0PikyFwSggL5dHaeugSDoQKwcP28csLuh5wplpATAzBgsqhk",
    "iG92NkBg0BBAEB/wQhAfl9TGjP/UY9TyQzYsn8sX9ZvHChok9QrrUhtAyWR1yCMA0GCSqGSIb3",
    "DQEBCwUAA4IBAQBNMzZ6llQ0laLXsrmyVieuoW9+pHeAaDJ7cBiQLjM3ZdIO3Gq5dkbWYYYwJw",
    "ymdxZ74WGZMuVv3ueJKcxG1jAhCRhr0lb6QaPaQQSNW+xnoesb3CLA0RzrcgBp/9WFZNdttJOS",
    "yC93lQmiE0r5RqPpe/IWUzwoZxri8qnsghVFxCBEcMB+U4PJR8WeAkPrji8po2JLYurvgNRhGk",
    "DKcAFPuGEpXdF86hPts+07zazsP0fBjBSVgP3jqb8G31w5W+O+wBW0B9uCf3s0vXU4LuJTAyww",
    "s2ImZ7O/AaY/uXWOyIUMUKPgL1/QJieB7pBoENIJ2CeJS2M3iv00ssmCmTEJ",
);
