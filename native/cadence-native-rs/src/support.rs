//! Library support ported from native_support.cpp: JNI_OnLoad and the native crash handler. These
//! were the last non-ABI C++; everything here is plain Rust over libc. The std::function lease
//! callbacks live in `cppfn`.

use std::ffi::{c_char, c_int, c_void, CString};
use std::sync::atomic::{AtomicI32, Ordering};

extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

static CRASH_FD: AtomicI32 = AtomicI32::new(-1);
const HANDLED_SIGS: [c_int; 5] = [libc::SIGSEGV, libc::SIGABRT, libc::SIGBUS, libc::SIGILL, libc::SIGFPE];
static mut OLD_ACTIONS: [libc::sigaction; 5] = unsafe { std::mem::zeroed() };

const JNI_VERSION_1_6: c_int = 0x0001_0006;

fn log_info(msg: &str) {
    if let Ok(c) = CString::new(msg) {
        let tag = CString::new("cadence-native").unwrap();
        unsafe { __android_log_write(4, tag.as_ptr(), c.as_ptr()) };
    }
}

extern "C" fn native_signal_handler(sig: c_int, info: *mut libc::siginfo_t, _ctx: *mut c_void) {
    let fd = CRASH_FD.load(Ordering::Relaxed);
    if fd >= 0 {
        // async-signal-safe: the line is built in a stack buffer (no allocation, no std formatting)
        // and says which native entry point and Apple call this thread was in (cxx_guard breadcrumb).
        let (entry, call) = crate::cxx_guard::breadcrumb();
        let addr = if info.is_null() { 0 } else { unsafe { (*info).si_addr() as usize } };
        let mut buf = [0u8; 512];
        let n = crate::logfmt::crash_line(&mut buf, sig, addr, unsafe { libc::gettid() }, entry, call);
        unsafe { libc::write(fd, buf.as_ptr() as *const c_void, n) };
    }
    let tag = b"cadence-native\0";
    let msg = b"native crash\0";
    unsafe {
        __android_log_write(6 /*FATAL*/, tag.as_ptr() as *const c_char, msg.as_ptr() as *const c_char);
        if let Some(idx) = HANDLED_SIGS.iter().position(|&s| s == sig) {
            let old = std::ptr::addr_of!(OLD_ACTIONS[idx]);
            libc::sigaction(sig, old, std::ptr::null_mut());
        } else {
            libc::signal(sig, libc::SIG_DFL);
        }
        let mut set: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, sig);
        libc::sigprocmask(libc::SIG_UNBLOCK, &set, std::ptr::null_mut());
        libc::raise(sig);
    }
}

fn install_crash_handlers() {
    install_crash_handlers_to("/sdcard/Android/data/app.cadence/files/logs/native-crash.log");
}

/// Fatal-signal handlers writing their line to `path` (the self-test points this somewhere else so
/// a test never writes to the app's real crash log).
pub fn install_crash_handlers_to(path: &str) {
    let Ok(path) = CString::new(path) else { return };
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_CREAT | libc::O_WRONLY | libc::O_APPEND, 0o660) };
    CRASH_FD.store(fd, Ordering::Relaxed);
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_sigaction = native_signal_handler as extern "C" fn(c_int, *mut libc::siginfo_t, *mut c_void) as usize;
        libc::sigemptyset(&mut sa.sa_mask);
        sa.sa_flags = libc::SA_RESETHAND | libc::SA_SIGINFO;
        for (idx, &sig) in HANDLED_SIGS.iter().enumerate() {
            libc::sigaction(sig, &sa, std::ptr::addr_of_mut!(OLD_ACTIONS[idx]));
        }
    }
}

/// The crate builds with panic = "unwind" so Apple C++ exceptions can be caught (cxx_guard). A Rust
/// panic must still kill the process, exactly as under panic = "abort", and must never unwind into
/// the C++ catch: log it as `[RUST-PANIC]` (message, file:line, thread, active entry point), then
/// abort at the panic itself.
pub fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let msg = if let Some(s) = info.payload().downcast_ref::<&str>() {
            (*s).to_owned()
        } else if let Some(s) = info.payload().downcast_ref::<String>() {
            s.clone()
        } else {
            "(non-string payload)".to_owned()
        };
        let hint = if msg.contains("cannot unwind") {
            " (a C++ exception or panic escaped an extern \"C\"/JNI frame: an Apple call on this thread is not wrapped by cxx_guard; see entry/apple_call)"
        } else {
            ""
        };
        let at = info.location().map_or("unknown location".to_owned(), |l| format!("{}:{}", l.file(), l.line()));
        let (entry, call) = crate::cxx_guard::breadcrumb();
        crate::events::emit(
            crate::events::Level::Error,
            "native",
            &format!(
                "[RUST-PANIC] \"{}\" at {at} on thread {}; entry={} apple_call={}; the process aborts now{hint}",
                crate::logfmt::truncate(&msg, 300),
                crate::events::thread_label(),
                if entry.is_empty() { "-" } else { entry },
                if call.is_empty() { "-" } else { call },
            ),
        );
        std::process::abort()
    }));
}

/// JNI library entry: install crash handlers and announce load, as native_support.cpp did.
#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: *mut c_void, _reserved: *mut c_void) -> c_int {
    install_panic_hook();
    install_crash_handlers();
    // The JVM bridge first, so the hooks below can already report through the app's log.
    crate::events::init_bridge(vm);
    crate::cxx_guard::install_terminate_handler();
    log_info(&format!("JNI_OnLoad reached crash_fd={}", CRASH_FD.load(Ordering::Relaxed)));
    crate::events::emit(
        crate::events::Level::Info,
        "native",
        "[NATIVE-INFO] native layer loaded: C++ exception guard, terminate report, panic report and crash-line hooks are active; native error lines start with [CXX-THROW] [CXX-SKIP] [CXX-TERMINATE] [RUST-PANIC]",
    );
    JNI_VERSION_1_6
}
