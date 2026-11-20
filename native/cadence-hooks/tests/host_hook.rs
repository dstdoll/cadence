//! The real mechanics on a real C++ library: patch its `__cxa_throw` PLT slot, throw through the wrapper,
//! check the C++ catch still works and the hook saw the throw. (x86_64 glibc here; arm64/bionic only
//! differs in the relocation constant and is covered by `real_libs.rs` + the on-device selftest.)
#![cfg(target_os = "linux")]
use cadence_hooks::plt::{self, JUMP_SLOT};
use cadence_hooks::record::Kind;
use std::ffi::{c_void, CString};
use std::process::Command;

fn build_thrower() -> String {
    let dir = std::env::temp_dir().join(format!("cadence-hooks-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let out = dir.join("libthrower.so");
    let src = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/cpp/thrower.cpp");
    // -z now + relro: the PLT slot sits in a read-only page after load, like the shipped Apple libraries
    let st = Command::new("g++").args(["-shared", "-fPIC", "-O1", "-Wl,-z,now,-z,relro", "-o"]).arg(&out).arg(src).status().expect("g++ is required for this test");
    assert!(st.success());
    out.to_string_lossy().into_owned()
}

unsafe fn sym<T>(h: *mut c_void, name: &str) -> T {
    let c = CString::new(name).unwrap();
    let p = libc::dlsym(h, c.as_ptr());
    assert!(!p.is_null(), "{name} not found");
    std::mem::transmute_copy(&p)
}

static THROW_NOW: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

extern "C-unwind" fn callee_that_throws_through_rust() {
    // a C++ exception propagating through a Rust frame, as Apple's do through ours
    let f: extern "C-unwind" fn() = unsafe { std::mem::transmute(THROW_NOW.load(std::sync::atomic::Ordering::SeqCst)) };
    f()
}

#[test]
fn a_patched_library_still_catches_and_the_hook_records_every_throw() {
    cadence_hooks::init();
    let path = build_thrower();
    let cpath = CString::new(path.clone()).unwrap();
    let h = unsafe { libc::dlopen(cpath.as_ptr(), libc::RTLD_NOW | libc::RTLD_GLOBAL) };
    assert!(!h.is_null(), "dlopen failed");
    let throw_and_catch: extern "C" fn() -> i32 = unsafe { sym(h, "throw_and_catch") };
    let throw_custom: extern "C" fn() -> i32 = unsafe { sym(h, "throw_custom_and_catch") };
    let throw_elsewhere: extern "C" fn() -> i32 = unsafe { sym(h, "throw_runtime_elsewhere") };
    let call_then_catch: extern "C-unwind" fn(extern "C-unwind" fn()) -> i32 = unsafe { sym(h, "call_then_catch") };
    let throw_now: usize = unsafe { sym(h, "throw_now") };
    THROW_NOW.store(throw_now, std::sync::atomic::Ordering::SeqCst);

    // 1. before patching: works, nothing recorded
    assert_eq!(throw_and_catch(), 42);

    // 2. the walker finds exactly one slot, in a RELRO page, and patches it
    let report = cadence_hooks::install_throw_hook(&["libthrower.so"], JUMP_SLOT);
    assert_eq!(report.patched.len(), 1, "{report:?}");
    assert_eq!(report.patched[0].1, "__cxa_throw");
    assert!(report.skipped.is_empty() && report.missing.is_empty(), "{report:?}");

    // 3. idempotent: a second install patches nothing and does not recurse
    let again = cadence_hooks::install_throw_hook(&["libthrower.so"], JUMP_SLOT);
    assert!(again.patched.is_empty() && again.already == 1, "{again:?}");

    // 4. throws still caught; 5 throws of one type from one site
    for _ in 0..5 { assert_eq!(throw_and_catch(), 42, "the C++ catch must still work through the wrapper"); }
    for _ in 0..5 { assert_eq!(throw_elsewhere(), 44); }
    assert_eq!(throw_custom(), 7, "a non-std exception type is caught too");
    assert_eq!(call_then_catch(callee_that_throws_through_rust), 43, "unwinding through a Rust frame still reaches the catch");

    // 5. what the hook recorded
    let mut d = cadence_hooks::Drain::new();
    let mut lines = Vec::new();
    cadence_hooks::drain_once(&mut d, &mut |o| lines.push(o));
    let raises: Vec<String> = lines.iter().filter_map(|o| match o { cadence_hooks::Output::Event(l) if l.starts_with("[CXX-RAISE]") => Some(l.clone()), _ => None }).collect();
    eprintln!("{}", raises.join("\n"));
    // runtime_error: full records for the first 3 only; Custom and logic_error once each
    let rt: Vec<&String> = raises.iter().filter(|l| l.contains("runtime_error")).collect();
    assert_eq!(rt.len(), 6, "3 full records for each of the two call sites (5 throws each): {raises:#?}");
    assert!(rt[0].contains(" x1 ") && rt[2].contains(" x3 "), "{rt:#?}");
    for l in &rt {
        // "at <throw site>" is the code that called __cxa_throw: inside the library, never inside the hook
        assert!(l.contains(" at libthrower.so+0x"), "the throw site must be inside the library: {l}");
    }
    let sites: std::collections::HashSet<&str> = rt.iter().map(|l| l.split(" at ").nth(1).unwrap().split(' ').next().unwrap()).collect();
    assert_eq!(sites.len(), 2, "two call sites, two budgets: {sites:?}");
    assert!(raises.iter().any(|l| l.contains("6Custom")), "{raises:#?}");
    assert!(raises.iter().any(|l| l.contains("logic_error")), "{raises:#?}");
    assert!(raises.iter().all(|l| l.contains("(thread ")));

    // 6. the counted-only throws are summarised (force the 10 s timer by draining after a manual wait is too slow: check the gate directly)
    let mut last = [0u32; plt_gate_size()];
    let pending = cadence_hooks::pending_for_test(&mut last);
    assert_eq!(pending.iter().filter(|(_, _, n)| *n == 2).count(), 2, "2 more throws at each of the 2 sites were only counted: {pending:?}");

    // 7. throws from other threads are recorded without any lock or JVM
    let threads: Vec<_> = (0..4).map(|_| std::thread::spawn(move || (0..50).map(|_| throw_and_catch()).sum::<i32>())).collect();
    for t in threads { assert_eq!(t.join().unwrap(), 42 * 50); }
    assert_eq!(cadence_hooks::dropped(), 0, "no record lost");
    let _ = Kind::Log;
    let _ = plt::R_X86_64_JUMP_SLOT;
}

const fn plt_gate_size() -> usize { cadence_hooks::gate::Gate::size() }
