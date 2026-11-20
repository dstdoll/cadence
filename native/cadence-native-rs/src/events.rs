//! The native error channel: one place that turns what goes wrong inside the native layer into a
//! readable line in the app's own log (`cadence.log`, the file the in-app log export shares).
//!
//! Every event is a single line starting with a greppable code:
//!
//! | code             | meaning                                                                      |
//! |------------------|------------------------------------------------------------------------------|
//! | `[CXX-THROW]`    | an Apple C++ call threw; the guard caught it, skipped the call, and the entry |
//! |                  | point returned failure (the process lives on)                                |
//! | `[CXX-SKIP]`     | later Apple calls of the same entry point were skipped after a throw         |
//! | `[CXX-TERMINATE]`| a C++ exception nobody could catch (e.g. on an Apple-created thread) is     |
//! |                  | about to abort the process; this line says what it was                       |
//! | `[RUST-PANIC]`   | a Rust panic (message, file:line, thread); the process aborts right after    |
//! | `[NATIVE-INFO]`  | start-up facts (guard installed, versions)                                   |
//!
//! Delivery: the JVM bridge calls `NativeWrapper.onNativeEvent` (Kotlin), which writes through
//! `AppLog`, so the line is redacted by `LogRedactor` like every other log line and shows up in the
//! exported log in order with the Kotlin lines around it. When the bridge is unavailable (before
//! `JNI_OnLoad`, in the device self-test, during teardown) the line goes to logcat after a
//! conservative scrub. The last events are also kept in a small ring for tests and diagnostics.
//! A fatal signal is different: it cannot run any of this safely, so `support.rs` writes a plain
//! line to `native-crash.log` instead.

use crate::logfmt;
use jni::objects::{JClass, JValue};
use jni::JavaVM;
use std::cell::Cell;
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::{Mutex, OnceLock};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Info = 0,
    Error = 1,
}

const KOTLIN_CLASS: &str = "app/cadence/backend/engine/media/NativeWrapper";
const KOTLIN_METHOD: &str = "onNativeEvent";
const KOTLIN_SIG: &str = "(ILjava/lang/String;Ljava/lang/String;)V";
const RING_CAP: usize = 32;

struct Bridge {
    vm: JavaVM,
    class: jni::objects::GlobalRef,
}
static BRIDGE: OnceLock<Bridge> = OnceLock::new();
static RING: Mutex<Vec<String>> = Mutex::new(Vec::new());

thread_local! {
    static IN_EMIT: Cell<bool> = const { Cell::new(false) };
}

extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, msg: *const c_char) -> c_int;
}

/// Called from `JNI_OnLoad` (a Java thread, inside `System.loadLibrary`): remembers the VM and the
/// `NativeWrapper` class so events can be delivered from any thread later. Harmless if it fails.
pub fn init_bridge(vm: *mut c_void) {
    if vm.is_null() {
        return;
    }
    // SAFETY: `vm` is the JavaVM* the runtime passed to JNI_OnLoad.
    let Ok(vm) = (unsafe { JavaVM::from_raw(vm as *mut jni::sys::JavaVM) }) else { return };
    let Ok(mut env) = vm.get_env() else { return };
    let class = match env.find_class(KOTLIN_CLASS) {
        Ok(c) => c,
        Err(_) => {
            let _ = env.exception_clear();
            return;
        }
    };
    let Ok(global) = env.new_global_ref(&class) else { return };
    let _ = BRIDGE.set(Bridge { vm, class: global });
}

