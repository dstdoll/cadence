//! Progressive M4A writer for ALAC, AAC and Dolby (E-AC-3, AC-3): `ftyp`, one `mdat`, then `moov`
//! with full sample tables.

use std::fs::File;
use std::io::{self, BufWriter, Seek, SeekFrom, Write};
use std::path::Path;

use crate::boxes::boxed;

/// Codec parameters of the single audio track.
#[derive(Debug, Clone)]
pub struct TrackSpec {
    /// `alac`, `mp4a`, `ec-3` or `ac-3`.
    pub codec: [u8; 4],
    /// ALAC: the 24-byte `ALACSpecificConfig`. AAC: the `esds` box body, version/flags included.
    /// E-AC-3 / AC-3: the `dec3` / `dac3` box body.
    pub cookie: Vec<u8>,
    pub channels: u16,
    pub sample_rate: u32,
    pub bit_depth: u16,
}

/// iTunes-style tags written to `moov/udta/meta/ilst`. Absent fields write nothing.
#[derive(Debug, Clone, Default)]
pub struct TrackMetadata {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album_artist: Option<String>,
    pub album: Option<String>,
    pub genre: Option<String>,
    pub year: Option<String>,
    pub comment: Option<String>,
    pub lyrics: Option<String>,
    pub copyright: Option<String>,
    pub encoder: Option<String>,
    /// `(number, total)`; a total of 0 means unknown.
    pub track: Option<(u16, u16)>,
    pub disc: Option<(u16, u16)>,
    pub compilation: Option<bool>,
    pub cover: Option<Vec<u8>>,
    pub cover_mime: Option<String>,
}

const FTYP: &[u8] = b"\x00\x00\x00\x1cftypM4A \x00\x00\x00\x00M4A mp42isom";

/// Streams samples into `mdat` and writes the sample tables on [`Mp4Muxer::finish`].
///
/// The file layout is fixed (`ftyp`, `mdat`, `moov`, a single chunk) because the caller's
/// faststart pass relocates `moov` by patching the one `stco` entry.
pub struct Mp4Muxer {
    out: BufWriter<File>,
    spec: TrackSpec,
    metadata: Option<TrackMetadata>,
    sizes: Vec<u32>,
    /// Run-length `stts`: `(sample count, duration)`.
    durations: Vec<(u32, u32)>,
    total_duration: u64,
    payload: u64,
}

impl Mp4Muxer {
    pub fn create(path: &Path, spec: TrackSpec) -> io::Result<Self> {
        match &spec.codec {
            b"alac" if spec.cookie.len() != 24 => return Err(invalid("ALAC config must be 24 bytes")),
            b"mp4a" if spec.cookie.len() < 12 => return Err(invalid("AAC esds is too short")),
            b"ec-3" if spec.cookie.len() < 5 => return Err(invalid("E-AC-3 dec3 is too short")),
            b"ac-3" if spec.cookie.len() < 3 => return Err(invalid("AC-3 dac3 is too short")),
            b"alac" | b"mp4a" | b"ec-3" | b"ac-3" => {}
            _ => return Err(invalid("unsupported audio format")),
        }
        if spec.sample_rate == 0 {
            return Err(invalid("sample rate is zero"));
        }
        let mut out = BufWriter::with_capacity(1 << 20, File::create(path)?);
        out.write_all(FTYP)?;
        out.write_all(&[0, 0, 0, 0])?; // mdat size, patched in finish()
        out.write_all(b"mdat")?;
        Ok(Self { out, spec, metadata: None, sizes: Vec::new(), durations: Vec::new(), total_duration: 0, payload: 0 })
    }

    pub fn set_metadata(&mut self, metadata: TrackMetadata) {
        self.metadata = Some(metadata);
    }

    /// Appends one audio frame. `duration` is in samples (the track timescale is the sample rate).
    pub fn add_sample(&mut self, data: &[u8], duration: u32) -> io::Result<()> {
        if data.is_empty() {
            return Err(invalid("empty sample"));
        }
        if duration == 0 {
            return Err(invalid("sample duration is zero"));
        }
        self.out.write_all(data)?;
        self.sizes.push(data.len() as u32);
        self.payload += data.len() as u64;
        self.total_duration += u64::from(duration);
        match self.durations.last_mut() {
            Some((count, last)) if *last == duration => *count += 1,
            _ => self.durations.push((1, duration)),
        }
        Ok(())
    }

