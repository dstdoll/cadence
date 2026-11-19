//! The two `std::function` lease callbacks ported from native_support.cpp, hand-built in Rust to
//! match libc++'s (`__ndk1`) `std::function` ABI so `SVPlaybackLeaseManager`'s constructor can copy,
//! store, invoke and destroy them.
//!
//! libc++ `std::function<R(A)>` is `{ __buf_[3 words]; __base* __f_ }` (32 bytes). For a small target
//! (a function pointer) it uses the small-buffer optimisation: a `__func` (`{ vptr; target }`, 16
//! bytes) lives inline in `__buf_` and `__f_` points at it. `__func`'s vtable (Itanium layout, the
//! object's vptr pointing at the first virtual slot) is:
//!   [-2] offset-to-top=0  [-1] typeinfo   [0] __clone()  [1] __clone(__base*)  [2] destroy()
//!   [3] destroy_deallocate()  [4] operator()(A)  [5] target(type_info)  [6] target_type()
//! The lease copy-ctor calls __clone(dest) (placement copy) for an SBO source; invocation calls
//! operator(); destruction calls destroy() (no-op for a trivial fn-pointer target). This is pinned to
//! the NDK libc++ we build with; a toolchain change could move the layout (the layout guards and the
//! on-device lease test are the backstops).

use std::cell::UnsafeCell;
use std::ffi::{c_char, c_int, c_void};
use std::ptr;

extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

/// The inline `__func`: vtable pointer then the Rust callback (a function pointer). 16 bytes, fits the
/// 24-byte small-buffer.
#[repr(C)]
#[derive(Clone, Copy)]
struct Func {
    vtable: *const VTable,
    target: extern "C" fn(*const c_void),
}

/// libc++ `__func`'s vtable, in Itanium layout: the object's vptr points at `clone_heap` (the first
/// virtual slot); `typeinfo`/`offset_to_top` sit just before it.
#[repr(C)]
struct VTable {
    offset_to_top: usize,
    typeinfo: usize,
    dtor_complete: unsafe extern "C" fn(*mut Func),
    dtor_deleting: unsafe extern "C" fn(*mut Func),
    clone_heap: unsafe extern "C" fn(*mut Func) -> *mut Func,
    clone_place: unsafe extern "C" fn(*mut Func, *mut Func),
    destroy: unsafe extern "C" fn(*mut Func),
    destroy_deallocate: unsafe extern "C" fn(*mut Func),
    call: unsafe extern "C" fn(*mut Func, *const c_void),
    target: unsafe extern "C" fn(*mut Func, *const c_void) -> *const c_void,
    target_type: unsafe extern "C" fn(*mut Func) -> *const c_void,
}
unsafe impl Sync for VTable {}

/// libc++ `std::function` storage: 16-byte-aligned 32-byte buffer (holding `Func` in SBO) + `__f_` at +0x20.
#[repr(C, align(16))]
struct StdFunction {
    buf: [usize; 4],
    f: *mut c_void,
    _pad: usize,
}
const _: () = assert!(std::mem::size_of::<StdFunction>() == 48);
const _: () = assert!(std::mem::offset_of!(StdFunction, f) == 32);
const _: () = assert!(std::mem::size_of::<Func>() == 16);

// ---- the __func vtable: operates on `this` == *mut Func ------------------------------------------
unsafe extern "C" fn vt_dtor_complete(_this: *mut Func) {}
unsafe extern "C" fn vt_dtor_deleting(this: *mut Func) {
    libc::free(this as *mut c_void);
}
unsafe extern "C" fn vt_clone_heap(this: *mut Func) -> *mut Func {
    let p = libc::malloc(std::mem::size_of::<Func>()) as *mut Func;
    if !p.is_null() {
        *p = *this;
    }
    p
}
unsafe extern "C" fn vt_clone_place(this: *mut Func, dest: *mut Func) {
    *dest = *this; // copy {vtable, target} into the destination std::function's buffer
}
unsafe extern "C" fn vt_destroy(_this: *mut Func) {}
unsafe extern "C" fn vt_destroy_deallocate(this: *mut Func) {
    libc::free(this as *mut c_void);
}
unsafe extern "C" fn vt_call(this: *mut Func, arg: *const c_void) {
    ((*this).target)(arg);
}
unsafe extern "C" fn vt_target(_this: *mut Func, _ti: *const c_void) -> *const c_void {
    ptr::null()
}
unsafe extern "C" fn vt_target_type(_this: *mut Func) -> *const c_void {
    ptr::null()
}

static VTABLE: VTable = VTable {
    offset_to_top: 0,
    typeinfo: 0, // unused by the lease's clone/call/destroy operations
    dtor_complete: vt_dtor_complete,
    dtor_deleting: vt_dtor_deleting,
    clone_heap: vt_clone_heap,
    clone_place: vt_clone_place,
    destroy: vt_destroy,
    destroy_deallocate: vt_destroy_deallocate,
    call: vt_call,
    target: vt_target,
    target_type: vt_target_type,
};

// ---- the two global callbacks -------------------------------------------------------------------
extern "C" fn end_lease_cb(code: *const c_void) {
    let c = if code.is_null() { -1 } else { unsafe { *(code as *const i32) } };
    log_err(&format!("end lease {c}"));
}
extern "C" fn pb_err_cb(_err: *const c_void) {
    log_err("playback error");
}

struct Slot(UnsafeCell<StdFunction>);
unsafe impl Sync for Slot {}
static END_LEASE: Slot = Slot(UnsafeCell::new(StdFunction { buf: [0; 4], f: ptr::null_mut(), _pad: 0 }));
static PB_ERR: Slot = Slot(UnsafeCell::new(StdFunction { buf: [0; 4], f: ptr::null_mut(), _pad: 0 }));
static INIT: std::sync::Once = std::sync::Once::new();

unsafe fn build(slot: *mut StdFunction, cb: extern "C" fn(*const c_void)) {
    (*slot).buf[0] = ptr::addr_of!(VTABLE.dtor_complete) as usize; // vptr → first virtual slot
    (*slot).buf[1] = cb as usize; // target
    (*slot).buf[2] = 0;
    (*slot).buf[3] = 0;
    (*slot).f = ptr::addr_of_mut!((*slot).buf) as *mut c_void; // __f_ → this std::function's own buffer
}

fn ensure_init() {
    INIT.call_once(|| unsafe {
        build(END_LEASE.0.get(), end_lease_cb);
        build(PB_ERR.0.get(), pb_err_cb);
    });
}

/// Address of the `std::function<void(int const&)>` end-of-lease callback (the lease ctor's 1st arg).
pub fn end_lease_ptr() -> *const c_void {
    ensure_init();
    END_LEASE.0.get() as *const c_void
}

/// Address of the `std::function<void(shared_ptr<StoreErrorCondition> const&)>` playback-error
/// callback (the lease ctor's 2nd arg).
pub fn pb_err_ptr() -> *const c_void {
    ensure_init();
    PB_ERR.0.get() as *const c_void
}

fn log_err(msg: &str) {
    if let Ok(c) = std::ffi::CString::new(msg) {
        let tag = std::ffi::CString::new("cadence-native").unwrap();
        unsafe { __android_log_write(6, tag.as_ptr(), c.as_ptr()) };
    }
}
