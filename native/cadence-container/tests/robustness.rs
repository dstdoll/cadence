//! Malformed and truncated input must come back as an error, never as a panic: the shipping
//! library is built with `panic = "abort"`, so a panic in any of these entry points takes the
//! whole app down. The inputs here are derived deterministically from Apple-shaped seeds, so a
//! failure always reproduces.

use std::panic::{catch_unwind, AssertUnwindSafe};

use cadence_container::{clear_fragment, clear_init, clear_segment, decrypt_samples, parse_fragment, parse_init};

fn boxed(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
    let mut out = ((body.len() + 8) as u32).to_be_bytes().to_vec();
    out.extend(kind);
    out.extend(body);
    out
}

/// A full box: version/flags (zero) and then `rest`.
fn full(kind: &[u8; 4], rest: &[u8]) -> Vec<u8> {
    boxed(kind, &[&[0u8; 4][..], rest].concat())
}

/// `ALACSpecificConfig` for 24-bit stereo at `rate`.
fn alac_cookie(rate: u32) -> Vec<u8> {
    let mut c = vec![0, 0, 16, 0, 0, 24, 40, 10, 14, 2, 0, 255, 0, 0, 96, 4, 0, 32, 76, 192];
    c.extend(rate.to_be_bytes());
    c
}

/// An `esds` body for AAC-LC stereo at 44.1 kHz: ES_Descriptor, DecoderConfig, ASC, SLConfig.
fn esds_body() -> Vec<u8> {
    let asc = [0x12u8, 0x10];
    let mut dcd = vec![0x40, 0x15, 0, 0x18, 0, 0, 0x03, 0xE8, 0, 0, 0x03, 0xE8, 0];
    dcd.extend([0x05, asc.len() as u8]);
    dcd.extend(asc);
    let mut es = vec![0, 1, 0];
    es.extend([0x04, dcd.len() as u8]);
    es.extend(dcd);
    es.extend([0x06, 1, 2]);
    let mut body = vec![0u8; 4];
    body.extend([0x03, es.len() as u8]);
    body.extend(es);
    body
}

fn sample_entry_fixed(channels: u16, bits: u16, rate: u32) -> Vec<u8> {
    let mut fixed = vec![0u8; 28];
    fixed[7] = 1; // data reference index
    fixed[16..18].copy_from_slice(&channels.to_be_bytes());
    fixed[18..20].copy_from_slice(&bits.to_be_bytes());
    let rate_field = if rate <= 0xFFFF { rate << 16 } else { 0 };
    fixed[24..28].copy_from_slice(&rate_field.to_be_bytes());
    fixed
}

/// An init segment shaped like Apple's: `entries` sample entries, protected (`enca`) or clear,
/// ALAC or AAC, with `mvex/trex` defaults and a `pssh`.
fn init(codec: &[u8; 4], protected: bool, entries: usize, iv_size: u8, rate: u32) -> Vec<u8> {
    let config = if codec == b"alac" { full(b"alac", &alac_cookie(rate)) } else { boxed(b"esds", &esds_body()) };
    let fixed = sample_entry_fixed(2, if codec == b"alac" { 24 } else { 16 }, rate);
    let entry = if protected {
        let mut tenc = vec![0u8, 0, 1, iv_size];
        tenc.extend([0x11u8; 16]); // default KID
        if iv_size == 0 {
            tenc.push(16);
            tenc.extend([0x22u8; 16]); // constant IV
        }
        let sinf = boxed(
            b"sinf",
            &[boxed(b"frma", codec), full(b"schm", b"cbcs\0\x01\0\0"), boxed(b"schi", &full(b"tenc", &tenc))].concat(),
        );
        boxed(b"enca", &[fixed, config, sinf].concat())
    } else {
        boxed(codec, &[fixed, config].concat())
    };
    let mut stsd = (entries as u32).to_be_bytes().to_vec();
    (0..entries).for_each(|_| stsd.extend(&entry));
    let stbl = boxed(b"stbl", &full(b"stsd", &stsd));
    let mut mdhd = vec![0u8; 8];
    mdhd.extend(rate.to_be_bytes());
    mdhd.extend([0u8; 8]);
    let mdia = boxed(b"mdia", &[full(b"mdhd", &mdhd), boxed(b"minf", &stbl)].concat());
    let trex = full(b"trex", &[&1u32.to_be_bytes()[..], &1u32.to_be_bytes(), &4096u32.to_be_bytes(), &[0u8; 8]].concat());
    let mvhd = full(b"mvhd", &[0u8; 96]);
    let moov = boxed(b"moov", &[mvhd, boxed(b"trak", &mdia), full(b"pssh", &[7u8; 20]), boxed(b"mvex", &trex)].concat());
    [boxed(b"ftyp", b"iso5\0\0\0\0isom"), moov].concat()
}

