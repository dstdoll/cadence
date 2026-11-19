//! Non-fragmented FairPlay `.m4p` parser and clear `.m4a` muxer.

use std::fmt;
use std::path::Path;

use crate::boxes::{child, children, descend, u16_at, u32_at};
use crate::mux::{Mp4Muxer, TrackMetadata, TrackSpec};

#[derive(Debug)]
pub enum M4pError<E> {
    Invalid(&'static str),
    Io(std::io::Error),
    Decrypt(E),
}

impl<E: fmt::Display> fmt::Display for M4pError<E> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Invalid(msg) => write!(f, "invalid m4p: {msg}"),
            Self::Io(err) => write!(f, "m4p io: {err}"),
            Self::Decrypt(err) => write!(f, "m4p decrypt: {err}"),
        }
    }
}

impl<E: fmt::Debug + fmt::Display> std::error::Error for M4pError<E> {}

fn u64_at(buf: &[u8], off: usize) -> Option<u64> {
    buf.get(off..off + 8).map(|s| u64::from_be_bytes(s.try_into().unwrap()))
}

/// Parses a non-fragmented FairPlay `.m4p`, decrypts each sample in `mdat`, and writes a tagged
/// progressive `.m4a` via [Mp4Muxer].
pub fn mux_m4p<E, F>(
    data: &[u8],
    out_path: &Path,
    metadata: TrackMetadata,
    mut decrypt: F,
) -> Result<(), M4pError<E>>
where
    F: FnMut(&mut [u8]) -> Result<(), E>,
{
    let moov = child(data, b"moov").ok_or(M4pError::Invalid("missing moov"))?.body;
    let mdia = descend(moov, &[b"trak", b"mdia"]).ok_or(M4pError::Invalid("missing mdia"))?;

    let mdhd = child(mdia, b"mdhd").ok_or(M4pError::Invalid("missing mdhd"))?.body;
    let mdhd_ver = *mdhd.first().ok_or(M4pError::Invalid("truncated mdhd"))?;
    let timescale = u32_at(mdhd, if mdhd_ver == 1 { 20 } else { 12 })
        .ok_or(M4pError::Invalid("truncated mdhd timescale"))?;

    let stbl = descend(mdia, &[b"minf", b"stbl"]).ok_or(M4pError::Invalid("missing stbl"))?;
    let stsd = child(stbl, b"stsd").ok_or(M4pError::Invalid("missing stsd"))?.body;
    let entry = children(stsd.get(8..).ok_or(M4pError::Invalid("truncated stsd"))?)
        .into_iter()
        .next()
        .ok_or(M4pError::Invalid("missing stsd entry"))?;

    let fixed = entry.body.get(..28).ok_or(M4pError::Invalid("truncated sample entry"))?;
    let extensions = &entry.body[28..];
    let mut codec = entry.kind;
    if &entry.kind == b"enca" || &entry.kind == b"drms" {
        let frma = descend(extensions, &[b"sinf", b"frma"]).ok_or(M4pError::Invalid("missing sinf/frma"))?;
        codec.copy_from_slice(frma.get(..4).ok_or(M4pError::Invalid("truncated frma"))?);
    }
    if &codec != b"mp4a" {
        return Err(M4pError::Invalid("expected mp4a audio in m4p"));
    }
    let cookie = child(extensions, b"esds")
        .ok_or(M4pError::Invalid("missing esds"))?
        .body
        .to_vec();
    let channels = u16_at(fixed, 16).ok_or(M4pError::Invalid("channels"))?;
    let sample_rate = u16_at(fixed, 24).ok_or(M4pError::Invalid("sample_rate"))? as u32;

    let sizes = parse_stsz(child(stbl, b"stsz").ok_or(M4pError::Invalid("missing stsz"))?.body)?;
    let durations = parse_stts(
        child(stbl, b"stts").ok_or(M4pError::Invalid("missing stts"))?.body,
        sizes.len(),
    )?;
    let chunk_offsets = parse_chunk_offsets(stbl)?;
    let stsc = parse_stsc(child(stbl, b"stsc").ok_or(M4pError::Invalid("missing stsc"))?.body)?;

    let spec = TrackSpec {
        codec,
        cookie,
        channels,
        sample_rate,
        bit_depth: 16,
    };
    let mut muxer = Mp4Muxer::create(out_path, spec).map_err(M4pError::Io)?;
    muxer.set_metadata(metadata);

    let mut sample_idx = 0usize;
    let mut stsc_idx = 0usize;
    for (chunk_idx, &chunk_off) in chunk_offsets.iter().enumerate() {
        let chunk_num = (chunk_idx + 1) as u32;
        while stsc_idx + 1 < stsc.len() && chunk_num >= stsc[stsc_idx + 1].0 {
            stsc_idx += 1;
        }
        let samples_in_chunk = stsc
            .get(stsc_idx)
            .ok_or(M4pError::Invalid("empty stsc"))?
            .1 as usize;
        let mut off = chunk_off as usize;
        for _ in 0..samples_in_chunk {
            if sample_idx >= sizes.len() {
                break;
            }
            let sz = sizes[sample_idx] as usize;
            let end = off.checked_add(sz).ok_or(M4pError::Invalid("sample offset overflow"))?;
            let slice = data.get(off..end).ok_or(M4pError::Invalid("sample out of range"))?;
            let mut buf = slice.to_vec();
            decrypt(&mut buf).map_err(M4pError::Decrypt)?;
            let dur = if timescale == sample_rate {
                durations[sample_idx]
            } else {
                ((u64::from(durations[sample_idx]) * u64::from(sample_rate) + u64::from(timescale) / 2)
                    / u64::from(timescale))
                .max(1) as u32
            };
            muxer.add_sample(&buf, dur).map_err(M4pError::Io)?;
            off = end;
            sample_idx += 1;
        }
    }
    if sample_idx != sizes.len() {
        return Err(M4pError::Invalid("sample count mismatch"));
    }
    muxer.finish().map_err(M4pError::Io)
}

