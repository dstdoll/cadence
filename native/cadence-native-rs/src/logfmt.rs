//! Pure text helpers for the native error channel (no JNI, no allocation where it matters), kept in
//! their own file so they can be unit-tested on the host:
//! `rustc --edition 2021 --test src/logfmt.rs -o /tmp/logfmt_test && /tmp/logfmt_test`.

/// Conservative scrub for text that may reach logcat without passing the Kotlin `LogRedactor`
/// (only when the JVM bridge is unavailable): URL query strings, long opaque tokens, e-mail names.
pub fn mask(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() {
        let c = b[i] as char;
        // `?query` / `#fragment` of a URL: keep the path, drop the parameters.
        if (c == '?' || c == '#') && out.rfind("://").map_or(false, |p| !out[p..].contains(char::is_whitespace)) {
            out.push_str(&format!("{c}<redacted>"));
            while i < b.len() && !(b[i] as char).is_whitespace() && b[i] != b'"' && b[i] != b'\'' {
                i += 1;
            }
            continue;
        }
        // A long run of token characters (JWTs, base64 secrets, hex ids).
        if is_token(c) {
            let start = i;
            while i < b.len() && is_token(b[i] as char) {
                i += 1;
            }
            if i - start >= 48 {
                out.push_str(&format!("<secret:{}>", i - start));
            } else {
                out.push_str(&s[start..i]);
            }
            continue;
        }
        // user@host -> u***@host
        if c == '@' {
            let user_start = out.rfind(|ch: char| !(ch.is_ascii_alphanumeric() || "._%+-".contains(ch))).map_or(0, |p| p + 1);
            if user_start < out.len() {
                let first: String = out[user_start..].chars().take(1).collect();
                out.truncate(user_start);
                out.push_str(&first);
                out.push_str("***");
            }
        }
        out.push(c);
        i += 1;
    }
    out
}

fn is_token(c: char) -> bool {
    // Same alphabet and threshold as the Kotlin LogRedactor's opaque-token rule: `=` is not part of a
    // run, so `name=value` keeps its name, and identifiers like `apple_call` stay readable.
    c.is_ascii_alphanumeric() || c == '+' || c == '/' || c == '_' || c == '-'
}

/// `SVFootHillSessionCtrl::getPersistentKey(std::string const&, ...)` -> `SVFootHillSessionCtrl::getPersistentKey`.
pub fn without_params(demangled: &str) -> &str {
    match demangled.find('(') {
        Some(p) => demangled[..p].trim_end(),
        None => demangled,
    }
}

/// At most `max` bytes, cut on a char boundary, with an ellipsis when something was dropped.
pub fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        return s.to_owned();
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}...", &s[..end])
}

/// Async-signal-safe: builds the `native-crash.log` line for a fatal signal into `buf` without
/// allocating or formatting through std. Returns the number of bytes written (always fits: it
/// truncates the two names to what remains).
pub fn crash_line(buf: &mut [u8], sig: i32, addr: usize, tid: i32, entry: &str, call: &str) -> usize {
    // The file sits where a USB file browser can read it, so the raw address is not written: only
    // whether it was null (a null dereference is the common case worth knowing).
    let mut w = Writer { buf, n: 0 };
    w.s("\n=== NATIVE CRASH ");
    w.s(match sig {
        11 => "SIGSEGV",
        6 => "SIGABRT",
        7 => "SIGBUS",
        4 => "SIGILL",
        8 => "SIGFPE",
        _ => "SIGNAL",
    });
    w.s(" (signal ");
    w.dec(sig as i64);
    w.s(") fault=");
    w.s(if addr == 0 { "null" } else { "non-null" });
    w.s(" tid=");
    w.dec(tid as i64);
    if entry.is_empty() && call.is_empty() {
        w.s(" no native entry point of ours was active (Apple-created thread, or outside the guard)");
    } else {
        w.s(" entry=");
        w.s(if entry.is_empty() { "?" } else { entry });
        w.s(" apple_call=");
        w.s(if call.is_empty() { "-" } else { call });
    }
    w.s(" ===\n");
    w.n
}

