//! Bounds what the drain forwards to the app log: at most `max_per_sec` lines a second (the rest are
//! counted and reported once), and an identical line repeated back to back is collapsed to a count.

pub struct Throttle {
    max_per_sec: u32,
    window_start: u64,
    in_window: u32,
    dropped: u32,
    last_key: Option<u64>,
    repeats: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Verdict {
    /// Forward this line.
    pub emit: bool,
    /// A note to forward BEFORE the line: lines dropped by the rate limit in the previous second.
    pub dropped_note: Option<u32>,
    /// A note to forward BEFORE the line: how many times the previous line repeated.
    pub repeat_note: Option<u32>,
}

impl Throttle {
    pub fn new(max_per_sec: u32) -> Throttle {
        Throttle { max_per_sec, window_start: 0, in_window: 0, dropped: 0, last_key: None, repeats: 0 }
    }

    pub fn feed(&mut self, now_ms: u64, key: u64) -> Verdict {
        let mut v = Verdict { emit: false, dropped_note: None, repeat_note: None };
        if now_ms.saturating_sub(self.window_start) >= 1_000 {
            if self.dropped > 0 {
                v.dropped_note = Some(self.dropped);
            }
            self.window_start = now_ms;
            self.in_window = 0;
            self.dropped = 0;
        }
        if self.last_key == Some(key) {
            self.repeats += 1;
            return v;
        }
        if self.repeats > 0 {
            v.repeat_note = Some(self.repeats);
        }
        self.last_key = Some(key);
        self.repeats = 0;
        if self.in_window >= self.max_per_sec {
            self.dropped += 1;
            return v;
        }
        self.in_window += 1;
        v.emit = true;
        v
    }
}

/// FNV-1a over the tag and message: the identity of a line for repeat collapsing.
pub fn line_key(tag: &str, msg: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in tag.bytes().chain(std::iter::once(0)).chain(msg.bytes()) {
        h ^= b as u64;
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    h
}
