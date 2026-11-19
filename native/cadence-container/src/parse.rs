//! Init (`moov`) and fragment (`moof` + `mdat`) parsing.

use crate::boxes::{child, children, descend, u16_at, u32_at};

#[derive(Debug, PartialEq, Eq)]
pub enum ParseError {
    /// A required box is absent; the string is its path.
    Missing(&'static str),
    /// A box is shorter than its own fields.
    Truncated(&'static str),
    /// The sample entry is not ALAC, AAC, E-AC-3 or AC-3 (clear, or protected `enca` wrapping one of them).
    UnsupportedCodec([u8; 4]),
    /// `tfhd` gives an absolute base offset, which cannot be resolved inside one segment.
    AbsoluteBaseOffset,
    /// No `trun`, `tfhd` or `trex` value provides this sample's duration.
    NoSampleDuration { sample: usize },
    /// No `trun`, `tfhd` or `trex` value provides this sample's size.
    NoSampleSize { sample: usize },
    /// `senc` describes a different number of samples than the `trun` boxes.
    EncryptionInfoMismatch { senc: usize, samples: usize },
    /// A sample's bytes fall outside the segment.
    SampleOutOfRange { sample: usize },
}

/// What the muxer and the fragment parser need from the init segment.
#[derive(Debug, Clone)]
pub struct InitSegment {
    /// The audio format: `alac`, `mp4a` (AAC), `ec-3` (E-AC-3, Dolby Atmos when JOC) or `ac-3`.
    pub codec: [u8; 4],
    /// The decoder configuration. ALAC: the 24-byte `ALACSpecificConfig` (the `alac` box payload
    /// after version/flags). AAC: the whole `esds` box body, version/flags included. E-AC-3 and
    /// AC-3: the whole `dec3` / `dac3` box body.
    pub cookie: Vec<u8>,
    pub channels: u16,
    pub sample_rate: u32,
    pub bit_depth: u16,
    /// Media timescale (`mdhd`): the unit of every sample duration in the fragments.
    pub timescale: u32,
    /// `trex` defaults, used when neither `trun` nor `tfhd` carries the value.
    pub default_sample_duration: u32,
    pub default_sample_size: u32,
    /// `tenc` per-sample IV size: how many bytes precede each `senc` entry's subsample table.
    pub per_sample_iv_size: u8,
}

/// One sample of a fragment. `offset` is from the start of the segment slice (the `moof`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sample {
    pub offset: usize,
    pub size: u32,
    pub duration: u32,
    /// `(clear, protected)` byte counts from `senc`; empty means the whole sample is protected.
    pub subsamples: Vec<(u32, u32)>,
}

#[derive(Debug, Clone)]
pub struct Fragment {
    pub samples: Vec<Sample>,
}

/// Parses everything up to and including `moov`.
pub fn parse_init(data: &[u8]) -> Result<InitSegment, ParseError> {
    let moov = child(data, b"moov").ok_or(ParseError::Missing("moov"))?.body;
    let mdia = descend(moov, &[b"trak", b"mdia"]).ok_or(ParseError::Missing("moov/trak/mdia"))?;

    let mdhd = child(mdia, b"mdhd").ok_or(ParseError::Missing("mdhd"))?.body;
    let version = *mdhd.first().ok_or(ParseError::Truncated("mdhd"))?;
    let timescale = u32_at(mdhd, if version == 1 { 20 } else { 12 }).ok_or(ParseError::Truncated("mdhd"))?;

    let stsd = descend(mdia, &[b"minf", b"stbl", b"stsd"]).ok_or(ParseError::Missing("stsd"))?;
    // Full-box header (4) + entry count (4), then the sample entries. Apple lists one entry per
    // key (prefetch and content); they describe the same audio, so the first one is enough.
    let entry = children(stsd.get(8..).ok_or(ParseError::Truncated("stsd"))?)
        .into_iter()
        .next()
        .ok_or(ParseError::Missing("stsd entry"))?;
    // AudioSampleEntry: 6 reserved, data-ref index, 8 reserved, channels, sample size, 4, rate.
    let fixed = entry.body.get(..28).ok_or(ParseError::Truncated("audio sample entry"))?;
    let extensions = &entry.body[28..];
    let mut codec = entry.kind;
    if &entry.kind == b"enca" {
        let format = descend(extensions, &[b"sinf", b"frma"]).ok_or(ParseError::Missing("sinf/frma"))?;
        codec.copy_from_slice(format.get(..4).ok_or(ParseError::Truncated("frma"))?);
    }
    let (cookie, channels, sample_rate, bit_depth) = match &codec {
        b"alac" => {
            let config = child(extensions, b"alac").ok_or(ParseError::Missing("alac config"))?.body;
            let cookie = config.get(4..28).ok_or(ParseError::Truncated("alac config"))?.to_vec();
            // ALACSpecificConfig: frameLength(4) compatVersion(1) bitDepth(1) pb mb kb numChannels(1)
            // maxRun(2) maxFrameBytes(4) avgBitRate(4) sampleRate(4).
            let rate = u32_at(&cookie, 20).ok_or(ParseError::Truncated("alac config"))?;
            let (channels, depth) = (u16::from(cookie[9]), u16::from(cookie[5]));
            (cookie, channels, rate, depth)
        }
        b"mp4a" => {
            let esds = child(extensions, b"esds").ok_or(ParseError::Missing("esds"))?.body;
            // AAC has no bit depth of its own; the entry's fields carry channels and the 16.16 rate.
            let channels = u16_at(fixed, 16).ok_or(ParseError::Truncated("audio sample entry"))?;
            let rate = u32_at(fixed, 24).ok_or(ParseError::Truncated("audio sample entry"))? >> 16;
            (esds.to_vec(), channels, rate, 16)
        }
        b"ec-3" | b"ac-3" => {
            // Dolby: the `dec3`/`dac3` box is the decoder configuration, kept whole for the muxer.
            // Decoders read the real channel layout from it and the bitstream; the entry's fields
            // carry the nominal channels and the 16.16 rate.
            let config = if &codec == b"ec-3" {
                child(extensions, b"dec3").ok_or(ParseError::Missing("dec3"))?.body
            } else {
                child(extensions, b"dac3").ok_or(ParseError::Missing("dac3"))?.body
            };
            let channels = u16_at(fixed, 16).ok_or(ParseError::Truncated("audio sample entry"))?;
            let rate = u32_at(fixed, 24).ok_or(ParseError::Truncated("audio sample entry"))? >> 16;
            (config.to_vec(), channels, rate, 16)
        }
        _ => return Err(ParseError::UnsupportedCodec(codec)),
    };

    let per_sample_iv_size = descend(extensions, &[b"sinf", b"schi", b"tenc"])
        .and_then(|tenc| tenc.get(7).copied())
        .unwrap_or(0);

    let (default_sample_duration, default_sample_size) = match descend(moov, &[b"mvex", b"trex"]) {
        // version/flags, track id, default description index, duration, size, flags.
        Some(trex) => (
            u32_at(trex, 12).ok_or(ParseError::Truncated("trex"))?,
            u32_at(trex, 16).ok_or(ParseError::Truncated("trex"))?,
        ),
        None => (0, 0),
    };

    Ok(InitSegment {
        codec,
        cookie,
        channels,
        sample_rate,
        bit_depth,
        timescale,
        default_sample_duration,
        default_sample_size,
        per_sample_iv_size,
    })
}

/// Parses one segment (`moof` followed by its `mdat`) into samples, across every `trun`.
pub fn parse_fragment(segment: &[u8], init: &InitSegment) -> Result<Fragment, ParseError> {
    let moof = child(segment, b"moof").ok_or(ParseError::Missing("moof"))?;
    let mut samples: Vec<Sample> = Vec::new();

    for traf in children(moof.body).into_iter().filter(|b| &b.kind == b"traf") {
        let tfhd = child(traf.body, b"tfhd").ok_or(ParseError::Missing("tfhd"))?.body;
        let tfhd_flags = u32_at(tfhd, 0).ok_or(ParseError::Truncated("tfhd"))? & 0x00FF_FFFF;
        if tfhd_flags & 0x1 != 0 {
            return Err(ParseError::AbsoluteBaseOffset);
        }
        let mut at = 8usize; // version/flags + track id
        if tfhd_flags & 0x2 != 0 {
            at += 4; // sample description index: which key, not which layout
        }
        let mut default_duration = init.default_sample_duration;
        let mut default_size = init.default_sample_size;
        if tfhd_flags & 0x8 != 0 {
            default_duration = u32_at(tfhd, at).ok_or(ParseError::Truncated("tfhd"))?;
            at += 4;
        }
        if tfhd_flags & 0x10 != 0 {
            default_size = u32_at(tfhd, at).ok_or(ParseError::Truncated("tfhd"))?;
        }

        let first = samples.len();
        // Without a data offset a run continues where the previous one ended; the first run of
        // a traf with no offset starts right after the moof (default-base-is-moof layout).
        let mut cursor = moof.start + (moof.body_start - moof.start) + moof.body.len();
        for trun in children(traf.body).into_iter().filter(|b| &b.kind == b"trun") {
            let body = trun.body;
            let flags = u32_at(body, 0).ok_or(ParseError::Truncated("trun"))? & 0x00FF_FFFF;
            let count = u32_at(body, 4).ok_or(ParseError::Truncated("trun"))? as usize;
            let mut at = 8usize;
            if flags & 0x1 != 0 {
                let offset = u32_at(body, at).ok_or(ParseError::Truncated("trun"))? as i32;
                cursor = (moof.start as i64 + i64::from(offset)) as usize;
                at += 4;
            }
            if flags & 0x4 != 0 {
                at += 4; // first sample flags
            }
            for _ in 0..count {
                let index = samples.len();
                let duration = if flags & 0x100 != 0 {
                    let v = u32_at(body, at).ok_or(ParseError::Truncated("trun"))?;
                    at += 4;
                    v
                } else {
                    default_duration
                };
                let size = if flags & 0x200 != 0 {
                    let v = u32_at(body, at).ok_or(ParseError::Truncated("trun"))?;
                    at += 4;
                    v
                } else {
                    default_size
                };
                if flags & 0x400 != 0 {
                    at += 4;
                }
                if flags & 0x800 != 0 {
                    at += 4;
                }
                if duration == 0 {
                    return Err(ParseError::NoSampleDuration { sample: index });
                }
                if size == 0 {
                    return Err(ParseError::NoSampleSize { sample: index });
                }
                let end = cursor.checked_add(size as usize).ok_or(ParseError::SampleOutOfRange { sample: index })?;
                if end > segment.len() {
                    return Err(ParseError::SampleOutOfRange { sample: index });
                }
                samples.push(Sample { offset: cursor, size, duration, subsamples: Vec::new() });
                cursor = end;
            }
        }

        if let Some(senc) = child(traf.body, b"senc") {
            let body = senc.body;
            let flags = u32_at(body, 0).ok_or(ParseError::Truncated("senc"))? & 0x00FF_FFFF;
            let count = u32_at(body, 4).ok_or(ParseError::Truncated("senc"))? as usize;
            let in_traf = samples.len() - first;
            if count != in_traf {
                return Err(ParseError::EncryptionInfoMismatch { senc: count, samples: in_traf });
            }
            let mut at = 8usize;
            for sample in &mut samples[first..] {
                at += usize::from(init.per_sample_iv_size);
                if flags & 0x2 == 0 {
                    continue;
                }
                let entries = u16_at(body, at).ok_or(ParseError::Truncated("senc"))?;
                at += 2;
                for _ in 0..entries {
                    let clear = u16_at(body, at).ok_or(ParseError::Truncated("senc"))?;
                    let protected = u32_at(body, at + 2).ok_or(ParseError::Truncated("senc"))?;
                    at += 6;
                    sample.subsamples.push((u32::from(clear), protected));
                }
            }
        }
    }

    if samples.is_empty() {
        return Err(ParseError::Missing("trun samples"));
    }
    Ok(Fragment { samples })
}
