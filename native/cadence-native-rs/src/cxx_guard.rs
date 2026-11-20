//! Catches C++ exceptions thrown by Apple's closed libraries, so a throw skips one step instead of
//! killing the process (`__cxa_throw -> std::terminate -> abort`, seen as a SIGABRT in
//! `SVFootHillSessionCtrl::getPersistentKey`).
//!
//! Rust cannot catch a foreign C++ exception: `catch_unwind` aborts, and Rust's unwinder helpers
//! crash against the 2019 (clang 9) `libc++_shared.so` this app ships, whose unwinder is hidden. So
//! the catch frame is `cadence_try_call` (`cxx_trampoline.s`, below), whose personality and
//! `__cxa_begin_catch`/`__cxa_end_catch` come from the same libc++ that throws. It is the compiled
//! form of:
//!
//! ```cpp
//! extern "C" int cadence_try_call(void (*fn)(void*), void* ctx) {
//!     try { fn(ctx); return 0; } catch (...) { return -1; }
//! }
//! ```
//! (`aarch64-linux-android31-clang++ -S -O2 -fexceptions -fPIC`, then every `.L*`/`GCC_except_table*`
//! label was prefixed `cxxg` so it cannot clash with labels rustc emits under `panic = "unwind"`,
//! and the symbol marked hidden; the `.addrsig`/`.ident`/`.file` lines are dropped.)
//!
//! Hard rules, each one proven on a device (docs: crash-cxx-exception-rust-plan.md):
//! * The crate is built with `panic = "unwind"` and `JNI_OnLoad` installs an abort panic hook, so a
//!   Rust panic still kills the process and is never swallowed by `catch (...)`.
//! * Between `cadence_try_call` and the Apple function there is exactly one Rust frame, the thunk
//!   [`apple_externs!`] generates, and it holds no value with a destructor. A frame with a
//!   `CString`/`Vec`/guard in between runs Rust cleanup code against the wrong unwinder and aborts.
//! * Everything with a destructor stays in the caller, above the catch.
//!
//! Semantics: a wrapped call that throws returns an all-zero value (null/0/false) and *poisons* the
//! thread's current [`Scope`]: every later wrapped call in that scope is skipped and returns zero,
//! so one failed step cannot feed half-built state into the next one. Entry points open a `Scope`,
//! and check [`Scope::threw`] before trusting a result.

use crate::events::{self, Level};
use crate::logfmt;
use std::cell::{Cell, RefCell};
use std::ffi::{c_int, c_void};
use std::sync::atomic::{AtomicU32, Ordering};

pub type Thunk = extern "C-unwind" fn(*mut c_void);

/// Filled in by the catch frame: the thrown type (mangled) and `what()` (empty for non-std types).
#[repr(C)]
pub struct CxxThrow {
    type_name: [u8; 96],
    what: [u8; 224],
}
impl CxxThrow {
    const fn empty() -> CxxThrow {
        CxxThrow { type_name: [0; 96], what: [0; 224] }
    }
    fn text(buf: &[u8]) -> String {
        let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
        String::from_utf8_lossy(&buf[..end]).into_owned()
    }
}

#[cfg(all(target_arch = "aarch64", target_os = "android"))]
std::arch::global_asm!(include_str!("cxx_trampoline.s"));

#[cfg(all(target_arch = "aarch64", target_os = "android"))]
extern "C-unwind" {
    fn cadence_try_call(f: Thunk, ctx: *mut c_void, out: *mut CxxThrow) -> c_int;
}
#[cfg(all(target_arch = "aarch64", target_os = "android"))]
extern "C" {
    fn cadence_install_terminate_handler();
}

/// Host builds (unit tests) have no Apple libraries and nothing to catch.
#[cfg(not(all(target_arch = "aarch64", target_os = "android")))]
unsafe fn cadence_try_call(f: Thunk, ctx: *mut c_void, _out: *mut CxxThrow) -> c_int {
    f(ctx);
    0
}

/// Makes `std::terminate` (a C++ exception nobody can catch) log what was thrown before the process
/// aborts. Call once, from `JNI_OnLoad`.
pub fn install_terminate_handler() {
    #[cfg(all(target_arch = "aarch64", target_os = "android"))]
    unsafe {
        cadence_install_terminate_handler()
    }
}

