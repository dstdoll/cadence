//! Host-side diagnostics for the decrypt pipeline; none of it needs the Apple libraries.
//!
//! ```text
//! inspect <package> <out.m4a> [identity|mock] [batch]
//!     Prints the init/trex values and each fragment's sample count, then muxes a progressive
//!     M4A (the download path) so ffprobe can validate the container.
//!
//! inspect clear <package> <out.mp4> <identity|mock|replay> [decrypted.m4a]
//!     Builds the clear fragmented stream the player reads (clear init, sidx, clear segments).
//!     `replay` substitutes each sample with the matching frame of a file that was already
//!     decrypted on a device, which yields real, decodable audio on the host.
//! ```
//!
//! `identity` leaves sample bytes untouched (for clear input); `mock` XORs them.

use std::collections::VecDeque;
use std::path::Path;
use std::sync::Mutex;

use cadence_android_drm::{
    clear_segment_with, decrypt_streaming, decrypt_with_workers, IdentityDecryptor, MockDecryptor, SampleDecryptor,
};
use cadence_container::{build_sidx, clear_init, parse_fragment, parse_init, InitSegment};

fn boxes(data: &[u8]) -> Vec<(usize, usize, [u8; 4])> {
    let mut out = Vec::new();
    let mut at = 0usize;
    while at + 8 <= data.len() {
        let size = u32::from_be_bytes([data[at], data[at + 1], data[at + 2], data[at + 3]]) as usize;
        let mut kind = [0u8; 4];
        kind.copy_from_slice(&data[at + 4..at + 8]);
        let (header, total) = if size == 1 {
            (16usize, u64::from_be_bytes(data[at + 8..at + 16].try_into().unwrap()) as usize)
        } else if size == 0 {
            (8usize, data.len() - at)
        } else {
            (8usize, size)
        };
        if total < header || at + total > data.len() {
            break;
        }
        out.push((at, at + total, kind));
        at += total;
    }
    out
}

/// Hands back the frames of an already-decrypted file, one per decrypt call, in file order.
/// Encryption is in place, so each frame must be exactly as long as the sample it replaces.
struct ReplayDecryptor {
    frames: Mutex<VecDeque<Vec<u8>>>,
}

impl ReplayDecryptor {
    /// Reads the frames of a progressive, single-chunk M4A (what the download path writes).
    fn from_m4a(path: &str) -> Self {
        let data = std::fs::read(path).expect("read decrypted m4a");
        let find = |kind: &[u8; 4]| data.windows(4).position(|w| w == kind).unwrap_or_else(|| panic!("no {kind:?}"));
        let u32_at = |at: usize| u32::from_be_bytes(data[at..at + 4].try_into().unwrap()) as usize;
        let stsz = find(b"stsz");
        let count = u32_at(stsz + 12);
        let mut offset = u32_at(find(b"stco") + 12);
        let mut frames = VecDeque::with_capacity(count);
        for i in 0..count {
            let size = u32_at(stsz + 16 + i * 4);
            frames.push_back(data[offset..offset + size].to_vec());
            offset += size;
        }
        Self { frames: Mutex::new(frames) }
    }

    fn remaining(&self) -> usize {
        self.frames.lock().unwrap().len()
    }
}

impl SampleDecryptor for ReplayDecryptor {
    fn decrypt(&self, _kd: i64, buf: &mut [u8]) -> i32 {
        match self.frames.lock().unwrap().pop_front() {
            Some(frame) if frame.len() == buf.len() => {
                buf.copy_from_slice(&frame);
                0
            }
            Some(frame) => {
                eprintln!("replay: frame is {} bytes but the sample is {}", frame.len(), buf.len());
                -2
            }
            None => -1,
        }
    }
}

/// `(start, end)` of every `moof`+`mdat` pair after the init, in file order.
fn fragments(data: &[u8], all: &[(usize, usize, [u8; 4])]) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    for (index, b) in all.iter().enumerate() {
        if &b.2 == b"moof" {
            let mdat = all[index + 1..].iter().find(|b| &b.2 == b"mdat").expect("moof without mdat");
            out.push((b.0, mdat.1));
        }
    }
    assert!(!out.is_empty(), "no fragments in {} bytes", data.len());
    out
}