/// One `moof`+`mdat`: two runs of `sizes` (the second without a data offset), a `senc` with a
/// subsample table (or bare `iv_size`-byte IVs), `saiz`, and an encryption sample group.
fn fragment(sizes: &[u32], iv_size: u8, subsamples: bool) -> Vec<u8> {
    let (first, second) = sizes.split_at(sizes.len() / 2);
    let mut senc_body = (sizes.len() as u32).to_be_bytes().to_vec();
    for s in sizes {
        senc_body.extend(vec![0xA5u8; usize::from(iv_size)]);
        if subsamples {
            senc_body.extend(1u16.to_be_bytes());
            senc_body.extend(1u16.to_be_bytes());
            senc_body.extend((s - 1).to_be_bytes());
        }
    }
    let build = |data_offset: u32| -> Vec<u8> {
        let run = |sizes: &[u32], offset: Option<u32>| {
            let mut b = vec![0, 0, 2, u8::from(offset.is_some())];
            b.extend((sizes.len() as u32).to_be_bytes());
            if let Some(o) = offset {
                b.extend(o.to_be_bytes());
            }
            sizes.iter().for_each(|s| b.extend(s.to_be_bytes()));
            boxed(b"trun", &b)
        };
        let tfhd = boxed(b"tfhd", &[&[0u8, 2, 0, 0x0A][..], &1u32.to_be_bytes(), &2u32.to_be_bytes(), &4096u32.to_be_bytes()].concat());
        let senc = boxed(b"senc", &[&[0u8, 0, 0, if subsamples { 2 } else { 0 }][..], &senc_body].concat());
        let saiz = full(b"saiz", &[8, 0, 0, 0, sizes.len() as u8]);
        let saio = full(b"saio", &[0, 0, 0, 1, 0, 0, 0, 0]);
        let sbgp = full(b"sbgp", &[b"seig".as_slice(), &1u32.to_be_bytes(), &(sizes.len() as u32).to_be_bytes(), &1u32.to_be_bytes()].concat());
        let traf = boxed(b"traf", &[tfhd, senc, saiz, saio, sbgp, run(first, Some(data_offset)), run(second, None)].concat());
        boxed(b"moof", &[full(b"mfhd", &1u32.to_be_bytes()), traf].concat())
    };
    let moof_len = build(0).len() as u32;
    let payload: Vec<u8> = (0..sizes.iter().sum::<u32>()).map(|i| (i % 251) as u8).collect();
    [build(moof_len + 8), boxed(b"mdat", &payload)].concat()
}

/// A media segment with a leading `styp` and two fragments.
fn segment(iv_size: u8, subsamples: bool) -> Vec<u8> {
    [boxed(b"styp", b"msdh\0\0\0\0msdhmsix"), fragment(&[5, 7, 3, 11, 2], iv_size, subsamples), fragment(&[9, 4, 4], iv_size, subsamples)]
        .concat()
}

