//! A bounded multi-producer multi-consumer queue (Vyukov): no locks, no allocation after `new`, safe to
//! push from any thread, including one that holds a lock the consumer needs or that has no JVM. A full
//! queue drops the new item and counts it; producers never wait.

use std::cell::UnsafeCell;
use std::mem::MaybeUninit;
use std::sync::atomic::{AtomicUsize, Ordering};

struct Slot<T> {
    seq: AtomicUsize,
    val: UnsafeCell<MaybeUninit<T>>,
}

pub struct Ring<T: Copy> {
    slots: Box<[Slot<T>]>,
    mask: usize,
    enq: AtomicUsize,
    deq: AtomicUsize,
    dropped: AtomicUsize,
}

unsafe impl<T: Copy + Send> Send for Ring<T> {}
unsafe impl<T: Copy + Send> Sync for Ring<T> {}

impl<T: Copy> Ring<T> {
    /// `capacity` must be a power of two.
    pub fn new(capacity: usize) -> Self {
        assert!(capacity.is_power_of_two() && capacity >= 2);
        let slots = (0..capacity).map(|i| Slot { seq: AtomicUsize::new(i), val: UnsafeCell::new(MaybeUninit::uninit()) }).collect();
        Ring { slots, mask: capacity - 1, enq: AtomicUsize::new(0), deq: AtomicUsize::new(0), dropped: AtomicUsize::new(0) }
    }

    /// False (and counted) when the queue is full.
    pub fn push(&self, item: T) -> bool {
        let mut pos = self.enq.load(Ordering::Relaxed);
        loop {
            let slot = &self.slots[pos & self.mask];
            let seq = slot.seq.load(Ordering::Acquire);
            let diff = seq as isize - pos as isize;
            if diff == 0 {
                match self.enq.compare_exchange_weak(pos, pos + 1, Ordering::Relaxed, Ordering::Relaxed) {
                    Ok(_) => {
                        // SAFETY: the CAS gave this thread exclusive ownership of the slot until seq is published.
                        unsafe { (*slot.val.get()).write(item) };
                        slot.seq.store(pos + 1, Ordering::Release);
                        return true;
                    }
                    Err(p) => pos = p,
                }
            } else if diff < 0 {
                self.dropped.fetch_add(1, Ordering::Relaxed);
                return false;
            } else {
                pos = self.enq.load(Ordering::Relaxed);
            }
        }
    }

    pub fn pop(&self) -> Option<T> {
        let mut pos = self.deq.load(Ordering::Relaxed);
        loop {
            let slot = &self.slots[pos & self.mask];
            let seq = slot.seq.load(Ordering::Acquire);
            let diff = seq as isize - (pos + 1) as isize;
            if diff == 0 {
                match self.deq.compare_exchange_weak(pos, pos + 1, Ordering::Relaxed, Ordering::Relaxed) {
                    Ok(_) => {
                        // SAFETY: the CAS gave this thread exclusive ownership; the producer's Release store made the value visible.
                        let v = unsafe { (*slot.val.get()).assume_init_read() };
                        slot.seq.store(pos + self.mask + 1, Ordering::Release);
                        return Some(v);
                    }
                    Err(p) => pos = p,
                }
            } else if diff < 0 {
                return None;
            } else {
                pos = self.deq.load(Ordering::Relaxed);
            }
        }
    }

    pub fn dropped(&self) -> usize {
        self.dropped.load(Ordering::Relaxed)
    }
}