fn fragment_ticks(segment: &[u8], init: &InitSegment) -> (usize, u64) {
    let frag = parse_fragment(segment, init).unwrap();
    (frag.samples.len(), frag.samples.iter().map(|s| u64::from(s.duration)).sum())
}

fn clear_stream(args: &[String]) {
    let (input, output, mode) = (&args[0], &args[1], args[2].as_str());
    let data = std::fs::read(input).unwrap();
    let all = boxes(&data);
    let moov = all.iter().find(|b| &b.2 == b"moov").unwrap();
    let init_bytes = &data[..moov.1];
    let init = parse_init(init_bytes).unwrap();

    let replay = (mode == "replay").then(|| ReplayDecryptor::from_m4a(&args[3]));
    let decryptor: &dyn SampleDecryptor = match mode {
        "identity" => &IdentityDecryptor,
        "mock" => &MockDecryptor,
        "replay" => replay.as_ref().unwrap(),
        other => panic!("unknown mode {other}"),
    };

    let mut index = Vec::new();
    let mut segments = Vec::new();
    for (start, end) in fragments(&data, &all) {
        let (samples, ticks) = fragment_ticks(&data[start..end], &init);
        let clear = clear_segment_with(decryptor, 1, init_bytes, &data[start..end]).unwrap();
        assert_eq!(clear.len(), end - start, "a clear segment must keep its length");
        println!("segment @{start}: bytes={} samples={samples} ticks={ticks}", clear.len());
        index.push((clear.len() as u32, ticks as u32));
        segments.push(clear);
    }
    if let Some(replay) = &replay {
        assert_eq!(replay.remaining(), 0, "the decrypted file has frames the package does not");
    }

    let clear_init_bytes = clear_init(init_bytes).unwrap();
    let sidx = build_sidx(init.timescale, &index);
    println!("head: init {} -> clear {} bytes, sidx {} bytes", init_bytes.len(), clear_init_bytes.len(), sidx.len());
    let mut out = [clear_init_bytes, sidx].concat();
    segments.iter().for_each(|s| out.extend_from_slice(s));
    std::fs::write(output, &out).unwrap();
    println!("clear stream written ({mode}, {} segments, {} bytes)", segments.len(), out.len());
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(String::as_str) == Some("clear") {
        return clear_stream(&args[1..]);
    }

    let input = args.first().cloned().unwrap_or("/tmp/package.bin".into());
    let output = args.get(1).cloned().unwrap_or("/tmp/mock.m4a".into());
    let data = std::fs::read(&input).unwrap();
    let all = boxes(&data);
    let moov = all.iter().find(|b| &b.2 == b"moov").unwrap();
    let init = parse_init(&data[..moov.1]).unwrap();
    println!(
        "init: timescale={} rate={} ch={} depth={} trex(dur={}, size={})",
        init.timescale, init.sample_rate, init.channels, init.bit_depth, init.default_sample_duration, init.default_sample_size
    );
    let (mut total_samples, mut total_ticks) = (0usize, 0u64);
    for (start, end) in fragments(&data, &all) {
        let (samples, ticks) = fragment_ticks(&data[start..end], &init);
        println!("fragment {start}: samples={samples} ticks={ticks} dur_s={:.3}", ticks as f64 / init.timescale as f64);
        total_samples += samples;
        total_ticks += ticks;
    }
    println!("total samples={} ticks={} duration_s={:.3}", total_samples, total_ticks, total_ticks as f64 / init.timescale as f64);

    let identity = args.get(2).map(String::as_str) == Some("identity");
    let batch = args.get(3).map(String::as_str) == Some("batch");
    let decryptor: &dyn SampleDecryptor = if identity { &IdentityDecryptor } else { &MockDecryptor };
    let meta = cadence_container::TrackMetadata {
        title: Some("Inspect title".into()),
        artist: Some("Inspect artist".into()),
        album: Some("Inspect album".into()),
        track: Some((3, 12)),
        lyrics: Some("line one\nline two".into()),
        ..Default::default()
    };
    if batch {
        decrypt_with_workers(decryptor, &[1i64], Path::new(&input), Path::new(&output), Some(meta), 1).unwrap();
    } else {
        decrypt_streaming(decryptor, &[1i64], Path::new(&input), Path::new(&output), Some(meta)).unwrap();
    }
    println!("muxed {} -> {} ({}, {})", input, output, if identity { "identity" } else { "mock" }, if batch { "batch" } else { "streaming" });
}
