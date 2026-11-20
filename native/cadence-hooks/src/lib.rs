//! Diagnostics hooks for the Apple libraries, kept free of JNI so the real mechanics run in host tests.
//!
//! * a liblog capture: the platform glue gives every log message of the process to [`on_log`], which keeps the
//!   Apple/native lines and warnings;
//! * a `__cxa_throw` hook ([`install_throw_hook`]) that records every C++ throw (type + call site) inside Apple's
//!   libraries, including exceptions Apple catches itself;
//! * a drain thread ([`start_drain`]) that turns the records into lines for the app log, rate limited.
//!
//! Hooks never lock, allocate or call into the JVM: they push fixed records into a lock-free ring.

pub mod gate;
pub mod plt;
pub mod record;
pub mod ring;
pub mod throttle;
mod throw;

use record::Record;
use std::ffi::c_char;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

pub const RING_CAPACITY: usize = 512;

pub(crate) struct State {
    pub ring: ring::Ring<Record>,
    pub gate: gate::Gate,
}

static STATE: OnceLock<State> = OnceLock::new();
static LOGGING: AtomicBool = AtomicBool::new(false);

pub(crate) fn state() -> Option<&'static State> {
    STATE.get()
}

/// Creates the ring and gate (once). Hooks do nothing before this.
pub fn init() {
    STATE.get_or_init(|| State { ring: ring::Ring::new(RING_CAPACITY), gate: throw::new_gate() });
}

pub fn dropped() -> usize {
    state().map_or(0, |s| s.ring.dropped())
}

pub fn set_logging(on: bool) {
    LOGGING.store(on, Ordering::Relaxed);
}

const APPLE_WORDS: &[&str] = &[
    "fairplay", "sverror", "svfoot", "storeservices", "cadence-native", "ckc", "coreadi", "mediaplatform",
    "androidappmusic", "svplayback", "lease",
];

fn contains_ci(hay: &[u8], needle: &str) -> bool {
    let n = needle.as_bytes();
    hay.len() >= n.len() && hay.windows(n.len()).any(|w| w.eq_ignore_ascii_case(n))
}

/// The same rule the app side uses: warnings/errors of any tag, Apple/native tags at any priority;
/// never our own diagnostics (`cadence-*`, `Cadence`), which would loop back into the log.
pub fn accepts_log(prio: u8, tag: &[u8], msg: &[u8]) -> bool {
    if tag == b"Cadence" || tag.starts_with(b"cadence-") && !tag.eq_ignore_ascii_case(b"cadence-native") {
        return false;
    }
    if tag.starts_with(b"cadence-") {
        // our own native lines are already in cadence.log via the event channel
        return false;
    }
    prio >= 5 || APPLE_WORDS.iter().any(|w| contains_ci(tag, w) || contains_ci(msg, w))
}

fn bytes_of<'a>(p: *const c_char, max: usize) -> &'a [u8] {
    if p.is_null() {
        return &[];
    }
    let mut n = 0;
    // SAFETY: the platform passes NUL-terminated strings; reads stop at the first NUL or `max`.
    unsafe {
        while n < max && *p.add(n) != 0 {
            n += 1;
        }
        std::slice::from_raw_parts(p as *const u8, n)
    }
}

/// Called by the platform logger glue for every liblog message. Null-safe, lock-free, never panics.
///
/// # Safety
/// `tag` and `msg` are null or NUL-terminated strings valid for the call.
pub unsafe fn on_log(prio: i32, tag: *const c_char, msg: *const c_char) {
    if !LOGGING.load(Ordering::Relaxed) {
        return;
    }
    let Some(st) = state() else { return };
    let (tag, msg) = (bytes_of(tag, record::TAG_MAX), bytes_of(msg, record::MSG_MAX));
    let prio = prio.clamp(0, 255) as u8;
    if !accepts_log(prio, tag, msg) {
        return;
    }
    st.ring.push(Record::log(prio, libc::gettid() as u32, tag, msg));
}

pub use throw::cadence_cxa_throw;

/// Result of [`install_throw_hook`].
pub use plt::PatchReport;

/// Patches `__cxa_throw` in the named loaded libraries. The real function is the original PLT target of the
/// first slot found (all of them resolve to the same C++ runtime). Nothing is patched if none is found.
pub fn install_throw_hook(libs: &[&str], slot_type: u32) -> PatchReport {
    init();
    let wrapper = cadence_cxa_throw as *const () as usize;
    // Read the current targets first, without writing. A slot that already points at the wrapper is
    // already hooked: it must never become the "real" function (that would recurse forever).
    let probe = plt::patch_loaded_dry(&["__cxa_throw"], libs, slot_type);
    let Some(original) = probe.iter().map(|(_, _, _, o)| *o).find(|&o| o != wrapper) else {
        let already = probe.len();
        let reason = if already > 0 { "already hooked" } else { "no __cxa_throw PLT slot in the requested libraries" };
        return PatchReport { already, skipped: if already > 0 { Vec::new() } else { vec![("*".into(), reason.into())] }, ..Default::default() };
    };
    throw::REAL.store(original, Ordering::SeqCst);
    plt::patch_loaded(&["__cxa_throw"], wrapper, libs, slot_type)
}

mod drain;
pub use drain::{format_throw, spawn_drain, drain_once, Output, Drain};

/// Test hook: the counted-only throws the gate would report (see [`gate::Gate::pending`]).
pub fn pending_for_test(last: &mut [u32; gate::Gate::size()]) -> Vec<(usize, usize, u32)> {
    state().map(|s| s.gate.pending(last)).unwrap_or_default()
}
