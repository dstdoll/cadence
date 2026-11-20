use cadence_hooks::gate::{Gate, FULL};
use cadence_hooks::record::Record;
use cadence_hooks::ring::Ring;
use cadence_hooks::throttle::{line_key, Throttle};
use std::sync::Arc;

#[test]
fn ring_keeps_every_item_from_many_producers_without_corruption() {
    let ring = Arc::new(Ring::<(u32, u32, u64)>::new(1024));
    let producers = 4u32;
    let per = 20_000u32;
    let consumer = {
        let ring = ring.clone();
        std::thread::spawn(move || {
            let mut seen = vec![0u32; producers as usize];
            let mut last = vec![-1i64; producers as usize];
            let mut total = 0u32;
            while total < producers * per - ring.dropped() as u32 {
                match ring.pop() {
                    Some((p, i, check)) => {
                        assert_eq!(check, (p as u64) << 32 | i as u64, "a record was torn");
                        assert!(i as i64 > last[p as usize], "a producer's records came out of order");
                        last[p as usize] = i as i64;
                        seen[p as usize] += 1;
                        total += 1;
                    }
                    None => std::thread::yield_now(),
                }
                if total + ring.dropped() as u32 >= producers * per { break; }
            }
            (seen, total)
        })
    };
    let handles: Vec<_> = (0..producers).map(|p| {
        let ring = ring.clone();
        std::thread::spawn(move || for i in 0..per { while !ring.push((p, i, (p as u64) << 32 | i as u64)) && false {} })
    }).collect();
    handles.into_iter().for_each(|h| h.join().unwrap());
    let (seen, total) = consumer.join().unwrap();
    assert_eq!(total as usize + ring.dropped(), (producers * per) as usize, "every item is either delivered or counted as dropped");
    assert!(seen.iter().all(|&n| n > 0));
}

#[test]
fn a_full_ring_drops_and_counts_instead_of_blocking() {
    let ring = Ring::<u32>::new(4);
    let accepted = (0..10).filter(|&i| ring.push(i)).count();
    assert_eq!(accepted, 4);
    assert_eq!(ring.dropped(), 6);
    assert_eq!((0..4).map(|_| ring.pop().unwrap()).collect::<Vec<_>>(), vec![0, 1, 2, 3]);
    assert!(ring.pop().is_none());
    assert!(ring.push(99), "space is reusable after draining");
}

#[test]
fn records_truncate_instead_of_overflowing() {
    let long = vec![b'x'; 1000];
    let r = Record::log(6, 1, &long, &long);
    assert_eq!(r.tag().len(), 32);
    assert_eq!(r.msg().len(), 224);
    let t = Record::throw(1, 1, &long, &[1usize; 50]);
    assert_eq!(t.frames().len(), 8);
}

#[test]
fn gate_gives_each_type_and_site_a_full_record_budget_then_only_counts() {
    let g = Gate::new();
    let counts: Vec<u32> = (0..6).map(|_| g.hit(0x1000, 0x2000)).collect();
    assert_eq!(counts, vec![1, 2, 3, 4, 5, 6]);
    assert_eq!(g.hit(0x1000, 0x3000), 1, "another call site has its own budget");
    assert_eq!(g.hit(0x9999, 0x2000), 1, "another type has its own budget");
    let mut last = [0u32; Gate::size()];
    let p = g.pending(&mut last);
    assert_eq!(p, vec![(0x1000, 0x2000, 6 - FULL)]);
    assert!(g.pending(&mut last).is_empty(), "already reported");
    g.hit(0x1000, 0x2000);
    assert_eq!(g.pending(&mut last), vec![(0x1000, 0x2000, 1)]);
}

#[test]
fn gate_reports_overflow_instead_of_recording() {
    let g = Gate::new();
    let results: Vec<u32> = (1..=300usize).map(|i| g.hit(i * 64, i * 8)).collect();
    assert!(results.contains(&u32::MAX), "a full table reports MAX (record nothing)");
    assert!(results.iter().take(100).all(|&c| c == 1));
}

#[test]
fn throttle_limits_a_flood_reports_it_and_collapses_repeats() {
    let mut t = Throttle::new(3);
    let emitted = (0..10u64).filter(|&i| t.feed(5_000, i).emit).count();
    assert_eq!(emitted, 3);
    let v = t.feed(6_500, 100);
    assert!(v.emit);
    assert_eq!(v.dropped_note, Some(7));
    let k = line_key("FairPlay", "same");
    assert!(t.feed(8_000, k).emit);
    for _ in 0..4 { assert!(!t.feed(8_000, k).emit); }
    let after = t.feed(8_001, line_key("FairPlay", "different"));
    assert_eq!(after.repeat_note, Some(4));
    assert!(after.emit);
    assert_ne!(line_key("a", "bc"), line_key("ab", "c"), "tag/message boundary is part of the key");
}
