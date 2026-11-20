//! On-device harness for the C++ exception guard (see native/selftest-cxx-guard.sh). Loads the
//! stripped libcadence-native.so built with `--features selftest` exactly as `System.loadLibrary`
//! would (dlopen RTLD_NOW) and drives the `cadence_selftest_*` exports. It does not call JNI_OnLoad
//! (that opens the app's real crash log); `cadence_selftest_install_hooks` installs the same hooks
//! with the crash log pointed at a temp file.
use std::ffi::{c_char, c_int, c_void, CString};
use std::os::unix::process::ExitStatusExt;

extern "C" {
    fn dlopen(path: *const c_char, flags: c_int) -> *mut c_void;
    fn dlsym(h: *mut c_void, name: *const c_char) -> *mut c_void;
    fn dlerror() -> *const c_char;
}
type F = extern "C" fn() -> c_int;

fn rss_kb() -> u64 {
    std::fs::read_to_string("/proc/self/statm")
        .ok()
        .and_then(|s| s.split_whitespace().nth(1).and_then(|v| v.parse::<u64>().ok()))
        .unwrap_or(0)
        * 4
}
fn sym(h: *mut c_void, n: &str) -> F {
    let c = CString::new(n).unwrap();
    let p = unsafe { dlsym(h, c.as_ptr()) };
    if p.is_null() {
        eprintln!("FAIL missing export {n}");
        std::process::exit(3)
    }
    unsafe { std::mem::transmute(p) }
}
fn load(path: &str) -> *mut c_void {
    let c = CString::new(path).unwrap();
    let h = unsafe { dlopen(c.as_ptr(), 2) };
    if h.is_null() {
        let e = unsafe { std::ffi::CStr::from_ptr(dlerror()) };
        eprintln!("FAIL dlopen {path}: {e:?}");
        std::process::exit(2)
    }
    h
}

