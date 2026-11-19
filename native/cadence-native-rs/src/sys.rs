//! ABI primitives for calling Apple's StoreServices C++ libraries from Rust, replacing the former
//! C/C++ glue (see native/RUST_MIGRATION.md). Everything here is `unsafe`: raw pointers into closed
//! libraries by mangled symbol, with hand-matched libc++ layouts. Rust does not make this safer than
//! the C it replaces — it localises the unsafe seam and lets us build with cargo alone.
//!
//! Phase 0 (scaffolding): the core types, the std::string view, the symbol resolver, and the
//! sret-return mechanism with a few representative bindings. The C/C++ remains the live path until
//! each piece is ported and verified on-device (Phases 2+).
#![allow(dead_code)]

use std::ffi::{c_char, c_int, c_void, CStr};

/// libc++ `std::shared_ptr<T>`: `{ T* obj; __shared_weak_count* ctrl }`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SharedPtr {
    pub obj: *mut c_void,
    pub ctrl: *mut c_void,
}
impl SharedPtr {
    pub const NULL: SharedPtr = SharedPtr { obj: std::ptr::null_mut(), ctrl: std::ptr::null_mut() };
    pub fn is_null(&self) -> bool { self.obj.is_null() }
}

/// libc++ `std::vector`: `{ begin, end, end_cap }`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct StdVector {
    pub begin: *mut c_void,
    pub end: *mut c_void,
    pub end_cap: *mut c_void,
}

/// A non-owning libc++ `std::string` argument, matching the C `new_std_string`: long mode with the
/// capacity's low bit set so the library reads `data`. The borrowed bytes must outlive the call.
#[repr(C)]
pub struct StdString {
    cap: usize,
    size: usize,
    data: *const u8,
}
impl StdString {
    /// Borrow a NUL-terminated C string as a `std::string` argument (non-owning).
    pub fn borrow(s: &CStr) -> StdString {
        StdString { cap: 1, size: s.to_bytes().len(), data: s.as_ptr().cast() }
    }
    /// Read a `std::string`'s bytes (short or long mode), as the C `std_string_data` did.
    pub unsafe fn data(p: *const StdString) -> *const c_char {
        let first = *(p as *const u8);
        if first & 1 == 0 {
            (p as *const u8).add(1) as *const c_char // short mode: inline bytes after the length byte
        } else {
            (*p).data as *const c_char
        }
    }
}

// ---- sret returns -------------------------------------------------------------------------------
// A C++ function returning a non-trivial type (shared_ptr, std::string, Data) returns it indirectly
// through the x8 register on AArch64. Rust returns any `repr(C)` struct larger than 16 bytes the same
// way — so if we declare the FFI return type as >16 bytes, the call conventions line up: the callee
// writes its (≤16-byte) value into our slot and we read the leading bytes. No inline asm needed.

/// 24-byte carrier for a returned `shared_ptr` (16 bytes used, 8 padding to force x8-indirect return).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SretShared {
    pub sp: SharedPtr,
    _pad: usize,
}

/// 24-byte carrier for a returned `mediaplatform::Data` (`{ bytes*, size }`, 16 bytes used).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SretData {
    pub bytes: *mut c_char,
    pub size: usize,
    _pad: usize,
}

/// `RequestContext::init(...)` returns an 88-byte result by value (sret); we only need the side
/// effect, so the bytes are opaque. >16 → x8-indirect, matching the C++ shim's struct.
#[repr(C)]
pub struct ReqCtxInit88 {
    _b: [u8; 88],
}

/// `DeviceGUID::configure(...)` returns a 256-byte result by value (sret), used only for its side
/// effect. Opaque.
#[repr(C)]
pub struct DevGuidConf256 {
    _b: [u8; 256],
}

// ---- symbol resolution --------------------------------------------------------------------------
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
extern "C" {
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