/// Calls a static method of `NativeWrapper` with an optional leading int and string arguments.
/// Returns false when the bridge is down or the call raised (the exception is cleared).
fn call_kotlin(method: &str, sig: &str, int_arg: Option<i32>, strings: &[&str]) -> bool {
    let Some(bridge) = BRIDGE.get() else { return false };
    // Already attached (a Java thread inside a JNI call) or attach for the duration of this call.
    let mut guard = None;
    let mut env = match bridge.vm.get_env() {
        Ok(env) => env,
        Err(_) => match bridge.vm.attach_current_thread() {
            Ok(g) => {
                guard = Some(g);
                // SAFETY: the guard lives until the end of this function and owns the attachment.
                unsafe { jni::JNIEnv::from_raw(guard.as_ref().unwrap().get_raw()).unwrap() }
            }
            Err(_) => return false,
        },
    };
    let mut jstrings = Vec::with_capacity(strings.len());
    for s in strings {
        match env.new_string(s) {
            Ok(j) => jstrings.push(j),
            Err(_) => {
                let _ = env.exception_clear();
                return false;
            }
        }
    }
    let mut args: Vec<JValue> = Vec::with_capacity(strings.len() + 1);
    if let Some(i) = int_arg {
        args.push(JValue::Int(i));
    }
    args.extend(jstrings.iter().map(|j| JValue::Object(&**j)));
    // SAFETY: `class` is a global reference to the NativeWrapper class.
    let class = unsafe { JClass::from_raw(bridge.class.as_raw()) };
    let result = env.call_static_method(&class, method, sig, &args);
    let threw = env.exception_check().unwrap_or(false);
    if threw {
        let _ = env.exception_clear();
    }
    drop(guard);
    !threw && result.is_ok()
}

fn deliver_to_kotlin(level: Level, tag: &str, msg: &str) -> bool {
    call_kotlin(KOTLIN_METHOD, KOTLIN_SIG, Some(level as i32), &[tag, msg])
}

