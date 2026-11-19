//! C-ABI bridge functions the remaining C glue still calls, reimplemented in Rust (replacing the
//! `*_bridge.cpp` shims) using the `sys` sret mechanism. Each is deleted once its C caller also moves
//! to Rust. Exported via `#[no_mangle]`; the C objects in the same `.so` link to them statically.

use crate::sys::{self, SharedPtr, StdString};
use std::ffi::{c_char, c_int, c_void, CStr};

/// Heap-box a returned `shared_ptr` so the C caller can treat it as `struct shared_ptr *` (matching
/// the C++ shims' `new std::shared_ptr<T>(...)`). The 16 bytes already carry refcount 1 from
/// make_shared; we just move them to the heap.
unsafe fn box_shared(sp: SharedPtr) -> *mut c_void {
    if sp.obj.is_null() {
        return std::ptr::null_mut();
    }
    Box::into_raw(Box::new(sp)) as *mut c_void
}

/// `std::make_shared<RequestContext>(std::string&)` — replaces request_ctx_bridge.cpp's version.
/// `path` is a `std::string*` (the mpl_db directory). Validates the sret padded-return trick.
#[no_mangle]
pub unsafe extern "C" fn cadence_make_request_ctx(path: *mut c_void) -> *mut c_void {
    box_shared(sys::make_request_ctx(path as *const StdString).sp)
}

/// `std::make_shared<AuthenticateFlow>(shared_ptr<RequestContext>&)`. `req_ctx` is the
/// `shared_ptr<RequestContext>*` from `cadence_make_request_ctx` (i.e. a `*SharedPtr`).
#[no_mangle]
pub unsafe extern "C" fn cadence_make_authenticate_flow(req_ctx: *mut c_void) -> *mut c_void {
    if req_ctx.is_null() {
        return std::ptr::null_mut();
    }
    box_shared(sys::make_authenticate_flow(req_ctx as *const SharedPtr).sp)
}

/// `std::make_shared<AndroidPresentationInterface>()`.
#[no_mangle]
pub unsafe extern "C" fn cadence_make_presentation() -> *mut c_void {
    box_shared(sys::make_presentation().sp)
}

/// `RequestContext::init(shared_ptr<RequestContextConfig>&)` — side effect only; the 88-byte sret
/// return is discarded. `this_` is the RequestContext object; `config` the `shared_ptr<...Config>*`.
#[no_mangle]
pub unsafe extern "C" fn cadence_request_ctx_init(this_: *mut c_void, config: *mut c_void) {
    if this_.is_null() || config.is_null() {
        return;
    }
    let _ = sys::request_ctx_init(this_, config as *const SharedPtr);
}

/// `SVFootHillSessionCtrl::getPersistentKey(this, 8×std::string&)`. `a1..a8` are `std::string*`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn cadence_get_persistent_key(
    self_: *mut c_void,
    a1: *mut c_void, a2: *mut c_void, a3: *mut c_void, a4: *mut c_void,
    a5: *mut c_void, a6: *mut c_void, a7: *mut c_void, a8: *mut c_void,
) -> *mut c_void {
    box_shared(sys::get_persistent_key(
        self_,
        a1 as *const StdString, a2 as *const StdString, a3 as *const StdString, a4 as *const StdString,
        a5 as *const StdString, a6 as *const StdString, a7 as *const StdString, a8 as *const StdString,
    ).sp)
}

/// `SVFootHillSessionCtrl::decryptContext(this, std::string&, SVDecryptorType&(0), bool&(false))`.
/// `persistent_key` is the PersistentKey*, whose leading bytes are the key `std::string` the call
/// takes (matching the C++ shim's `*static_cast<std::string*>(persistentKey)`).
#[no_mangle]
pub unsafe extern "C" fn cadence_decrypt_context(self_: *mut c_void, persistent_key: *mut c_void) -> *mut c_void {
    if persistent_key.is_null() {
        return std::ptr::null_mut();
    }
    let ty: c_int = 0;
    let flag: bool = false;
    box_shared(sys::decrypt_context(self_, persistent_key as *const StdString, &ty, &flag).sp)
}

/// `DeviceGUID::instance()` warmed and released? The C++ let its `shared_ptr` destruct; we hold the
/// reference (the GUID singleton lives for the process), which is harmless for a one-time warm-up.
#[no_mangle]
pub unsafe extern "C" fn cadence_device_guid_warm() {
    let _ = sys::device_guid_instance();
}

/// `new std::shared_ptr<DeviceGUID>(DeviceGUID::instance())` — a stable heap shared_ptr the C holds.
#[no_mangle]
pub unsafe extern "C" fn cadence_device_guid_get() -> *mut c_void {
    box_shared(sys::device_guid_instance().sp)
}

/// Copy `DeviceGUID::guid()`'s string into `out` (≤ `cap`-1 bytes + NUL). `device_guid_obj` is the
/// DeviceGUID*. Returns 1 on success, 0 otherwise. Mirrors device_guid_bridge.cpp.
#[no_mangle]
pub unsafe extern "C" fn cadence_device_guid_string(device_guid_obj: *mut c_void, out: *mut c_char, cap: c_int) -> c_int {
    if device_guid_obj.is_null() || out.is_null() || cap <= 0 {
        return 0;
    }
    let data = sys::device_guid(device_guid_obj); // Data by value (sret); .bytes == Data's first word
    if data.bytes.is_null() {
        return 0;
    }
    let bytes = sys::data_bytes(data.bytes as *mut c_void);
    if bytes.is_null() || *bytes == 0 {
        return 0;
    }
    let src = CStr::from_ptr(bytes).to_bytes();
    let n = src.len().min((cap as usize).saturating_sub(1));
    std::ptr::copy_nonoverlapping(src.as_ptr(), out as *mut u8, n);
    *out.add(n) = 0;
    1
}

/// `DeviceGUID::configure(this, std::string&, std::string&, unsigned&, bool&)` — side effect only;
/// the 256-byte sret return is discarded. `first`/`second` are `std::string*`, `conf3` `unsigned*`,
/// `conf4` `bool*`.
#[no_mangle]
pub unsafe extern "C" fn cadence_device_guid_configure(
    self_: *mut c_void, first: *mut c_void, second: *mut c_void, conf3: *mut c_void, conf4: *mut c_void,
) {
    let _ = sys::device_guid_configure(
        self_,
        first as *const StdString,
        second as *const StdString,
        conf3 as *const u32,
        conf4 as *const bool,
    );
}