/// SplitMix64: tiny, seedable and identical on every platform.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
}

/// Header offsets of every box in `data`, descending into the containers these parsers walk.
fn box_offsets(data: &[u8], base: usize, out: &mut Vec<usize>) {
    let mut at = 0usize;
    while at + 8 <= data.len() {
        let size = u32::from_be_bytes(data[at..at + 4].try_into().unwrap()) as usize;
        if size < 8 || size > data.len() - at {
            return;
        }
        out.push(base + at);
        let kind = &data[at + 4..at + 8];
        let body = &data[at + 8..at + size];
        let skip = match kind {
            b"moov" | b"trak" | b"mdia" | b"minf" | b"stbl" | b"mvex" | b"moof" | b"traf" | b"sinf" | b"schi" => Some(0),
            b"stsd" => Some(8),
            b"enca" | b"alac" | b"mp4a" if body.len() > 28 => Some(28),
            _ => None,
        };
        if let Some(skip) = skip {
            if body.len() >= skip {
                box_offsets(&body[skip..], base + at + 8 + skip, out);
            }
        }
        at += size;
    }
}

const INTERESTING_U32: [u32; 14] =
    [0, 1, 2, 7, 8, 9, 15, 16, 0x7FFF_FFFF, 0x8000_0000, 0xFFFF_FFF0, 0xFFFF_FFF8, 0xFFFF_FFFE, 0xFFFF_FFFF];
const INTERESTING_U64: [u64; 8] =
    [0, 8, 16, 0x1_0000_0008, 0x7FFF_FFFF_FFFF_FFFF, 0x8000_0000_0000_0000, u64::MAX - 7, u64::MAX];

fn put_u32(data: &mut [u8], at: usize, v: u32) {
    if at + 4 <= data.len() {
        data[at..at + 4].copy_from_slice(&v.to_be_bytes());
    }
}

/// One random edit: a box size, a largesize header, a field, a bit, or the length.
fn mutate(rng: &mut Rng, seed: &[u8]) -> Vec<u8> {
    let mut data = seed.to_vec();
    for _ in 0..1 + rng.below(3) {
        if data.is_empty() {
            break;
        }
        // Recomputed per edit: an earlier edit may have moved or cut boxes.
        let mut boxes = Vec::new();
        box_offsets(&data, 0, &mut boxes);
        match rng.below(8) {
            0 | 1 if !boxes.is_empty() => {
                let at = boxes[rng.below(boxes.len())];
                let v = match rng.below(3) {
                    0 => INTERESTING_U32[rng.below(INTERESTING_U32.len())],
                    1 => u32::from_be_bytes(data[at..at + 4].try_into().unwrap()).wrapping_add(rng.below(17) as u32).wrapping_sub(8),
                    _ => (data.len() - at) as u32,
                };
                put_u32(&mut data, at, v);
            }
            2 if !boxes.is_empty() => {
                // A 64-bit size: `size == 1` followed by the largesize field.
                let at = boxes[rng.below(boxes.len())];
                put_u32(&mut data, at, 1);
                let wide = INTERESTING_U64[rng.below(INTERESTING_U64.len())];
                if at + 16 <= data.len() {
                    data[at + 8..at + 16].copy_from_slice(&wide.to_be_bytes());
                }
            }
            3 => {
                let at = rng.below(data.len());
                put_u32(&mut data, at, INTERESTING_U32[rng.below(INTERESTING_U32.len())]);
            }
            4 => {
                let at = rng.below(data.len());
                data[at] ^= 1 << rng.below(8);
            }
            5 => {
                let at = rng.below(data.len());
                data[at] = [0u8, 1, 0x7F, 0x80, 0xFF][rng.below(5)];
            }
            6 => {
                let keep = rng.below(data.len());
                data.truncate(keep);
            }
            _ => {
                let at = rng.below(data.len());
                let n = 1 + rng.below(8);
                data.drain(at..(at + n).min(data.len()));
            }
        }
    }
    data
}