/// Tells the app something happened that the user should know about (`NativeWrapper.onNativeNotice`:
/// kind code such as `KEY_REJECTED`, impact `track`/`session`/`app`, the entry point, its detail).
/// Best effort: with no JVM bridge it does nothing (the log line already carries the same facts).
pub fn notice(kind: &str, impact: &str, entry: &str, detail: &str) {
    let _ = call_kotlin("onNativeNotice", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", None, &[kind, impact, entry, detail]);
}

/// A line Apple's libraries (or the platform) wrote to liblog, captured by the hooks: delivered to
/// `NativeWrapper.onAppleLog(tag, "D t4300 message")`. No logcat fallback: it already is in logcat.
pub fn apple_log(tag: &str, line: &str) {
    let _ = call_kotlin("onAppleLog", "(Ljava/lang/String;Ljava/lang/String;)V", None, &[tag, line]);
}

fn to_logcat(level: Level, tag: &str, msg: &str) {
    let (Ok(t), Ok(m)) = (CString::new(format!("cadence-{tag}")), CString::new(logfmt::mask(msg))) else { return };
    let prio = if level == Level::Error { 6 } else { 4 };
    // SAFETY: both strings are NUL-terminated and outlive the call.
    unsafe { __android_log_write(prio, t.as_ptr(), m.as_ptr()) };
}

/// Records and delivers one event. Never panics, never blocks on anything but the ring lock, and
/// ignores re-entrant calls (an event raised while delivering an event).
pub fn emit(level: Level, tag: &str, msg: &str) {
    if IN_EMIT.with(|f| f.replace(true)) {
        return;
    }
    {
        let mut ring = RING.lock().unwrap_or_else(|e| e.into_inner());
        if ring.len() == RING_CAP {
            ring.remove(0);
        }
        ring.push(logfmt::mask(msg));
    }
    if !deliver_to_kotlin(level, tag, msg) {
        to_logcat(level, tag, msg);
    }
    #[cfg(feature = "selftest")]
    if std::env::var_os("CADENCE_NATIVE_EVENTS_STDERR").is_some() {
        eprintln!("{tag}: {}", logfmt::mask(msg));
    }
    IN_EMIT.with(|f| f.set(false));
}

/// The most recent events, oldest first (already scrubbed).
#[allow(dead_code)]
pub fn recent() -> Vec<String> {
    RING.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

// ---- C++ names -----------------------------------------------------------------------------------

#[cfg(all(target_arch = "aarch64", target_os = "android"))]
extern "C" {
    fn __cxa_demangle(mangled: *const c_char, out: *mut c_char, len: *mut usize, status: *mut c_int) -> *mut c_char;
}

/// Demangles an Itanium C++ name or type (`St13runtime_error` -> `std::runtime_error`) with the
/// libc++abi demangler inside `libc++_shared.so`. `None` if it is not a mangled name.
pub fn demangle(mangled: &str) -> Option<String> {
    #[cfg(all(target_arch = "aarch64", target_os = "android"))]
    {
        let c = CString::new(mangled).ok()?;
        let mut status: c_int = -1;
        // SAFETY: valid NUL-terminated input; the result is malloc'd and freed below.
        let out = unsafe { __cxa_demangle(c.as_ptr(), std::ptr::null_mut(), std::ptr::null_mut(), &mut status) };
        if out.is_null() || status != 0 {
            return None;
        }
        let s = unsafe { CStr::from_ptr(out) }.to_string_lossy().into_owned();
        unsafe { libc::free(out as *mut c_void) };
        Some(s)
    }
    #[cfg(not(all(target_arch = "aarch64", target_os = "android")))]
    {
        let _ = mangled;
        None
    }
}

/// The readable name of the Apple function at `addr` (`SVFootHillSessionCtrl::getPersistentKey`),
/// from the dynamic symbol table; `None` when the address has no symbol.
pub fn apple_symbol(addr: usize) -> Option<String> {
    // SAFETY: dladdr only reads the loader's tables.
    let mut info: libc::Dl_info = unsafe { std::mem::zeroed() };
    if unsafe { libc::dladdr(addr as *const c_void, &mut info) } == 0 || info.dli_sname.is_null() {
        return None;
    }
    let sym = unsafe { CStr::from_ptr(info.dli_sname) }.to_string_lossy().into_owned();
    Some(match demangle(&sym) {
        Some(d) => logfmt::without_params(&d).to_owned(),
        None => sym,
    })
}

/// A thrown type name as readable text: demangled when possible, else as the runtime gave it.
pub fn readable_type(raw: &str) -> String {
    if raw.is_empty() {
        return "unknown type".to_owned();
    }
    demangle(raw).unwrap_or_else(|| raw.to_owned())
}

/// `name (tid)` of the current thread, from the kernel's comm name (Java thread names included).
pub fn thread_label() -> String {
    let mut name = [0u8; 32];
    // SAFETY: PR_GET_NAME writes at most 16 bytes into the buffer.
    unsafe { libc::prctl(libc::PR_GET_NAME, name.as_mut_ptr() as libc::c_ulong, 0, 0, 0) };
    let end = name.iter().position(|&b| b == 0).unwrap_or(name.len());
    let tid = unsafe { libc::gettid() };
    format!("{} (tid {})", String::from_utf8_lossy(&name[..end]), tid)
}

/// Implemented here for the C++ terminate handler in `cxx_trampoline.cpp`: that handler runs when a
/// C++ exception has no catch frame anywhere (typically on a thread Apple created), then continues
/// to `abort()`.
#[no_mangle]
pub extern "C" fn cadence_terminate_report(type_name: *const c_char, what: *const c_char) {
    let take = |p: *const c_char| if p.is_null() { String::new() } else { unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned() };
    let (ty, what) = (readable_type(&take(type_name)), take(what));
    let (entry, call) = crate::cxx_guard::breadcrumb();
    let active = if entry.is_empty() && call.is_empty() {
        "no native entry point of ours was active (Apple-created thread, or a call outside the guard)".to_owned()
    } else {
        format!("entry={} apple_call={}", if entry.is_empty() { "?" } else { entry }, if call.is_empty() { "-" } else { call })
    };
    let what = if what.is_empty() { String::new() } else { format!(": \"{}\"", logfmt::truncate(&what, 200)) };
    emit(
        Level::Error,
        "native",
        &format!(
            "[CXX-TERMINATE] uncaught C++ exception {ty}{what} on thread {}; {active}; the process aborts now (nothing could catch this one)",
            thread_label()
        ),
    );
}
