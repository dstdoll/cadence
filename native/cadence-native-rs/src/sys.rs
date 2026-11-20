//! ABI seam to the closed StoreServices libraries, under the exception guard.
//!
//! **Types** (libc++ layout and sret carriers) are no longer defined here: they come from the
//! `apple-ss-sys` crate, which is the audited catalog of the 62 symbols and the single source of
//! truth for layout. Previously they were duplicated on both sides, which is right where a
//! single-field mismatch silently corrupts memory.
//!
//! **Calls** are declared here intentionally: `apple_externs!` wraps them in `cxx_guard`, which
//! catches Apple's C++ `throw`s and converts them into a zero return. `apple-ss-sys` declares the
//! raw externs (`extern "C-unwind"`, which allows unwinding without catching it), so it must not
//! be called directly from live code paths: a throw would unwind across Rust frames. If a new
//! symbol is needed, copy its signature from `apple-ss-sys` into this block instead of calling it
//! there.
//!
//! Everything here is `unsafe`: raw pointers into closed libraries via mangled symbols, with
//! hand-matched libc++ layouts. Rust does not make it safer than the C it replaced; it isolates
//! the seam and lets Cargo build standalone.
#![allow(dead_code)]

use std::ffi::{c_char, c_int, c_void, CStr};

// Tipos del ABI: una sola definicion, en apple-ss-sys. `StdString::borrow` alla toma
// `&str`; la ruta viva usa `borrow_cstr`, que toma el `CStr` que ya armo la capa JNI.
pub use apple_ss_sys::abi::{
    DevGuidConf256, ReqCtxInit88, SharedPtr, SretData, SretShared, StdString, StdVector,
};

extern "C" {
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}
/// bionic `RTLD_DEFAULT` for LP64.
const RTLD_DEFAULT: *mut c_void = std::ptr::null_mut();

/// Resolve a (mangled) symbol from any loaded library, or `None`.
pub fn sym(name: &CStr) -> Option<usize> {
    let p = unsafe { dlsym(RTLD_DEFAULT, name.as_ptr()) };
    if p.is_null() { None } else { Some(p as usize) }
}