fn event(h: *mut c_void, n: c_int) -> Option<String> {
    let f: extern "C" fn(c_int, *mut c_char, c_int) -> c_int = unsafe { std::mem::transmute(sym_raw(h, "cadence_selftest_event")) };
    let mut buf = vec![0u8; 1024];
    let len = f(n, buf.as_mut_ptr() as *mut c_char, buf.len() as c_int);
    if len < 0 { None } else { Some(String::from_utf8_lossy(&buf[..len as usize]).into_owned()) }
}
fn sym_raw(h: *mut c_void, n: &str) -> *mut c_void {
    let c = CString::new(n).unwrap();
    let p = unsafe { dlsym(h, c.as_ptr()) };
    if p.is_null() {
        eprintln!("FAIL missing export {n}");
        std::process::exit(3)
    }
    p
}
fn install(h: *mut c_void, crash_log: &str) {
    let f: extern "C" fn(*const c_char) -> c_int = unsafe { std::mem::transmute(sym_raw(h, "cadence_selftest_install_hooks")) };
    let p = CString::new(crash_log).unwrap();
    f(p.as_ptr());
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let crash_log = format!("{}.crash.log", a[1]);
    let h = load(&a[1]);
    // Child modes: each must kill the process, and leaves its evidence on stderr / in the crash log.
    match a.get(2).map(String::as_str) {
        Some("panic") => { install(h, &crash_log); let r = sym(h, "cadence_selftest_panic")(); println!("FAIL panic returned {r}"); return; }
        Some("crash") => { install(h, &crash_log); let r = sym(h, "cadence_selftest_crash")(); println!("FAIL crash returned {r}"); return; }
        Some("uncaught") => {
            // a thread with only C++ frames (like Apple's own): no scope, no guard; std::terminate
            // must report, then abort
            install(h, &crash_log);
            let r = sym(h, "cadence_selftest_uncaught")();
            println!("FAIL uncaught returned {r}");
            return;
        }
        _ => {}
    }
    let (throw, poison, ok, asm, count) = (
        sym(h, "cadence_selftest_throw"),
        sym(h, "cadence_selftest_poison"),
        sym(h, "cadence_selftest_ok"),
        sym(h, "cadence_selftest_asm_throw"),
        sym(h, "cadence_selftest_throw_count"),
    );
    let (t_int, t_custom, t_msg) = (sym(h, "cadence_selftest_throw_int"), sym(h, "cadence_selftest_throw_custom"), sym(h, "cadence_selftest_throw_message"));
    let mut fails = 0;
    let mut check = |name: &str, good: bool, detail: String| {
        println!("{} {name}: {detail}", if good { "PASS" } else { "FAIL" });
        if !good { fails += 1 }
    };

    // --- what the log line says (nothing here may need a JVM: the ring buffer holds the events)
    let r = (throw(), t_int(), t_custom(), t_msg());
    check("each thrown kind is caught", r == (-1, -1, -1, -1), format!("{r:?}"));
    let msg = event(h, 0).unwrap_or_default();
    let int = event(h, 2).unwrap_or_default();
    let custom = event(h, 1).unwrap_or_default();
    let std_line = event(h, 3).unwrap_or_default();
    println!("INFO sample std line:    {std_line}");
    println!("INFO sample custom line: {custom}");
    println!("INFO sample int line:    {int}");
    println!("INFO sample message line:{msg}");
    check("line: code, entry, detail, apple call, type, what, thread",
        std_line.starts_with("[CXX-THROW] class=SELFTEST impact=none entry=selftest.throw (case=std) apple_call=cpp_throw threw std::runtime_error: \"boom\" on thread ")
            && std_line.contains("caught, call skipped, entry point reports failure"),
        "std::runtime_error".into());
    check("line: non-std exception types are named", custom.contains("threw 14NotAStdException") || custom.contains("threw NotAStdException"), "custom class".into());
    check("line: a thrown int is named", int.contains("threw int"), "int".into());
    check("line: secrets in what() are scrubbed (logcat path)", !msg.contains("SECRETSECRET") && !msg.contains("alice.smith") && msg.contains("?<redacted>"), "token + e-mail".into());

    // Everything below runs on a worker thread, like the kd call does.
    let (n_throw, bad, before, after, ns, poison_ok, asm_bad, counted) = std::thread::spawn(move || {
        let c0 = count();
        for _ in 0..5000 { throw(); }
        let before = rss_kb();
        let mut bad = 0u32;
        let n = 200_000;
        for _ in 0..n {
            if throw() != -1 { bad += 1 }
            if ok() != 5 { bad += 1 } // a fresh scope after a throw must work again
        }
        let after = rss_kb();
        let t = std::time::Instant::now();
        for _ in 0..2_000_000 { std::hint::black_box(ok()); }
        let ns = t.elapsed().as_nanos() as f64 / 2e6;
        let poison_ok = (0..1000).all(|_| poison() == 1);
        let asm_bad = (0..1000).filter(|_| asm() != -1).count();
        let counted = count() - c0;
        (n, bad, before, after, ns, poison_ok, asm_bad, counted)
    }).join().unwrap();

    check("caught throws (separate .so, production thunk)", bad == 0, format!("{n_throw} iterations, {bad} wrong results"));
    check("memory flat", after <= before + 256, format!("RSS {before} -> {after} KB"));
    check("scope poisoning (2nd call skipped, next scope works)", poison_ok, "1000 rounds".into());
    check("inline-asm thunk path", asm_bad == 0, format!("1000 calls, {asm_bad} wrong"));
    check("throw counter", counted == 5000 + n_throw as c_int + 1000 + 1000, format!("{counted} caught"));
    println!("INFO guarded non-throwing call: {ns:.1} ns");
    poison();
    let skip = event(h, 0).unwrap_or_default();
    println!("INFO skip line:    {skip}");
    check("skipped calls are reported", skip.starts_with("[CXX-SKIP] entry=selftest.poison aborted: 1 later Apple call(s) were skipped after cpp_throw threw"), skip.chars().take(100).collect());

    // --- the process-killing cases: each runs in a child so this process survives
    install(h, &crash_log);
    let me = std::env::current_exe().unwrap();
    let run = |mode: &str| std::process::Command::new(&me).args([&a[1], mode]).env("CADENCE_NATIVE_EVENTS_STDERR", "1").output().unwrap();

    let p = run("panic");
    let pe = String::from_utf8_lossy(&p.stderr).into_owned();
    println!("INFO panic line:    {}", pe.lines().find(|l| l.contains("[RUST-PANIC]")).unwrap_or(""));
    check("Rust panic: aborts and says why", p.status.signal() == Some(6)
        && pe.contains("[RUST-PANIC] \"selftest: a Rust panic must abort\" at ")
        && pe.contains("selftest.rs:") && pe.contains("entry=selftest.panic"), format!("{:?}", p.status));

    let u = run("uncaught");
    let ue = String::from_utf8_lossy(&u.stderr).into_owned();
    println!("INFO terminate line:{}", ue.lines().find(|l| l.contains("[CXX-TERMINATE]")).unwrap_or(""));
    check("uncaught C++ exception on another thread: reported, then aborts", u.status.signal() == Some(6)
        && ue.contains("[CXX-TERMINATE] uncaught C++ exception std::runtime_error: \"boom\" on thread ")
        && ue.contains("no native entry point of ours was active"), format!("{:?}", u.status));

    let _ = std::fs::remove_file(&crash_log);
    let c = run("crash");
    let log = std::fs::read_to_string(&crash_log).unwrap_or_default();
    println!("INFO crash line:   {}", log.trim());
    check("fatal signal: crash line names the Apple call", c.status.signal() == Some(11)
        && log.contains("=== NATIVE CRASH SIGSEGV (signal 11) fault=null tid=")
        && log.contains("entry=selftest.crash apple_call=cpp_crash ==="), format!("{:?}", c.status));
    let _ = std::fs::remove_file(&crash_log);

    if fails > 0 { println!("RESULT: {fails} FAILED"); std::process::exit(1) }
    println!("RESULT: all passed");
}
