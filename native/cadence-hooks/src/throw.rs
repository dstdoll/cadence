//! The `__cxa_throw` replacement. Every C++ `throw` in a patched library lands here first: we note the
//! exception type and where it was thrown, then call the real `__cxa_throw`, which never returns.
//!
//! The wrapper is `extern "C-unwind"` and holds nothing with a destructor across the real call, so the C++
//! unwinder can walk through this frame to the catch (our frame has `.eh_frame` because the crate builds
//! with panic=unwind, which the exception guard already needs). The hook takes no lock, allocates nothing
//! and makes no JNI call: it fills a fixed record and pushes it into the lock-free ring.

use crate::gate::{Gate, FULL};
use crate::record::{Record, FRAMES};
use crate::state;
use std::cell::Cell;
use std::ffi::c_void;
use std::sync::atomic::{AtomicUsize, Ordering};

/// The real `__cxa_throw` (the original PLT target), set when the first slot is patched.
pub(crate) static REAL: AtomicUsize = AtomicUsize::new(0);

thread_local! {
    static BUSY: Cell<bool> = const { Cell::new(false) };
}

/// Frames of this module on the stack when the walk runs: `backtrace`, `note`, the wrapper.
const OWN_FRAMES: usize = 3;

type UnwindCtx = c_void;
extern "C-unwind" {
    fn _Unwind_Backtrace(f: extern "C" fn(*mut UnwindCtx, *mut c_void) -> i32, arg: *mut c_void) -> i32;
    fn _Unwind_GetIP(ctx: *mut UnwindCtx) -> usize;
}

struct Frames {
    pcs: [usize; FRAMES + OWN_FRAMES],
    n: usize,
    want: usize,
}

extern "C" fn collect(ctx: *mut UnwindCtx, arg: *mut c_void) -> i32 {
    // SAFETY: `arg` is the `Frames` passed to `_Unwind_Backtrace` below; `ctx` is valid during the callback.
    let f = unsafe { &mut *(arg as *mut Frames) };
    if f.n >= f.want {
        return 5; // _URC_END_OF_STACK: stop walking
    }
    f.pcs[f.n] = unsafe { _Unwind_GetIP(ctx) };
    f.n += 1;
    0
}

#[inline(never)]
fn backtrace(want: usize) -> Frames {
    let mut f = Frames { pcs: [0; FRAMES + OWN_FRAMES], n: 0, want: want.min(FRAMES + OWN_FRAMES) };
    unsafe { _Unwind_Backtrace(collect, &mut f as *mut Frames as *mut c_void) };
    f
}

/// `std::type_info` is `{ vptr, name }`; libc++ may tag the top bit of `name` (non-unique RTTI).
unsafe fn type_name(tinfo: *const c_void) -> ([u8; 96], usize) {
    let mut out = [0u8; 96];
    if tinfo.is_null() {
        return (out, 0);
    }
    let name = (*(tinfo as *const usize).add(1)) & !(1usize << 63);
    if name == 0 {
        return (out, 0);
    }
    let p = name as *const u8;
    let mut n = 0;
    while n < out.len() {
        let b = *p.add(n);
        if b == 0 {
            break;
        }
        out[n] = b;
        n += 1;
    }
    (out, n)
}

#[inline(never)]
fn note(tinfo: *const c_void) {
    if BUSY.with(|b| b.replace(true)) {
        return;
    }
    if let Some(st) = state() {
        // The walk starts in `backtrace` itself: [0]=backtrace, [1]=note, [2]=the wrapper, [3]=the
        // code that called __cxa_throw (the throw site). Our three frames are skipped.
        let first = backtrace(OWN_FRAMES + 1);
        let site = if first.n > OWN_FRAMES { first.pcs[OWN_FRAMES] } else { 0 };
        let (name, len) = unsafe { type_name(tinfo) };
        let ty = tinfo as usize;
        let count = st.gate.hit(ty, site);
        if count != u32::MAX && count <= FULL {
            let full = backtrace(FRAMES + OWN_FRAMES);
            let frames = if full.n > OWN_FRAMES { &full.pcs[OWN_FRAMES..full.n] } else { &full.pcs[..0] };
            let rec = Record::throw(unsafe { libc::gettid() } as u32, count, &name[..len], frames);
            st.ring.push(rec);
        }
    }
    BUSY.with(|b| b.set(false));
}

/// What patched libraries call instead of `__cxa_throw`.
pub unsafe extern "C-unwind" fn cadence_cxa_throw(obj: *mut c_void, tinfo: *mut c_void, dest: *mut c_void) -> ! {
    note(tinfo);
    let real: unsafe extern "C-unwind" fn(*mut c_void, *mut c_void, *mut c_void) -> ! = std::mem::transmute(REAL.load(Ordering::Relaxed));
    real(obj, tinfo, dest)
}

/// Used by tests and by `state()`: a fresh gate.
pub(crate) fn new_gate() -> Gate {
    Gate::new()
}
