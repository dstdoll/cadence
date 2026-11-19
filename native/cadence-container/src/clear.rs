//! Converts Apple's protected fMP4 into the clear equivalent a stock demuxer plays.
//!
//! Streaming playback feeds the player one track as a single fragmented MP4. Two things stand
//! between the HLS package and that file:
//!
//! * the init segment declares `enca` sample entries (one per key) wrapping the real `alac`
//!   entry in a `sinf`; a demuxer treats those as DRM-protected and refuses to output samples;
//! * each fragment's samples are encrypted in place (`cbcs`), described by `senc`/`saiz`/`saio`.
//!
//! [`clear_init`] rewrites the sample entries; [`clear_fragment`] decrypts the samples and blanks
//! the encryption boxes. A fragment keeps its exact byte length, so the position of every
//! segment in the clear stream is known from the playlist before any of it is downloaded.

use crate::boxes::{boxed, child, children};
use crate::parse::{Fragment, InitSegment, ParseError};

#[derive(Debug)]
pub enum ClearError {
    Parse(ParseError),
    /// The per-sample decryptor reported a failure; the string is its message.
    Decrypt(String),
    /// A `senc` subsample table describes more bytes than its sample holds.
    SubsampleOutOfRange { sample: usize },
}

impl From<ParseError> for ClearError {
    fn from(e: ParseError) -> Self {
        ClearError::Parse(e)
    }
}

/// The container boxes on the way from the file root to the sample descriptions.
const PATH_TO_STSD: [&[u8; 4]; 5] = [b"moov", b"trak", b"mdia", b"minf", b"stbl"];

/// Rewrites an init segment so every protected audio entry becomes its original format.
///
/// `enca` entries turn into the codec named by their `sinf/frma` (ALAC) with the `sinf` removed,
/// and any `pssh` is dropped. Entry count and order are preserved because fragments refer to
/// them by index (`tfhd` sample description index).
pub fn clear_init(init: &[u8]) -> Result<Vec<u8>, ParseError> {
    rewrite_level(init, 0)
}

fn rewrite_level(data: &[u8], depth: usize) -> Result<Vec<u8>, ParseError> {
    let boxes = children(data);
    let consumed = boxes.last().map_or(0, |b| b.body_start + b.body.len());
    if consumed != data.len() {
        return Err(ParseError::Truncated("init segment box"));
    }
    let mut out = Vec::with_capacity(data.len());
    for b in boxes {
        if depth < PATH_TO_STSD.len() && &b.kind == PATH_TO_STSD[depth] {
            out.extend(boxed(&b.kind, &rewrite_level(b.body, depth + 1)?));
        } else if depth == PATH_TO_STSD.len() && &b.kind == b"stsd" {
            out.extend(boxed(b"stsd", &clear_stsd(b.body)?));
        } else if depth == 1 && &b.kind == b"pssh" {
            // Key-system data; with it present a player asks for a DRM session.
        } else {
            out.extend_from_slice(&data[b.start..b.body_start + b.body.len()]);
        }
    }
    Ok(out)
}

fn clear_stsd(body: &[u8]) -> Result<Vec<u8>, ParseError> {
    // Full-box header and entry count, then the entries.
    let header = body.get(..8).ok_or(ParseError::Truncated("stsd"))?;
    let mut out = header.to_vec();
    for entry in children(&body[8..]) {
        if &entry.kind != b"enca" {
            out.extend(boxed(&entry.kind, entry.body));
            continue;
        }
        // AudioSampleEntry fixed fields (28 bytes), then its child boxes.
        let fixed = entry.body.get(..28).ok_or(ParseError::Truncated("audio sample entry"))?;
        let extensions = &entry.body[28..];
        let format = child(extensions, b"sinf")
            .and_then(|sinf| child(sinf.body, b"frma"))
            .and_then(|frma| frma.body.get(..4))
            .ok_or(ParseError::Missing("sinf/frma"))?;
        let mut kind = [0u8; 4];
        kind.copy_from_slice(format);
        let mut clear = fixed.to_vec();
        for ext in children(extensions).into_iter().filter(|b| &b.kind != b"sinf") {
            clear.extend(boxed(&ext.kind, ext.body));
        }
        out.extend(boxed(&kind, &clear));
    }
    Ok(out)
}