fn xor(buf: &mut [u8]) -> Result<(), String> {
    buf.iter_mut().for_each(|b| *b ^= 0x5A);
    Ok(())
}

/// Every public parsing entry point on one init/segment pair. Results are ignored: only a panic
/// is a failure.
fn exercise(init_bytes: &[u8], segment_bytes: &[u8]) {
    let _ = clear_init(init_bytes);
    let Ok(init) = parse_init(init_bytes) else { return };
    let _ = clear_segment(segment_bytes, &init, xor);
    let _ = clear_fragment(segment_bytes, &init, xor);
    if let Ok(fragment) = parse_fragment(segment_bytes, &init) {
        let mut copy = segment_bytes.to_vec();
        let _ = decrypt_samples(&mut copy, &fragment, xor);
    }
    // The fragment entry points also see the segment past its leading `styp`.
    if let Some(moof) = segment_bytes.windows(4).position(|w| w == b"moof").and_then(|p| p.checked_sub(4)) {
        let tail = &segment_bytes[moof..];
        let _ = clear_fragment(tail, &init, xor);
        if let Ok(fragment) = parse_fragment(tail, &init) {
            let mut copy = tail.to_vec();
            let _ = decrypt_samples(&mut copy, &fragment, xor);
        }
    }
}

fn hex(data: &[u8]) -> String {
    data.iter().map(|b| format!("{b:02x}")).collect()
}

fn assert_no_panic(what: &str, init_bytes: &[u8], segment_bytes: &[u8]) {
    let result = catch_unwind(AssertUnwindSafe(|| exercise(init_bytes, segment_bytes)));
    assert!(result.is_ok(), "{what} panicked\ninit    = {}\nsegment = {}", hex(init_bytes), hex(segment_bytes));
}

fn seeds() -> Vec<(Vec<u8>, Vec<u8>)> {
    vec![
        (init(b"alac", true, 2, 0, 44_100), segment(0, true)),
        (init(b"alac", true, 1, 8, 192_000), segment(8, false)),
        (init(b"alac", true, 2, 16, 96_000), segment(16, true)),
        (init(b"alac", false, 1, 0, 48_000), segment(0, false)),
        (init(b"mp4a", true, 2, 0, 44_100), segment(0, true)),
        (init(b"mp4a", false, 1, 0, 48_000), segment(0, false)),
    ]
}

#[test]
fn seeds_are_well_formed() {
    // The mutations below only mean something if the unmutated inputs parse all the way through.
    for (init_bytes, segment_bytes) in seeds() {
        let init = parse_init(&init_bytes).unwrap();
        clear_init(&init_bytes).unwrap();
        let clear = clear_segment(&segment_bytes, &init, xor).unwrap();
        assert_eq!(clear.len(), segment_bytes.len());
    }
}

#[test]
fn every_truncation_is_handled_without_panicking() {
    for (init_bytes, segment_bytes) in seeds() {
        for cut in 0..init_bytes.len() {
            assert_no_panic("truncated init", &init_bytes[..cut], &segment_bytes);
        }
        for cut in 0..segment_bytes.len() {
            assert_no_panic("truncated segment", &init_bytes, &segment_bytes[..cut]);
        }
    }
}

#[test]
fn pseudo_random_mutations_are_handled_without_panicking() {
    let mut rng = Rng(0x00C0_FFEE_D00D_F00D);
    for (init_bytes, segment_bytes) in seeds() {
        for _ in 0..4_000 {
            let bad_init = mutate(&mut rng, &init_bytes);
            assert_no_panic("mutated init", &bad_init, &segment_bytes);
            let bad_segment = mutate(&mut rng, &segment_bytes);
            assert_no_panic("mutated segment", &init_bytes, &bad_segment);
            assert_no_panic("mutated init and segment", &bad_init, &bad_segment);
        }
    }
}
