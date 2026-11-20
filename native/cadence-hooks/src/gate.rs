//! Decides how much to record about a C++ throw. Apple's code may use exceptions as control flow, so
//! the first [`FULL`] hits of each (type, call site) are recorded with their call stack and later ones
//! are only counted; the drain reports the counts periodically.

use std::sync::atomic::{AtomicU32, AtomicU64, AtomicUsize, Ordering};

pub const FULL: u32 = 3;
const SIZE: usize = 128;

pub struct Gate {
    keys: [AtomicU64; SIZE],
    counts: [AtomicU32; SIZE],
    types: [AtomicUsize; SIZE],
    sites: [AtomicUsize; SIZE],
}

impl Gate {
    pub fn new() -> Gate {
        Gate {
            keys: std::array::from_fn(|_| AtomicU64::new(0)),
            counts: std::array::from_fn(|_| AtomicU32::new(0)),
            types: std::array::from_fn(|_| AtomicUsize::new(0)),
            sites: std::array::from_fn(|_| AtomicUsize::new(0)),
        }
    }

    fn key(ty: usize, site: usize) -> u64 {
        ((ty as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15) ^ (site as u64).rotate_left(29)) | 1
    }

    /// The 1-based count of this (type, site) including this hit; `u32::MAX` if the table is full (record nothing).
    pub fn hit(&self, ty: usize, site: usize) -> u32 {
        let key = Self::key(ty, site);
        let start = (key >> 7) as usize % SIZE;
        for i in 0..SIZE {
            let idx = (start + i) % SIZE;
            let cur = self.keys[idx].load(Ordering::Acquire);
            if cur == key || (cur == 0 && self.keys[idx].compare_exchange(0, key, Ordering::AcqRel, Ordering::Acquire).map_or_else(|now| now == key, |_| true)) {
                self.types[idx].store(ty, Ordering::Relaxed);
                self.sites[idx].store(site, Ordering::Relaxed);
                return self.counts[idx].fetch_add(1, Ordering::Relaxed).saturating_add(1);
            }
        }
        u32::MAX
    }

    /// (type, site, hits since `last`) for every key thrown more than [`FULL`] times in total; `last`
    /// remembers what was reported so each call reports only the new hits.
    pub fn pending(&self, last: &mut [u32; SIZE]) -> Vec<(usize, usize, u32)> {
        let mut out = Vec::new();
        for i in 0..SIZE {
            let total = self.counts[i].load(Ordering::Relaxed);
            if total > FULL && total > last[i] {
                // hits the full records already described are not "new"
                let new = total - last[i].max(FULL);
                last[i] = total;
                if new > 0 {
                    out.push((self.types[i].load(Ordering::Relaxed), self.sites[i].load(Ordering::Relaxed), new));
                }
            }
        }
        out
    }

    pub const fn size() -> usize {
        SIZE
    }
}

impl Default for Gate {
    fn default() -> Self {
        Self::new()
    }
}
