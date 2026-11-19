//! Library support ported from native_support.cpp: JNI_OnLoad and the native crash handler. These
//! were the last non-ABI C++; everything here is plain Rust over libc. The std::function lease
//! callbacks live in `cppfn`.

use std::ffi::{c_char, c_int, c_void, CString};
use std::sync::atomic::{AtomicI32, Ordering};

extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

static CRASH_FD: AtomicI32 = AtomicI32::new(-1);

const JNI_VERSION_1_6: c_int = 0x0001_0006;

fn log_info(msg: &str) {
    if let Ok(c) = CString::new(msg) {
        let tag = CString::new("cadence-native").unwrap();
        unsafe { __android_log_write(4, tag.as_ptr(), c.as_ptr()) };
    }
}

extern "C" fn native_signal_handler(sig: c_int) {
    let name = match sig {
        libc::SIGSEGV => "SIGSEGV\0",
        libc::SIGABRT => "SIGABRT\0",
        libc::SIGBUS => "SIGBUS\0",
        libc::SIGILL => "SIGILL\0",
        _ => "SIGFPE\0",
    };
    let fd = CRASH_FD.load(Ordering::Relaxed);
    if fd >= 0 {
        // async-signal-safe: a fixed preamble + the signal name, no allocation.
        let pre = b"\n=== NATIVE CRASH ";
        unsafe {
            libc::write(fd, pre.as_ptr() as *const c_void, pre.len());
            libc::write(fd, name.as_ptr() as *const c_void, name.len() - 1);
            libc::write(fd, b" ===\n".as_ptr() as *const c_void, 5);
        }
    }
    let tag = b"cadence-native\0";
    let msg = b"native crash\0";
    unsafe {
        __android_log_write(6 /*FATAL*/, tag.as_ptr() as *const c_char, msg.as_ptr() as *const c_char);
        libc::signal(sig, libc::SIG_DFL);
        libc::raise(sig);
    }
}

fn install_crash_handlers() {
    let path = CString::new("/sdcard/Android/data/app.cadence/files/logs/native-crash.log").unwrap();
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_CREAT | libc::O_WRONLY | libc::O_APPEND, 0o660) };
    CRASH_FD.store(fd, Ordering::Relaxed);
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_sigaction = native_signal_handler as extern "C" fn(c_int) as usize;
        libc::sigemptyset(&mut sa.sa_mask);
        sa.sa_flags = libc::SA_RESETHAND;
        for sig in [libc::SIGSEGV, libc::SIGABRT, libc::SIGBUS, libc::SIGILL, libc::SIGFPE] {
            libc::sigaction(sig, &sa, std::ptr::null_mut());
        }
    }
}

/// JNI library entry: install crash handlers and announce load, as native_support.cpp did.
#[no_mangle]
pub extern "C" fn JNI_OnLoad(_vm: *mut c_void, _reserved: *mut c_void) -> c_int {
    install_crash_handlers();
    log_info(&format!("JNI_OnLoad reached crash_fd={}", CRASH_FD.load(Ordering::Relaxed)));
    JNI_VERSION_1_6
}
