//! Device self-test of the C++ exception guard and the native error channel. Built only with
//! `--features selftest` (never in a release APK); `native/selftest-cxx-guard.sh` loads the stripped
//! library with `dlopen` on a phone and runs these exports against a stand-in "Apple" library
//! (`libthrower_so.so`) that throws real C++ exceptions with the app's shipped `libc++_shared.so`.
//!
//! The throwers are declared through `apple_externs!`, so the frame between the catch and the
//! thrower is exactly the production one, and `cadence_selftest_asm_throw` goes through the same
//! inline-asm thunk the lease/m4p calls use.
use crate::core::asm_call;
use crate::cxx_guard::{self, Scope};
use crate::events;
use std::ffi::{c_char, c_int};

#[link(name = "thrower_so")]
extern "C" {}

apple_externs! {
    fn cpp_throw();
    fn cpp_throw_int();
    fn cpp_throw_custom();
    fn cpp_throw_message();
    fn cpp_crash();
    fn cpp_spawn_throwing_thread();
    fn cpp_ok();
}

fn guarded(label: &'static str, detail: &str, call: impl FnOnce()) -> c_int {
    let scope = Scope::named(label, detail);
    call();
    if scope.threw() { -1 } else { 0 }
}

/// One guarded throw (`std::runtime_error("boom")`): -1 when it was caught.
#[no_mangle]
pub extern "C" fn cadence_selftest_throw() -> c_int {
    guarded("selftest.throw", "case=std", || unsafe { cpp_throw() })
}
/// `throw 42;` (not a std::exception).
#[no_mangle]
pub extern "C" fn cadence_selftest_throw_int() -> c_int {
    guarded("selftest.throw", "case=int", || unsafe { cpp_throw_int() })
}
/// A thrown class that is not derived from std::exception.
#[no_mangle]
pub extern "C" fn cadence_selftest_throw_custom() -> c_int {
    guarded("selftest.throw", "case=custom", || unsafe { cpp_throw_custom() })
}
/// A std::exception whose what() carries a URL with a token and an e-mail address.
#[no_mangle]
pub extern "C" fn cadence_selftest_throw_message() -> c_int {
    guarded("selftest.throw", "case=message", || unsafe { cpp_throw_message() })
}

/// Two throwing calls in one scope: the second must be skipped. Returns how many were caught (1).
#[no_mangle]
pub extern "C" fn cadence_selftest_poison() -> c_int {
    let before = cxx_guard::throw_count();
    let _scope = Scope::named("selftest.poison", "");
    unsafe {
        cpp_throw();
        cpp_throw();
    }
    (cxx_guard::throw_count() - before) as c_int
}

/// A normal call in a fresh scope after throws: must run and return 5 (poison does not leak).
#[no_mangle]
pub extern "C" fn cadence_selftest_ok() -> c_int {
    let scope = Scope::named("selftest.ok", "");
    unsafe { cpp_ok() };
    if scope.threw() { -1 } else { 5 }
}

/// The inline-asm thunk path (lease / m4p calls): a throw through `blr` is caught: -1.
#[no_mangle]
pub extern "C" fn cadence_selftest_asm_throw() -> c_int {
    let scope = Scope::named("selftest.asm", "");
    unsafe {
        // SAFETY: `cpp_throw` takes no arguments; only the calling ABI differs ("C-unwind" vs "C").
        let f: unsafe extern "C" fn() = std::mem::transmute(raw::cpp_throw as unsafe extern "C-unwind" fn());
        asm_call("selftest.asm", f, 0, [0; 8]);
    }
    if scope.threw() { -1 } else { 0 }
}

/// A Rust panic must abort the process after logging `[RUST-PANIC]`, never be swallowed.
#[no_mangle]
pub extern "C" fn cadence_selftest_panic() -> c_int {
    let _scope = Scope::named("selftest.panic", "");
    panic!("selftest: a Rust panic must abort");
}

/// A fatal signal inside an Apple call: the crash line must name the entry point and the call.
#[no_mangle]
pub extern "C" fn cadence_selftest_crash() -> c_int {
    let _scope = Scope::named("selftest.crash", "");
    unsafe { cpp_crash() };
    0
}

/// An uncaught C++ exception on a thread "Apple" made (only C++ frames on its stack), with no
/// guard in the way: the terminate handler must log `[CXX-TERMINATE]` and the process must abort.
#[no_mangle]
pub extern "C" fn cadence_selftest_uncaught() -> c_int {
    unsafe { cpp_spawn_throwing_thread() };
    0
}

#[no_mangle]
pub extern "C" fn cadence_selftest_throw_count() -> c_int {
    cxx_guard::throw_count() as c_int
}

/// Copies the n-th most recent event (0 = latest) into `buf` (NUL-terminated); returns its length
/// or -1 when there is no such event.
#[no_mangle]
pub extern "C" fn cadence_selftest_event(n: c_int, buf: *mut c_char, cap: c_int) -> c_int {
    let events = events::recent();
    let Some(e) = events.iter().rev().nth(n as usize) else { return -1 };
    let bytes = e.as_bytes();
    let len = bytes.len().min((cap as usize).saturating_sub(1));
    // SAFETY: the harness passes a buffer of `cap` bytes.
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buf as *mut u8, len);
        *buf.add(len) = 0;
    }
    len as c_int
}

/// What `JNI_OnLoad` does for hooks, without its side effects on the app's real files: the panic
/// hook, the terminate report, and a crash-line log at `path` (never the app's `native-crash.log`).
#[no_mangle]
pub extern "C" fn cadence_selftest_install_hooks(path: *const c_char) -> c_int {
    crate::support::install_panic_hook();
    cxx_guard::install_terminate_handler();
    if !path.is_null() {
        let p = unsafe { std::ffi::CStr::from_ptr(path) }.to_string_lossy().into_owned();
        crate::support::install_crash_handlers_to(&p);
    }
    0
}
