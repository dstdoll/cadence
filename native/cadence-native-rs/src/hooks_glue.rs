//! Platform glue for `cadence-hooks`: Android's liblog logger and the install sequence run from `JNI_OnLoad`.
//!
//! * `__android_log_set_logger` (API 30; minSdk is 31) hands every liblog message of the process to
//!   [`our_logger`], which first calls liblog's default logger (logcat is unchanged) and then gives the message
//!   to `cadence_hooks::on_log` (a lock-free push; no JNI here, the drain thread does that).
//! * `__cxa_throw` is patched in the libraries that import it (see docs: 6 libraries, JUMP_SLOT relocations).
//! * `files/hooks.off` (an empty file) turns both off at the next start.

use crate::events::{self, Level};
use cadence_hooks::{plt, Output};
use std::ffi::c_char;
use std::sync::atomic::{AtomicUsize, Ordering};

/// `struct __android_log_message` from `<android/log.h>` (API 30).
#[repr(C)]
struct AndroidLogMessage {
    struct_size: usize,
    buffer_id: i32,
    priority: i32,
    tag: *const c_char,
    file: *const c_char,
    line: u32,
    message: *const c_char,
}

type SetLoggerFn = unsafe extern "C" fn(extern "C" fn(*const AndroidLogMessage));
type LogdLoggerFn = unsafe extern "C" fn(*const AndroidLogMessage);

/// liblog's default logger (`__android_log_logd_logger`), found at run time: both functions exist from
/// Android 11 (API 30) only. A hard import would make the whole library fail to load on Android 10.
static LOGD_LOGGER: AtomicUsize = AtomicUsize::new(0);

fn lookup(name: &std::ffi::CStr) -> usize {
    // SAFETY: dlsym with RTLD_DEFAULT and a NUL-terminated name.
    unsafe { libc::dlsym(libc::RTLD_DEFAULT, name.as_ptr()) as usize }
}

/// Every library that imports `__cxa_throw` through its PLT (checked with readelf and by
/// `cadence-hooks/tests/real_libs.rs`).
const THROW_LIBS: [&str; 6] = [
    "libandroidappmusic.so", "libstoreservicescore.so", "libmediaplatform.so",
    "libmedialibrarycore.so", "libdaapkit.so", "libc++_shared.so",
];

extern "C" fn our_logger(message: *const AndroidLogMessage) {
    // SAFETY: liblog passes a valid message for the duration of the call.
    unsafe {
        let logd = LOGD_LOGGER.load(Ordering::Relaxed);
        if logd != 0 {
            // first: logcat must behave exactly as without the hook
            std::mem::transmute::<usize, LogdLoggerFn>(logd)(message);
        }
        if message.is_null() {
            return;
        }
        let m = &*message;
        // an older struct would not have the message field: do not read past what liblog wrote
        if m.struct_size < std::mem::size_of::<AndroidLogMessage>() {
            return;
        }
        cadence_hooks::on_log(m.priority, m.tag, m.message);
    }
}

fn hooks_off() -> bool {
    // JNI_OnLoad runs before the app hands us its directory: derive it from the process name (the package).
    let Ok(cmd) = std::fs::read("/proc/self/cmdline") else { return false };
    let pkg = String::from_utf8_lossy(&cmd).split('\0').next().unwrap_or("").to_string();
    if pkg.is_empty() {
        return false;
    }
    ["/data/user/0", "/data/data"].iter().any(|root| std::path::Path::new(&format!("{root}/{pkg}/files/hooks.off")).exists())
}

pub fn install() {
    let _ = std::panic::catch_unwind(|| {
        if hooks_off() {
            events::emit(Level::Info, "native", "[HOOK] logger=off throw=off (files/hooks.off exists)");
            return;
        }
        cadence_hooks::init();
        // The liblog logger API is Android 11+; on Android 10 the library still loads and only this hook is off.
        let (set_logger, logd) = (lookup(c"__android_log_set_logger"), lookup(c"__android_log_logd_logger"));
        let logger = if set_logger != 0 && logd != 0 {
            LOGD_LOGGER.store(logd, Ordering::SeqCst);
            cadence_hooks::set_logging(true);
            // SAFETY: `set_logger` is liblog's function of that name; `our_logger` has the signature it expects and lives for the process.
            unsafe { std::mem::transmute::<usize, SetLoggerFn>(set_logger)(our_logger) };
            "on".to_string()
        } else {
            "off (liblog has no __android_log_set_logger before Android 11)".to_string()
        };
        let report = cadence_hooks::install_throw_hook(&THROW_LIBS, plt::JUMP_SLOT);
        cadence_hooks::spawn_drain(|out| match out {
            Output::Apple { tag, line } => events::apple_log(&tag, &line),
            Output::Event(line) => events::emit(Level::Info, "native", &line),
        });
        let throw = if report.patched.is_empty() && report.already == 0 {
            format!("off ({})", report.skipped.first().map(|(l, r)| format!("{l}: {r}")).unwrap_or_else(|| "no slot".into()))
        } else {
            format!("on slots={} already={}", report.patched.len(), report.already)
        };
        let missing = if report.missing.is_empty() { String::new() } else { format!(" missing={}", report.missing.join(",")) };
        // Kotlin reads the "logger=on" prefix to stop its own logcat follower (this replaces it).
        events::emit(Level::Info, "native", &format!("[HOOK] logger={logger} throw={throw}{missing}"));
    });
}