/// Decrypts every sample of `fragment` inside `segment`, which must be the slice it was parsed
/// from. `decrypt` receives each protected byte range and transforms it in place.
pub fn decrypt_samples<F>(segment: &mut [u8], fragment: &Fragment, mut decrypt: F) -> Result<(), ClearError>
where
    F: FnMut(&mut [u8]) -> Result<(), String>,
{
    for (index, sample) in fragment.samples.iter().enumerate() {
        let start = sample.offset;
        let end = start + sample.size as usize;
        let bytes = segment.get_mut(start..end).ok_or(ParseError::SampleOutOfRange { sample: index })?;
        if sample.subsamples.is_empty() {
            // No subsample table: the whole sample is protected.
            decrypt(bytes).map_err(ClearError::Decrypt)?;
            continue;
        }
        let mut at = 0usize;
        for &(clear, protected) in &sample.subsamples {
            at += clear as usize;
            let stop = at + protected as usize;
            let range = bytes.get_mut(at..stop).ok_or(ClearError::SubsampleOutOfRange { sample: index })?;
            if !range.is_empty() {
                decrypt(range).map_err(ClearError::Decrypt)?;
            }
            at = stop;
        }
    }
    Ok(())
}

/// Returns `segment` (a `moof` and its `mdat`) with its samples decrypted and its encryption
/// boxes blanked. The result has the same length and the same sample layout as the input.
pub fn clear_fragment<F>(segment: &[u8], init: &InitSegment, decrypt: F) -> Result<Vec<u8>, ClearError>
where
    F: FnMut(&mut [u8]) -> Result<(), String>,
{
    let fragment = crate::parse::parse_fragment(segment, init)?;
    let mut out = segment.to_vec();
    decrypt_samples(&mut out, &fragment, decrypt)?;
    for at in encryption_box_kinds(segment) {
        out[at..at + 4].copy_from_slice(b"free");
    }
    Ok(out)
}

/// Clears one HLS media segment: every `moof`+`mdat` pair in it is decrypted, and boxes that
/// carry no samples (`styp`, `prft`, `emsg`...) pass through untouched. Same length as the input.
pub fn clear_segment<F>(segment: &[u8], init: &InitSegment, mut decrypt: F) -> Result<Vec<u8>, ClearError>
where
    F: FnMut(&mut [u8]) -> Result<(), String>,
{
    let top = children(segment);
    let consumed = top.last().map_or(0, |b| b.body_start + b.body.len());
    if consumed != segment.len() {
        return Err(ParseError::Truncated("media segment box").into());
    }
    let end_of = |i: usize| top[i].body_start + top[i].body.len();
    let mut out = Vec::with_capacity(segment.len());
    let mut fragments = 0usize;
    let mut i = 0usize;
    while i < top.len() {
        if &top[i].kind != b"moof" {
            out.extend_from_slice(&segment[top[i].start..end_of(i)]);
            i += 1;
            continue;
        }
        let mdat = (i + 1..top.len())
            .find(|&j| &top[j].kind == b"mdat")
            .ok_or(ParseError::Missing("mdat after moof"))?;
        out.extend(clear_fragment(&segment[top[i].start..end_of(mdat)], init, &mut decrypt)?);
        fragments += 1;
        i = mdat + 1;
    }
    if fragments == 0 {
        return Err(ParseError::Missing("moof").into());
    }
    Ok(out)
}

/// Offsets of the four-character type of every box in the `moof` that only makes sense for
/// protected samples. Retyping them to `free` keeps all sizes and offsets intact; a demuxer
/// that meets `saiz`/`senc` under a clear sample entry would otherwise reject the fragment.
fn encryption_box_kinds(segment: &[u8]) -> Vec<usize> {
    let mut out = Vec::new();
    let Some(moof) = child(segment, b"moof") else { return out };
    for traf in children(moof.body).into_iter().filter(|b| &b.kind == b"traf") {
        let traf_at = moof.body_start + traf.body_start;
        for b in children(traf.body) {
            let blank = match &b.kind {
                b"senc" | b"saiz" | b"saio" => true,
                // Sample groups are generic; only the encryption grouping is dropped.
                b"sbgp" | b"sgpd" => b.body.get(4..8) == Some(b"seig".as_slice()),
                _ => false,
            };
            if blank {
                out.push(traf_at + b.start + 4);
            }
        }
    }
    out
}