    pub fn finish(mut self) -> io::Result<()> {
        if self.sizes.is_empty() {
            return Err(invalid("no samples were written"));
        }
        let mdat_size = u32::try_from(self.payload + 8).map_err(|_| invalid("mdat larger than 4 GiB"))?;
        let duration = u32::try_from(self.total_duration).map_err(|_| invalid("track duration overflows mdhd"))?;
        let moov = self.moov(duration);
        self.out.write_all(&moov)?;
        self.out.flush()?;
        let mut file = self.out.into_inner().map_err(|e| e.into_error())?;
        file.seek(SeekFrom::Start(FTYP.len() as u64))?;
        file.write_all(&mdat_size.to_be_bytes())?;
        file.sync_all()
    }

    fn moov(&self, duration: u32) -> Vec<u8> {
        let rate = self.spec.sample_rate;
        let movie_duration = (u64::from(duration) * 1000 / u64::from(rate)) as u32;
        let identity_matrix: [u32; 9] = [0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000];

        let mut mvhd = vec![0u8; 12]; // version/flags, creation, modification
        mvhd.extend(1000u32.to_be_bytes());
        mvhd.extend(movie_duration.to_be_bytes());
        mvhd.extend(0x0001_0000u32.to_be_bytes()); // rate 1.0
        mvhd.extend(0x0100u16.to_be_bytes()); // volume 1.0
        mvhd.extend([0u8; 10]);
        identity_matrix.iter().for_each(|v| mvhd.extend(v.to_be_bytes()));
        mvhd.extend([0u8; 24]);
        mvhd.extend(2u32.to_be_bytes()); // next track id

        let mut tkhd = vec![0, 0, 0, 3]; // enabled, in movie
        tkhd.extend([0u8; 8]);
        tkhd.extend(1u32.to_be_bytes()); // track id
        tkhd.extend([0u8; 4]);
        tkhd.extend(movie_duration.to_be_bytes());
        tkhd.extend([0u8; 8]);
        tkhd.extend([0u8; 4]); // layer, alternate group
        tkhd.extend(0x0100u16.to_be_bytes());
        tkhd.extend([0u8; 2]);
        identity_matrix.iter().for_each(|v| tkhd.extend(v.to_be_bytes()));
        tkhd.extend([0u8; 8]); // width, height

        let mut mdhd = vec![0u8; 12];
        mdhd.extend(rate.to_be_bytes());
        mdhd.extend(duration.to_be_bytes());
        mdhd.extend(0x55C4u16.to_be_bytes()); // language "und"
        mdhd.extend([0u8; 2]);

        let mut hdlr = vec![0u8; 8];
        hdlr.extend(b"soun");
        hdlr.extend([0u8; 12]);
        hdlr.extend(b"SoundHandler\0");

        // The decoder configuration box: `alac` (version/flags + config), or `esds`, `dec3`, `dac3`
        // stored whole.
        let config = match &self.spec.codec {
            b"mp4a" => boxed(b"esds", &self.spec.cookie),
            b"ec-3" => boxed(b"dec3", &self.spec.cookie),
            b"ac-3" => boxed(b"dac3", &self.spec.cookie),
            _ => {
                let mut body = vec![0u8; 4];
                body.extend(&self.spec.cookie);
                boxed(b"alac", &body)
            }
        };
        let mut entry = vec![0u8; 6];
        entry.extend(1u16.to_be_bytes()); // data reference index
        entry.extend([0u8; 8]);
        entry.extend(self.spec.channels.to_be_bytes());
        entry.extend(self.spec.bit_depth.to_be_bytes());
        entry.extend([0u8; 4]);
        // 16.16 fixed point holds rates up to 65535 Hz; above that decoders use the ALAC config.
        entry.extend((if rate <= 0xFFFF { rate << 16 } else { 0 }).to_be_bytes());
        entry.extend(config);
        let mut stsd = vec![0u8; 4];
        stsd.extend(1u32.to_be_bytes());
        stsd.extend(boxed(&self.spec.codec, &entry));

        let mut stts = vec![0u8; 4];
        stts.extend((self.durations.len() as u32).to_be_bytes());
        for (count, delta) in &self.durations {
            stts.extend(count.to_be_bytes());
            stts.extend(delta.to_be_bytes());
        }

        let mut stsc = vec![0u8; 4];
        stsc.extend(1u32.to_be_bytes());
        stsc.extend(1u32.to_be_bytes()); // first chunk
        stsc.extend((self.sizes.len() as u32).to_be_bytes()); // samples per chunk
        stsc.extend(1u32.to_be_bytes()); // sample description index

        let mut stsz = vec![0u8; 8]; // version/flags, uniform size 0 = per-sample table
        stsz.extend((self.sizes.len() as u32).to_be_bytes());
        self.sizes.iter().for_each(|s| stsz.extend(s.to_be_bytes()));

        let mut stco = vec![0u8; 4];
        stco.extend(1u32.to_be_bytes());
        stco.extend((FTYP.len() as u32 + 8).to_be_bytes());

        let stbl = [boxed(b"stsd", &stsd), boxed(b"stts", &stts), boxed(b"stsc", &stsc), boxed(b"stsz", &stsz), boxed(b"stco", &stco)].concat();
        let mut dref = vec![0u8; 4];
        dref.extend(1u32.to_be_bytes());
        dref.extend(boxed(b"url ", &[0, 0, 0, 1])); // self-contained
        let minf = [boxed(b"smhd", &[0u8; 8]), boxed(b"dinf", &boxed(b"dref", &dref)), boxed(b"stbl", &stbl)].concat();
        let mdia = [boxed(b"mdhd", &mdhd), boxed(b"hdlr", &hdlr), boxed(b"minf", &minf)].concat();
        let trak = [boxed(b"tkhd", &tkhd), boxed(b"mdia", &mdia)].concat();

        let mut moov = [boxed(b"mvhd", &mvhd), boxed(b"trak", &trak)].concat();
        if let Some(ilst) = self.metadata.as_ref().map(ilst).filter(|items| !items.is_empty()) {
            let mut meta_hdlr = vec![0u8; 8];
            meta_hdlr.extend(b"mdirappl");
            meta_hdlr.extend([0u8; 9]);
            let mut meta = vec![0u8; 4];
            meta.extend(boxed(b"hdlr", &meta_hdlr));
            meta.extend(boxed(b"ilst", &ilst));
            moov.extend(boxed(b"udta", &boxed(b"meta", &meta)));
        }
        boxed(b"moov", &moov)
    }
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.to_string())
}

