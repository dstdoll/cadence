use cadence_hooks::{accepts_log, drain_once, Drain, Output};
use std::ffi::CString;

fn push(prio: i32, tag: &str, msg: &str) {
    let (t, m) = (CString::new(tag).unwrap(), CString::new(msg).unwrap());
    unsafe { cadence_hooks::on_log(prio, t.as_ptr(), m.as_ptr()) };
}

#[test]
fn the_filter_keeps_apple_lines_and_warnings_but_never_our_own_diagnostics() {
    assert!(accepts_log(3, b"FairPlay", b"state"));
    assert!(accepts_log(4, b"Something", b"CKC mismatch"));
    assert!(accepts_log(5, b"AnyTag", b"a warning"));
    assert!(accepts_log(6, b"AnyTag", b"an error"));
    assert!(!accepts_log(4, b"Choreographer", b"skipped frames"));
    assert!(!accepts_log(6, b"Cadence", b"ours"), "the app's own tag is in cadence.log already");
    assert!(!accepts_log(6, b"cadence-native", b"ours too"), "native events already reach the log: forwarding them would loop");
    assert!(!accepts_log(4, b"cadence-kd", b"ours"));
}

#[test]
fn lines_flow_through_the_ring_the_drain_and_the_throttle_with_nulls_and_overflow_handled() {
    cadence_hooks::init();
    // off by default: nothing is captured until the platform glue turns it on
    push(6, "FairPlay", "before enabling");
    let mut d = Drain::new();
    let mut out = Vec::new();
    assert_eq!(drain_once(&mut d, &mut |o| out.push(o)), 0);

    cadence_hooks::set_logging(true);
    push(3, "FairPlay", "subscription status 0");
    push(4, "Choreographer", "noise");
    unsafe { cadence_hooks::on_log(6, std::ptr::null(), std::ptr::null()) }; // null tag and message: tolerated, then rejected by the filter? error prio passes
    let long = "x".repeat(1000);
    push(6, "SVError", &long);
    drain_once(&mut d, &mut |o| out.push(o));
    let apple: Vec<(String, String)> = out.iter().filter_map(|o| match o { Output::Apple { tag, line } => Some((tag.clone(), line.clone())), _ => None }).collect();
    assert_eq!(apple[0].0, "FairPlay");
    assert!(apple[0].1.starts_with("D t") && apple[0].1.ends_with("subscription status 0"), "{:?}", apple[0]);
    assert!(apple.iter().any(|(t, l)| t == "SVError" && l.len() < 260 && l.starts_with("E t")), "long messages are truncated: {apple:?}");
    assert!(!apple.iter().any(|(t, _)| t == "Choreographer"));
    assert!(!apple.iter().any(|(_, l)| l.contains("before enabling")));

    // a flood is bounded and reported once
    out.clear();
    for i in 0..200 { push(5, "FairPlay", &format!("distinct warning {i}")); }
    drain_once(&mut d, &mut |o| out.push(o));
    let emitted = out.iter().filter(|o| matches!(o, Output::Apple { .. })).count();
    assert!(emitted <= 25, "rate limit is 25 per second, got {emitted}");
    // overflow of the ring itself is counted, never blocks
    for i in 0..2000 { push(5, "FairPlay", &format!("burst {i}")); }
    assert!(cadence_hooks::dropped() > 0, "a full ring drops and counts");
}