/// Builds a `sidx` box indexing consecutive segments that start right after the box.
///
/// `segments` holds each segment's `(byte length, duration in timescale units)`. A demuxer uses
/// it to map a seek time to a byte offset, which lets playback jump to a segment that has not
/// been fetched yet.
pub fn build_sidx(timescale: u32, segments: &[(u32, u32)]) -> Vec<u8> {
    let mut body = vec![0u8; 4]; // version 0, flags 0
    body.extend(1u32.to_be_bytes()); // reference_ID: the single track
    body.extend(timescale.to_be_bytes());
    body.extend(0u32.to_be_bytes()); // earliest_presentation_time
    body.extend(0u32.to_be_bytes()); // first_offset: segments follow immediately
    body.extend(0u16.to_be_bytes());
    body.extend((segments.len() as u16).to_be_bytes());
    for &(size, duration) in segments {
        body.extend((size & 0x7FFF_FFFF).to_be_bytes()); // reference_type 0 (media)
        body.extend(duration.to_be_bytes());
        body.extend(0x9000_0000u32.to_be_bytes()); // starts with a sync sample (SAP type 1)
    }
    boxed(b"sidx", &body)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parse::{parse_fragment, parse_init};

    const COOKIE: [u8; 24] = [
        0, 0, 16, 0, 0, 24, 40, 10, 14, 2, 0, 255, 0, 0, 96, 4, 0, 32, 76, 192, 0, 0, 172, 68,
    ];

    fn full(kind: &[u8; 4], rest: &[u8]) -> Vec<u8> {
        boxed(kind, &[&[0u8; 4][..], rest].concat())
    }

    /// An init segment with `entries` protected ALAC entries, shaped like Apple's.
    fn protected_init(entries: usize) -> Vec<u8> {
        protected_init_of(entries, b"alac", full(b"alac", &COOKIE), 0)
    }

    /// An E-AC-3 JOC `dec3` body: 768 kbps, one independent substream, 48 kHz, 5.1 core, JOC.
    const DEC3: [u8; 7] = [0x18, 0x00, 0x20, 0x0F, 0x00, 0x01, 0x10];

    /// Like Apple's Dolby Atmos init: protected `enca` entries wrapping `ec-3` with its `dec3`.
    fn protected_atmos_init(entries: usize) -> Vec<u8> {
        protected_init_of(entries, b"ec-3", boxed(b"dec3", &DEC3), 48_000)
    }

    fn protected_init_of(entries: usize, format: &[u8; 4], config: Vec<u8>, rate: u32) -> Vec<u8> {
        let tenc = full(b"tenc", &[0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        let sinf = boxed(b"sinf", &[boxed(b"frma", format), full(b"schm", b"cbcs\0\x01\0\0"), boxed(b"schi", &tenc)].concat());
        let mut fixed = vec![0u8; 28];
        fixed[7] = 1; // data reference index
        fixed[17] = 2; // channels
        fixed[19] = 16; // sample size
        fixed[24..28].copy_from_slice(&(rate << 16).to_be_bytes());
        let entry = boxed(b"enca", &[fixed, config, sinf].concat());
        let mut stsd = (entries as u32).to_be_bytes().to_vec();
        (0..entries).for_each(|_| stsd.extend(&entry));
        let stbl = boxed(b"stbl", &full(b"stsd", &stsd));
        let mut mdhd = vec![0u8; 8];
        mdhd.extend(44100u32.to_be_bytes());
        mdhd.extend([0u8; 8]);
        let mdia = boxed(b"mdia", &[full(b"mdhd", &mdhd), boxed(b"minf", &stbl)].concat());
        let trex = full(b"trex", &[&1u32.to_be_bytes()[..], &1u32.to_be_bytes(), &4096u32.to_be_bytes(), &[0u8; 8]].concat());
        let moov = boxed(b"moov", &[boxed(b"trak", &mdia), full(b"pssh", &[7u8; 20]), boxed(b"mvex", &trex)].concat());
        [boxed(b"ftyp", b"iso5\0\0\0\0isom"), moov].concat()
    }

    /// One `moof`+`mdat` with two runs of `sizes`, protected with a full-sample subsample table.
    fn protected_fragment(sizes: &[u32]) -> Vec<u8> {
        let (first, second) = sizes.split_at(sizes.len() / 2);
        let senc_body = {
            let mut b = (sizes.len() as u32).to_be_bytes().to_vec();
            for s in sizes {
                b.extend(1u16.to_be_bytes());
                b.extend(0u16.to_be_bytes());
                b.extend(s.to_be_bytes());
            }
            b
        };
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
            let tfhd = boxed(b"tfhd", &[&[0u8, 2, 0, 2][..], &1u32.to_be_bytes(), &2u32.to_be_bytes()].concat());
            let senc = boxed(b"senc", &[&[0u8, 0, 0, 2][..], &senc_body].concat());
            let saiz = full(b"saiz", &[8, 0, 0, 0, sizes.len() as u8]);
            let traf = boxed(b"traf", &[tfhd, senc, saiz, run(first, Some(data_offset)), run(second, None)].concat());
            boxed(b"moof", &traf)
        };
        let moof_len = build(0).len() as u32;
        let payload: Vec<u8> = (0..sizes.iter().sum::<u32>()).map(|i| (i % 251) as u8).collect();
        [build(moof_len + 8), boxed(b"mdat", &payload)].concat()
    }

    #[test]
    fn dolby_atmos_init_parses_and_clears_like_alac() {
        let init = protected_atmos_init(2);
        let parsed = parse_init(&init).unwrap();
        assert_eq!(&parsed.codec, b"ec-3");
        assert_eq!(parsed.cookie, DEC3.to_vec());
        assert_eq!((parsed.sample_rate, parsed.channels, parsed.bit_depth), (48_000, 2, 16));
        let clear = clear_init(&init).unwrap();
        for needle in [&b"enca"[..], b"sinf", b"frma", b"tenc", b"pssh"] {
            assert!(!clear.windows(4).any(|w| w == needle), "{:?} survived", std::str::from_utf8(needle));
        }
        assert!(clear.windows(4).any(|w| w == b"ec-3") && clear.windows(4).any(|w| w == b"dec3"));
        assert_eq!(parse_init(&clear).unwrap().cookie, DEC3.to_vec());
        // Its fragments decrypt through the same path as ALAC's.
        let fragment = parse_fragment(&protected_fragment(&[100, 200, 300, 400]), &parsed).unwrap();
        assert_eq!(fragment.samples.len(), 4);
    }

    #[test]
    fn dolby_atmos_muxes_into_an_m4a_with_its_dec3() {
        let dir = std::env::temp_dir().join(format!("cadence-ec3-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("atmos.m4a");
        let spec = crate::TrackSpec { codec: *b"ec-3", cookie: DEC3.to_vec(), channels: 2, sample_rate: 48_000, bit_depth: 16 };
        let mut muxer = crate::Mp4Muxer::create(&path, spec.clone()).unwrap();
        muxer.add_sample(&[0x0B, 0x77, 1, 2, 3], 1536).unwrap();
        muxer.finish().unwrap();
        let file = std::fs::read(&path).unwrap();
        std::fs::remove_dir_all(&dir).ok();
        let entry = file.windows(4).position(|w| w == b"ec-3").expect("ec-3 sample entry");
        let dec3 = file.windows(4).position(|w| w == b"dec3").expect("dec3 box");
        assert!(dec3 > entry);
        assert_eq!(&file[dec3 + 4..dec3 + 4 + DEC3.len()], &DEC3);
        let parsed = parse_init(&file[file.windows(4).position(|w| w == b"moov").unwrap() - 4..]).unwrap();
        assert_eq!((&parsed.codec, parsed.cookie.clone(), parsed.sample_rate), (b"ec-3", DEC3.to_vec(), 48_000));
        // A dec3 too short to be real is refused rather than written.
        assert!(crate::Mp4Muxer::create(&path, crate::TrackSpec { cookie: vec![0x18], ..spec }).is_err());
    }

    #[test]
    fn clear_init_unwraps_every_protected_entry() {
        let init = protected_init(2);
        let clear = clear_init(&init).unwrap();
        assert!(clear.len() < init.len());
        for needle in [&b"enca"[..], b"sinf", b"frma", b"tenc", b"pssh"] {
            assert!(!clear.windows(4).any(|w| w == needle), "{:?} survived", std::str::from_utf8(needle));
        }
        // Still one entry per key, and the codec parameters are unchanged.
        let stsd_at = clear.windows(4).position(|w| w == b"stsd").unwrap();
        assert_eq!(&clear[stsd_at + 8..stsd_at + 12], &2u32.to_be_bytes());
        let (before, after) = (parse_init(&init).unwrap(), parse_init(&clear).unwrap());
        assert_eq!(after.cookie, before.cookie);
        assert_eq!((after.sample_rate, after.channels, after.bit_depth, after.timescale), (44100, 2, 24, 44100));
        assert_eq!(after.per_sample_iv_size, 0);
        // Every box size up the tree was recomputed: the file walks cleanly to its end.
        let top = children(&clear);
        assert_eq!(top.last().map(|b| b.body_start + b.body.len()), Some(clear.len()));
    }

    #[test]
    fn clear_init_rejects_a_truncated_init() {
        let init = protected_init(1);
        assert_eq!(clear_init(&init[..init.len() - 3]), Err(ParseError::Truncated("init segment box")));
    }

    #[test]
    fn clear_fragment_decrypts_in_place_and_blanks_encryption_boxes() {
        let init = parse_init(&protected_init(2)).unwrap();
        let sizes = [5u32, 7, 3, 11, 2];
        let segment = protected_fragment(&sizes);
        let clear = clear_fragment(&segment, &init, |buf| {
            buf.iter_mut().for_each(|b| *b ^= 0x5A);
            Ok(())
        })
        .unwrap();

        assert_eq!(clear.len(), segment.len());
        let before = parse_fragment(&segment, &init).unwrap();
        // The clear fragment parses to the same sample layout, minus the subsample tables.
        let after = parse_fragment(&clear, &init).unwrap();
        assert_eq!(after.samples.len(), sizes.len());
        for (a, b) in after.samples.iter().zip(&before.samples) {
            assert_eq!((a.offset, a.size, a.duration), (b.offset, b.size, b.duration));
            assert!(a.subsamples.is_empty());
        }
        // Every payload byte was transformed exactly once; nothing outside the samples moved.
        let payload_at = before.samples[0].offset;
        assert_eq!(&clear[..payload_at - 8], &{
            let mut head = segment[..payload_at - 8].to_vec();
            for needle in [&b"senc"[..], b"saiz"] {
                let at = head.windows(4).position(|w| w == needle).unwrap();
                head[at..at + 4].copy_from_slice(b"free");
            }
            head
        }[..]);
        assert!(clear[payload_at..].iter().zip(&segment[payload_at..]).all(|(c, s)| *c == *s ^ 0x5A));
    }

    #[test]
    fn clear_segment_handles_several_fragments_and_passes_other_boxes_through() {
        let init = parse_init(&protected_init(2)).unwrap();
        let (a, b) = (protected_fragment(&[6, 6]), protected_fragment(&[9, 4, 4]));
        let styp = boxed(b"styp", b"msdh\0\0\0\0");
        let segment = [styp.clone(), a.clone(), b.clone()].concat();
        let xor = |buf: &mut [u8]| {
            buf.iter_mut().for_each(|v| *v ^= 0x5A);
            Ok(())
        };
        let clear = clear_segment(&segment, &init, xor).unwrap();
        let expected = [styp, clear_fragment(&a, &init, xor).unwrap(), clear_fragment(&b, &init, xor).unwrap()].concat();
        assert_eq!(clear, expected);
        assert_eq!(clear.len(), segment.len());
        // A segment cut short must fail rather than come back partly decrypted.
        assert!(matches!(
            clear_segment(&segment[..segment.len() - 1], &init, xor),
            Err(ClearError::Parse(ParseError::Truncated("media segment box")))
        ));
    }

    #[test]
    fn clear_fragment_reports_a_decryptor_failure() {
        let init = parse_init(&protected_init(1)).unwrap();
        let segment = protected_fragment(&[4, 4]);
        let result = clear_fragment(&segment, &init, |_| Err("bad key".to_string()));
        assert!(matches!(result, Err(ClearError::Decrypt(message)) if message == "bad key"));
    }

    #[test]
    fn sidx_indexes_segments_from_the_end_of_the_box() {
        let sidx = build_sidx(44100, &[(2_616_851, 659_456), (1_177_792, 326_849)]);
        assert_eq!(sidx.len(), 12 + 20 + 2 * 12);
        assert_eq!(&sidx[4..8], b"sidx");
        assert_eq!(&sidx[16..20], &44100u32.to_be_bytes());
        assert_eq!(&sidx[30..32], &2u16.to_be_bytes());
        assert_eq!(&sidx[32..36], &2_616_851u32.to_be_bytes());
        assert_eq!(&sidx[36..40], &659_456u32.to_be_bytes());
        assert_eq!(&sidx[40..44], &0x9000_0000u32.to_be_bytes());
    }
}