struct ScopeState {
    depth: u32,
    threw: bool,
    /// Wrapped calls skipped after the first throw in this scope.
    skipped: u32,
    /// The Apple call that threw first.
    first: &'static str,
    label: &'static str,
    detail: String,
}
thread_local! {
    static STATE: RefCell<ScopeState> = const {
        RefCell::new(ScopeState { depth: 0, threw: false, skipped: 0, first: "", label: "", detail: String::new() })
    };
    /// (entry point, Apple call) currently running on this thread, for the fatal-signal line and the
    /// terminate report. A plain const thread-local: readable from a signal handler.
    static CRUMB: Cell<(&'static str, &'static str)> = const { Cell::new(("", "")) };
}
static THROWS: AtomicU32 = AtomicU32::new(0);

/// Total C++ exceptions caught since the process started.
#[allow(dead_code)]
pub fn throw_count() -> u32 {
    THROWS.load(Ordering::Relaxed)
}

/// The entry point and Apple call running on this thread (empty strings when none). Safe to call
/// from a signal handler on the crashing thread.
pub fn breadcrumb() -> (&'static str, &'static str) {
    CRUMB.try_with(|c| c.get()).unwrap_or(("", ""))
}

/// One entry point's span of Apple calls, named so every log line says where it happened. Nested
/// scopes share the outer one's name and state; the outermost clears it when it opens and when it
/// closes, so a throw never leaks into the next entry point on a thread.
pub struct Scope(());
impl Scope {
    /// `label`: the entry point (`"kd"`, `"m3u8"`, `"login"`); `detail`: what it was working on
    /// (`"adam=1595217258 key=c23"`), empty on hot paths (nothing is allocated for an empty one).
    pub fn named(label: &'static str, detail: &str) -> Scope {
        STATE.with(|s| {
            let mut s = s.borrow_mut();
            if s.depth == 0 {
                s.threw = false;
                s.skipped = 0;
                s.first = "";
                s.label = label;
                s.detail.clear();
                s.detail.push_str(detail);
                CRUMB.with(|c| c.set((label, "")));
            }
            s.depth += 1;
        });
        Scope(())
    }
    /// True once any wrapped call in this thread's current scope threw.
    pub fn threw(&self) -> bool {
        STATE.with(|s| s.borrow().threw)
    }
}
impl Drop for Scope {
    fn drop(&mut self) {
        let summary = STATE.with(|s| {
            let mut s = s.borrow_mut();
            s.depth = s.depth.saturating_sub(1);
            if s.depth > 0 {
                return None;
            }
            CRUMB.with(|c| c.set(("", "")));
            let out = (s.threw && s.skipped > 0).then(|| (s.label, s.detail.clone(), s.first, s.skipped));
            s.threw = false;
            s.skipped = 0;
            out
        });
        if let Some((label, detail, first, skipped)) = summary {
            events::emit(
                Level::Error,
                "native",
                &format!(
                    "[CXX-SKIP] entry={label}{} aborted: {skipped} later Apple call(s) were skipped after {first} threw; the entry point reports failure",
                    if detail.is_empty() { String::new() } else { format!(" ({detail})") }
                ),
            );
        }
    }
}

/// True when a wrapped call already threw in this thread's current scope.
pub fn threw() -> bool {
    STATE.with(|s| s.borrow().threw)
}

/// Run `thunk(ctx)` under the catch. Returns false if the call was skipped (an earlier call in this
/// scope threw) or threw now. `name` is the Rust-side name and `addr` the address of the Apple
/// function (for its real C++ name in the log). Used by [`apple_externs!`] and by the hand-written
/// thunk for the calls made through inline asm.
pub fn run(name: &'static str, addr: usize, thunk: Thunk, ctx: *mut c_void) -> bool {
    let skip = STATE.with(|s| {
        let mut s = s.borrow_mut();
        if s.depth == 0 {
            s.threw = false; // no scope open: this call is its own scope
            false
        } else if s.threw {
            s.skipped += 1;
            true
        } else {
            false
        }
    });
    if skip {
        return false;
    }
    CRUMB.with(|c| c.set((c.get().0, name)));
    let mut info = CxxThrow::empty();
    // SAFETY: `thunk` is an `extern "C-unwind"` fn that only reads plain data from `ctx`.
    let caught = unsafe { cadence_try_call(thunk, ctx, &mut info) };
    CRUMB.with(|c| c.set((c.get().0, "")));
    if caught != 0 {
        report_throw(name, addr, &info);
        return false;
    }
    true
}