// ---- representative StoreServices bindings (Phase 0: prove the shapes compile) -------------------
// These are linked by their mangled C++ names; the final module will group them by subsystem.
// Every function here is an Apple C++ call: `apple_externs!` runs it under the C++ exception catch
// (see `cxx_guard`); a throw returns zero and skips the rest of the entry point.
apple_externs! {
    /// `std::make_shared<RequestContext>(std::string&)` → shared_ptr (sret).
    #[link_name = "_ZNSt6__ndk110shared_ptrIN17storeservicescore14RequestContextEE11make_sharedIJRNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEEEEES3_DpOT_"]
    pub fn make_request_ctx(path: *const StdString) -> SretShared;

    /// `std::make_shared<AuthenticateFlow>(shared_ptr<RequestContext>&)` → shared_ptr (sret).
    #[link_name = "_ZNSt6__ndk110shared_ptrIN17storeservicescore16AuthenticateFlowEE11make_sharedIJRNS0_INS1_14RequestContextEEEEEES3_DpOT_"]
    pub fn make_authenticate_flow(ctx: *const SharedPtr) -> SretShared;

    /// `DeviceGUID::guid()` → `Data` by value (sret); `bytes` points at the GUID string.
    #[link_name = "_ZN17storeservicescore10DeviceGUID4guidEv"]
    pub fn device_guid(this: *mut c_void) -> SretData;

    /// `AuthenticateFlow::run()` — a plain method, no sret.
    #[link_name = "_ZN17storeservicescore16AuthenticateFlow3runEv"]
    pub fn authenticate_flow_run(this: *mut c_void);

    /// `AuthenticateFlow::response() const` → `shared_ptr*` (a reference, not sret).
    #[link_name = "_ZNK17storeservicescore16AuthenticateFlow8responseEv"]
    pub fn authenticate_flow_response(this: *mut c_void) -> *mut SharedPtr;

    /// `AuthenticateResponse::responseType() const` → int (6 == signed in).
    #[link_name = "_ZNK17storeservicescore20AuthenticateResponse12responseTypeEv"]
    pub fn authenticate_response_type(this: *mut c_void) -> i32;

    /// The wrapper's per-sample FairPlay decrypt server call: `(ctx, mode, in, out, len)`, mode 5 =
    /// content-key. Decrypts in place (`in == out`). Plain C ABI, no sret.
    #[link_name = "NfcRKVnxuKZy04KWbdFu71Ou"]
    pub fn nfc_decrypt_sample(ctx: *mut c_void, mode: u32, input: *mut c_void, output: *mut c_void, len: usize) -> i64;

    /// `std::make_shared<AndroidPresentationInterface>()` → shared_ptr (sret).
    #[link_name = "_ZNSt6__ndk110shared_ptrIN20androidstoreservices28AndroidPresentationInterfaceEE11make_sharedIJEEES3_DpOT_"]
    pub fn make_presentation() -> SretShared;

    /// `RequestContext::init(shared_ptr<RequestContextConfig>&)` → 88-byte result (sret, discarded).
    /// `config` points at the caller's `shared_ptr` (the `const&`).
    #[link_name = "_ZN17storeservicescore14RequestContext4initERKNSt6__ndk110shared_ptrINS_20RequestContextConfigEEE"]
    pub fn request_ctx_init(this: *mut c_void, config: *const SharedPtr) -> ReqCtxInit88;

    /// `SVFootHillSessionCtrl::getPersistentKey(this, 8×std::string&)` → shared_ptr<PersistentKey> (sret).
    #[link_name = "_ZN21SVFootHillSessionCtrl16getPersistentKeyERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEES8_S8_S8_S8_S8_S8_S8_"]
    pub fn get_persistent_key(
        this: *mut c_void,
        a1: *const StdString, a2: *const StdString, a3: *const StdString, a4: *const StdString,
        a5: *const StdString, a6: *const StdString, a7: *const StdString, a8: *const StdString,
    ) -> SretShared;

    /// `SVFootHillSessionCtrl::decryptContext(this, std::string&, SVDecryptorType&, bool&)` →
    /// shared_ptr<SVFootHillPContext> (sret). `key` is the PersistentKey's leading std::string.
    #[link_name = "_ZN21SVFootHillSessionCtrl14decryptContextERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEERKN11SVDecryptor15SVDecryptorTypeERKb"]
    pub fn decrypt_context(this: *mut c_void, key: *const StdString, ty: *const c_int, flag: *const bool) -> SretShared;

    /// `DeviceGUID::instance()` → shared_ptr<DeviceGUID> (sret).
    #[link_name = "_ZN17storeservicescore10DeviceGUID8instanceEv"]
    pub fn device_guid_instance() -> SretShared;

    /// `mediaplatform::Data::bytes() const` → `char*` (the GUID string).
    #[link_name = "_ZNK13mediaplatform4Data5bytesEv"]
    pub fn data_bytes(this: *mut c_void) -> *const c_char;

    /// `DeviceGUID::configure(this, std::string&, std::string&, unsigned&, bool&)` → 256-byte result
    /// (sret, discarded).
    #[link_name = "_ZN17storeservicescore10DeviceGUID9configureERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_RKjRKb"]
    pub fn device_guid_configure(this: *mut c_void, a1: *const StdString, a2: *const StdString, a3: *const u32, a4: *const bool) -> DevGuidConf256;
}

// ---- layout guards: a toolchain / libc++ change that moves these fails the build, not the phone ---
const _: () = assert!(std::mem::size_of::<SharedPtr>() == 16);
const _: () = assert!(std::mem::size_of::<StdString>() == 24);
const _: () = assert!(std::mem::size_of::<StdVector>() == 24);
const _: () = assert!(std::mem::size_of::<SretShared>() == 24); // >16 → x8-indirect return
const _: () = assert!(std::mem::size_of::<SretData>() == 24);