fn parse_stsz<E>(body: &[u8]) -> Result<Vec<u32>, M4pError<E>> {
    let uniform = u32_at(body, 4).ok_or(M4pError::Invalid("truncated stsz"))?;
    let count = u32_at(body, 8).ok_or(M4pError::Invalid("truncated stsz count"))? as usize;
    if uniform != 0 {
        return Ok(vec![uniform; count]);
    }
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        out.push(u32_at(body, 12 + i * 4).ok_or(M4pError::Invalid("truncated stsz entry"))?);
    }
    Ok(out)
}

fn parse_stts<E>(body: &[u8], total_samples: usize) -> Result<Vec<u32>, M4pError<E>> {
    let entries = u32_at(body, 4).ok_or(M4pError::Invalid("truncated stts"))? as usize;
    let mut out = Vec::with_capacity(total_samples);
    for i in 0..entries {
        let count = u32_at(body, 8 + i * 8).ok_or(M4pError::Invalid("truncated stts count"))? as usize;
        let delta = u32_at(body, 12 + i * 8).ok_or(M4pError::Invalid("truncated stts delta"))?;
        let take = count.min(total_samples.saturating_sub(out.len()));
        out.extend(std::iter::repeat(delta).take(take));
    }
    while out.len() < total_samples {
        out.push(*out.last().unwrap_or(&1024));
    }
    Ok(out)
}

fn parse_stsc<E>(body: &[u8]) -> Result<Vec<(u32, u32)>, M4pError<E>> {
    let entries = u32_at(body, 4).ok_or(M4pError::Invalid("truncated stsc"))? as usize;
    let mut out = Vec::with_capacity(entries);
    for i in 0..entries {
        let first_chunk = u32_at(body, 8 + i * 12).ok_or(M4pError::Invalid("truncated stsc chunk"))?;
        let per_chunk = u32_at(body, 12 + i * 12).ok_or(M4pError::Invalid("truncated stsc count"))?;
        out.push((first_chunk, per_chunk));
    }
    Ok(out)
}

fn parse_chunk_offsets<E>(stbl: &[u8]) -> Result<Vec<u64>, M4pError<E>> {
    if let Some(stco) = child(stbl, b"stco") {
        let count = u32_at(stco.body, 4).ok_or(M4pError::Invalid("truncated stco"))? as usize;
        let mut out = Vec::with_capacity(count);
        for i in 0..count {
            out.push(u32_at(stco.body, 8 + i * 4).ok_or(M4pError::Invalid("truncated stco entry"))? as u64);
        }
        return Ok(out);
    }
    if let Some(co64) = child(stbl, b"co64") {
        let count = u32_at(co64.body, 4).ok_or(M4pError::Invalid("truncated co64"))? as usize;
        let mut out = Vec::with_capacity(count);
        for i in 0..count {
            out.push(u64_at(co64.body, 8 + i * 8).ok_or(M4pError::Invalid("truncated co64 entry"))?);
        }
        return Ok(out);
    }
    Err(M4pError::Invalid("missing stco/co64"))
}