/// `" (adam=1 key=c23)"` -> `"adam=1 key=c23"`.
fn detail_plain(d: &str) -> String {
    d.trim().trim_start_matches('(').trim_end_matches(')').to_owned()
}

/// Records the throw in the scope and writes the `[CXX-THROW]` line (see `events.rs`).
fn report_throw(name: &'static str, addr: usize, info: &CxxThrow) {
    let (label, detail) = STATE.with(|s| {
        let mut s = s.borrow_mut();
        s.threw = true;
        if s.first.is_empty() {
            s.first = name;
        }
        (s.label, s.detail.clone())
    });
    let n = THROWS.fetch_add(1, Ordering::Relaxed) + 1;
    let ty = events::readable_type(&CxxThrow::text(&info.type_name));
    let what_raw = CxxThrow::text(&info.what);
    let class = crate::apple_errors::classify(label, name, &ty, &what_raw);
    let meaning = crate::apple_errors::describe(&class, &ty);
    let impact = match class.impact {
        crate::apple_errors::Impact::None => "none",
        crate::apple_errors::Impact::Track => "track",
        crate::apple_errors::Impact::Session => "session",
        crate::apple_errors::Impact::App => "app",
    };
    let what = if what_raw.is_empty() { String::new() } else { format!(": \"{}\"", logfmt::truncate(&what_raw, 200)) };
    let apple = match events::apple_symbol(addr) {
        Some(sym) if sym != name => format!("{sym} [{name}]"),
        _ => name.to_owned(),
    };
    let entry = if label.is_empty() { "(no entry point open)".to_owned() } else { label.to_owned() };
    let detail = if detail.is_empty() { String::new() } else { format!(" ({detail})") };
    events::emit(
        Level::Error,
        "native",
        &format!(
            "[CXX-THROW] class={} impact={impact} entry={entry}{detail} apple_call={apple} threw {ty}{what} on thread {}; caught, call skipped, entry point reports failure (throw #{n} this run). Meaning: {meaning}",
            class.code,
            events::thread_label()
        ),
    );
    if class.impact >= crate::apple_errors::Impact::Track {
        events::notice(class.code, impact, &entry, &detail_plain(&detail));
    }
}

/// `-> T` or nothing (`()`), for the macro below.
macro_rules! ret_ty {
    () => { () };
    ($t:ty) => { $t };
}

/// Declares Apple C++ functions and generates, for each, a same-named `unsafe fn` that calls it
/// under the catch (see the module docs). Argument and return types must be plain data (pointers,
/// integers, `repr(C)` structs of those): a const assertion rejects any argument struct that needs
/// drop, and a throw makes the wrapper return `mem::zeroed()`.
macro_rules! apple_externs {
    ($( $(#[$m:meta])* $vis:vis fn $name:ident ( $($a:ident : $t:ty),* $(,)? ) $(-> $r:ty)? ; )*) => {
        mod raw {
            #[allow(unused_imports)]
            use super::*;
            extern "C-unwind" {
                $( $(#[$m])* pub fn $name( $($a: $t),* ) $(-> $r)?; )*
            }
        }
        $(
            #[allow(clippy::too_many_arguments, non_snake_case)]
            $vis unsafe fn $name( $($a: $t),* ) -> ret_ty!($($r)?) {
                #[repr(C)]
                struct Args { $($a: $t,)* __ret: ::std::mem::MaybeUninit<ret_ty!($($r)?)> }
                const _: () = assert!(!::std::mem::needs_drop::<Args>());
                // The only Rust frame between the catch and the Apple function: no locals with
                // destructors, no temporaries that need cleanup.
                extern "C-unwind" fn thunk(p: *mut ::std::ffi::c_void) {
                    let a = unsafe { &mut *(p as *mut Args) };
                    a.__ret = ::std::mem::MaybeUninit::new(unsafe { raw::$name( $(a.$a),* ) });
                }
                let mut args = Args { $($a,)* __ret: ::std::mem::MaybeUninit::uninit() };
                if $crate::cxx_guard::run(stringify!($name), raw::$name as *const () as usize, thunk, &mut args as *mut Args as *mut ::std::ffi::c_void) {
                    args.__ret.assume_init()
                } else {
                    ::std::mem::zeroed()
                }
            }
        )*
    };
}