struct Writer<'a> {
    buf: &'a mut [u8],
    n: usize,
}
impl Writer<'_> {
    fn s(&mut self, s: &str) {
        for &b in s.as_bytes() {
            if self.n + 1 >= self.buf.len() {
                return; // keep the final newline slot free
            }
            self.buf[self.n] = b;
            self.n += 1;
        }
    }
    fn dec(&mut self, mut v: i64) {
        if v < 0 {
            self.s("-");
            v = -v;
        }
        let mut digits = [0u8; 20];
        let mut i = digits.len();
        loop {
            i -= 1;
            digits[i] = b'0' + (v % 10) as u8;
            v /= 10;
            if v == 0 {
                break;
            }
        }
        for &d in &digits[i..] {
            if self.n + 1 >= self.buf.len() {
                return;
            }
            self.buf[self.n] = d;
            self.n += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn masks_url_queries_but_keeps_the_path() {
        assert_eq!(mask("GET https://a.example/p/q?token=abc&x=1 failed"), "GET https://a.example/p/q?<redacted> failed");
    }

    #[test]
    fn masks_long_opaque_tokens_only() {
        let t = "A".repeat(60);
        assert_eq!(mask(&format!("bad key {t} here")), "bad key <secret:60> here");
        assert_eq!(mask("adam=1595217258 key=c23"), "adam=1595217258 key=c23");
        // names the log is read for must survive
        assert_eq!(mask("entry=selftest.throw (case=int) apple_call=cpp_throw_int threw int"), "entry=selftest.throw (case=int) apple_call=cpp_throw_int threw int");
    }

    #[test]
    fn masks_email_local_parts() {
        assert_eq!(mask("user alice.smith@example.com rejected"), "user a***@example.com rejected");
    }

    #[test]
    fn leaves_plain_text_alone() {
        assert_eq!(mask("std::runtime_error: invalid state"), "std::runtime_error: invalid state");
    }

    #[test]
    fn strips_parameters_from_a_demangled_name() {
        assert_eq!(
            without_params("SVFootHillSessionCtrl::getPersistentKey(std::__ndk1::basic_string<char> const&)"),
            "SVFootHillSessionCtrl::getPersistentKey"
        );
        assert_eq!(without_params("plain_symbol"), "plain_symbol");
    }

    #[test]
    fn truncates_on_a_char_boundary() {
        assert_eq!(truncate("abcdef", 10), "abcdef");
        assert_eq!(truncate("abcdef", 3), "abc...");
        assert_eq!(truncate("aé", 2), "a...");
    }

    fn line(sig: i32, addr: usize, entry: &str, call: &str) -> String {
        let mut b = [0u8; 400];
        let n = crash_line(&mut b, sig, addr, 4242, entry, call);
        String::from_utf8(b[..n].to_vec()).unwrap()
    }

    #[test]
    fn crash_line_names_the_active_apple_call() {
        assert_eq!(
            line(6, 0, "kd", "get_persistent_key"),
            "\n=== NATIVE CRASH SIGABRT (signal 6) fault=null tid=4242 entry=kd apple_call=get_persistent_key ===\n"
        );
    }

    #[test]
    fn crash_line_says_when_nothing_of_ours_was_active() {
        let l = line(11, 0xdeadbeef, "", "");
        assert!(l.starts_with("\n=== NATIVE CRASH SIGSEGV (signal 11) fault=non-null tid=4242 no native entry point"));
        assert!(!l.contains("deadbeef"));
        assert!(l.ends_with("===\n"));
    }

    #[test]
    fn crash_line_truncates_instead_of_overflowing() {
        let mut b = [0u8; 40];
        let n = crash_line(&mut b, 11, usize::MAX, 1, "kd", "x");
        assert!(n < 40);
    }
}