/// One `ilst` item: the atom wrapping a `data` box of the given well-known type.
fn item(kind: &[u8; 4], data_type: u32, payload: &[u8]) -> Vec<u8> {
    let mut data = Vec::with_capacity(payload.len() + 8);
    data.extend(data_type.to_be_bytes());
    data.extend([0u8; 4]); // locale
    data.extend(payload);
    boxed(kind, &boxed(b"data", &data))
}

const UTF8: u32 = 1;
const IMPLICIT: u32 = 0;
const JPEG: u32 = 13;
const PNG: u32 = 14;
const INTEGER: u32 = 21;

fn ilst(m: &TrackMetadata) -> Vec<u8> {
    let mut out = Vec::new();
    let text: [(&[u8; 4], &Option<String>); 10] = [
        (b"\xA9nam", &m.title),
        (b"\xA9ART", &m.artist),
        (b"aART", &m.album_artist),
        (b"\xA9alb", &m.album),
        (b"\xA9gen", &m.genre),
        (b"\xA9day", &m.year),
        (b"\xA9cmt", &m.comment),
        (b"\xA9lyr", &m.lyrics),
        (b"cprt", &m.copyright),
        (b"\xA9too", &m.encoder),
    ];
    for (kind, value) in text {
        if let Some(value) = value.as_deref().filter(|v| !v.is_empty()) {
            out.extend(item(kind, UTF8, value.as_bytes()));
        }
    }
    if let Some((number, total)) = m.track {
        let mut payload = vec![0u8; 2];
        payload.extend(number.to_be_bytes());
        payload.extend(total.to_be_bytes());
        payload.extend([0u8; 2]);
        out.extend(item(b"trkn", IMPLICIT, &payload));
    }
    if let Some((number, total)) = m.disc {
        let mut payload = vec![0u8; 2];
        payload.extend(number.to_be_bytes());
        payload.extend(total.to_be_bytes());
        out.extend(item(b"disk", IMPLICIT, &payload));
    }
    if let Some(compilation) = m.compilation {
        out.extend(item(b"cpil", INTEGER, &[u8::from(compilation)]));
    }
    if let Some(cover) = m.cover.as_deref().filter(|c| !c.is_empty()) {
        let png = m.cover_mime.as_deref().is_some_and(|mime| mime.contains("png")) || cover.starts_with(b"\x89PNG");
        out.extend(item(b"covr", if png { PNG } else { JPEG }, cover));
    }
    out
}
