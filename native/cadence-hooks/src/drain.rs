//! Turns ring records into lines for the app log. Runs on its own thread, so it may allocate, read /proc
//! and call into the JVM through the injected sink; hooks never do.

use crate::gate::FULL;
use crate::record::{prio_letter, Kind, Record};
use crate::throttle::{line_key, Throttle};
use crate::{state, throw};
use std::ffi::CStr;
use std::time::{Duration, Instant};

/// What the drain hands to the platform.
#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Output {
    /// A line captured from liblog: (tag, "D t4300 message").
    Apple { tag: String, line: String },
    /// A line for the native event channel (`[CXX-RAISE] ...`, notes).
    Event(String),
}

/// `lib.so+0xoff` for an address inside a loaded library, else the bare address.
fn locate(addr: usize) -> String {
    let mut info: libc::Dl_info = unsafe { std::mem::zeroed() };
    if unsafe { libc::dladdr(addr as *const _, &mut info) } != 0 && !info.dli_fname.is_null() {
        let path = unsafe { CStr::from_ptr(info.dli_fname) }.to_string_lossy().into_owned();
        let base = path.rsplit('/').next().unwrap_or(&path).to_string();
        return format!("{base}+{:#x}", addr.saturating_sub(info.dli_fbase as usize));
    }
    format!("{addr:#x}")
}

fn thread_name(tid: u32) -> String {
    std::fs::read_to_string(format!("/proc/self/task/{tid}/comm")).map(|s| s.trim().to_string()).unwrap_or_else(|_| format!("tid {tid}"))
}

/// `[CXX-RAISE] type=<mangled> x<n> at <lib+off> <- <lib+off> ... (thread <name>)`
pub fn format_throw(r: &Record) -> String {
    let frames: Vec<String> = r.frames().iter().map(|&a| locate(a)).collect();
    let (at, rest) = match frames.split_first() {
        Some((first, rest)) => (first.clone(), rest.join(" <- ")),
        None => ("unknown site".to_string(), String::new()),
    };
    let rest = if rest.is_empty() { String::new() } else { format!(" <- {rest}") };
    format!("[CXX-RAISE] type={} x{} at {at}{rest} (thread {})", r.msg(), r.count, thread_name(r.tid))
}

pub struct Drain {
    throttle: Throttle,
    last_counts: [u32; crate::gate::Gate::size()],
    last_summary: Instant,
    started: Instant,
}

impl Drain {
    pub fn new() -> Drain {
        Drain { throttle: Throttle::new(25), last_counts: [0; crate::gate::Gate::size()], last_summary: Instant::now(), started: Instant::now() }
    }
}

impl Default for Drain {
    fn default() -> Self {
        Self::new()
    }
}

/// Empties the ring once, forwarding what survives the throttle. Returns how many records were read.
pub fn drain_once(d: &mut Drain, sink: &mut dyn FnMut(Output)) -> usize {
    let Some(st) = state() else { return 0 };
    let mut n = 0;
    while let Some(rec) = st.ring.pop() {
        n += 1;
        match rec.kind {
            Kind::Throw => sink(Output::Event(format_throw(&rec))),
            Kind::Log => {
                let (tag, msg) = (rec.tag(), rec.msg());
                let now = d.started.elapsed().as_millis() as u64;
                let v = d.throttle.feed(now, line_key(&tag, &msg));
                if let Some(c) = v.dropped_note {
                    sink(Output::Event(format!("[HOOK] {c} Apple/native log line(s) dropped in the last second (rate limit)")));
                }
                if let Some(c) = v.repeat_note {
                    sink(Output::Apple { tag: "logcat".into(), line: format!("(previous line repeated {c} more time(s))") });
                }
                if v.emit {
                    sink(Output::Apple { tag, line: format!("{} t{} {}", prio_letter(rec.prio), rec.tid, msg) });
                }
            }
        }
    }
    if d.last_summary.elapsed() >= Duration::from_secs(10) {
        d.last_summary = Instant::now();
        for (ty, site, new) in st.gate.pending(&mut d.last_counts) {
            let name = type_name_of(ty);
            sink(Output::Event(format!("[CXX-RAISE] summary: type={name} at {} thrown {new} more time(s) (first {FULL} were logged in full)", locate(site))));
        }
        let lost = st.ring.dropped();
        if lost > 0 {
            sink(Output::Event(format!("[HOOK] {lost} record(s) lost so far because the hook queue was full")));
        }
    }
    n
}

fn type_name_of(tinfo: usize) -> String {
    if tinfo == 0 {
        return "?".into();
    }
    // SAFETY: `tinfo` is a type_info address recorded by the throw hook; type_info objects live as long as their library.
    let name = unsafe { (*(tinfo as *const usize).add(1)) & !(1usize << 63) };
    if name == 0 {
        return "?".into();
    }
    unsafe { CStr::from_ptr(name as *const _) }.to_string_lossy().chars().take(96).collect()
}

/// Starts the drain thread: wakes every 150 ms, forwards through `sink`. Never panics out of the thread.
pub fn spawn_drain(mut sink: impl FnMut(Output) + Send + 'static) {
    let _ = throw::REAL.load(std::sync::atomic::Ordering::Relaxed);
    let _ = std::thread::Builder::new().name("cadence-hooks".into()).spawn(move || {
        let mut d = Drain::new();
        loop {
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| drain_once(&mut d, &mut sink)));
            std::thread::sleep(Duration::from_millis(150));
        }
    });
}
