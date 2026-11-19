//! Pure-Rust ALAC packet decoder with zero-allocation PCM16 / PCM_FLOAT output.

use alac::{Decoder, StreamInfo};
use std::fmt;

/// Errors returned by [`AlacSession`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AlacError {
    InvalidCookie(&'static str),
    BufferTooSmall { needed: usize, available: usize },
    DecodeFailed(&'static str),
}

impl fmt::Display for AlacError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AlacError::InvalidCookie(msg) => write!(f, "invalid ALAC magic cookie: {msg}"),
            AlacError::BufferTooSmall { needed, available } => {
                write!(f, "output buffer too small: needed {needed} bytes, got {available}")
            }
            AlacError::DecodeFailed(msg) => write!(f, "ALAC decode failed: {msg}"),
        }
    }
}

impl std::error::Error for AlacError {}

/// Stateful ALAC decoder session holding reusable `i32` scratch space so packet decoding
/// performs zero heap allocations per frame.
pub struct AlacSession {
    decoder: Decoder,
    scratch: Vec<i32>,
    channels: u8,
    sample_rate: u32,
    bit_depth: u8,
    frame_length: u32,
}

impl AlacSession {
    /// Creates a decoder from `ALACSpecificConfig` (24 bytes), a 28-byte version/flags-prefixed
    /// `alac` box payload, or a full `frma`/`alac` magic cookie.
    pub fn from_cookie(mut cookie: &[u8]) -> Result<Self, AlacError> {
        if cookie.len() == 28 && cookie[0..4] == [0, 0, 0, 0] {
            cookie = &cookie[4..];
        }
        let info = StreamInfo::from_cookie(cookie).map_err(|_| AlacError::InvalidCookie("malformed ALACSpecificConfig"))?;
        let bit_depth = info.bit_depth();
        if !matches!(bit_depth, 16 | 20 | 24 | 32) {
            return Err(AlacError::InvalidCookie("unsupported bit depth"));
        }
        let channels = info.channels();
        let frame_length = info.max_frames_per_packet();
        let sample_rate = info.sample_rate();
        if channels == 0 || frame_length == 0 || sample_rate == 0 {
            return Err(AlacError::InvalidCookie("zero channels, frame_length, or sample_rate"));
        }
        let max_samples = info.max_samples_per_packet() as usize;
        Ok(Self {
            decoder: Decoder::new(info),
            scratch: vec![0i32; max_samples],
            channels,
            sample_rate,
            bit_depth,
            frame_length,
        })
    }

    pub fn channels(&self) -> u8 {
        self.channels
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn bit_depth(&self) -> u8 {
        self.bit_depth
    }

    pub fn frame_length(&self) -> u32 {
        self.frame_length
    }

    /// Maximum PCM output size in bytes for one decoded packet.
    pub fn max_output_bytes(&self, float_out: bool) -> usize {
        self.scratch.len() * if float_out { 4 } else { 2 }
    }

    /// Decodes one ALAC access unit into little-endian signed 16-bit PCM (`out`), returning bytes written.
    pub fn decode_to_pcm16(&mut self, packet: &[u8], out: &mut [u8]) -> Result<usize, AlacError> {
        let samples = self
            .decoder
            .decode_packet(packet, &mut self.scratch)
            .map_err(|_| AlacError::DecodeFailed("corrupt or truncated packet"))?;
        let needed = samples.len() * 2;
        if out.len() < needed {
            return Err(AlacError::BufferTooSmall { needed, available: out.len() });
        }
        for (dst, &s) in out[..needed].chunks_exact_mut(2).zip(samples.iter()) {
            dst.copy_from_slice(&((s >> 16) as i16).to_le_bytes());
        }
        Ok(needed)
    }

    /// Decodes one ALAC access unit into little-endian 32-bit float PCM (`[-1.0, 1.0)`), returning bytes written.
    pub fn decode_to_f32(&mut self, packet: &[u8], out: &mut [u8]) -> Result<usize, AlacError> {
        const SCALE: f64 = 1.0 / 2_147_483_648.0; // 2^-31 for MSB-aligned i32 samples
        let samples = self
            .decoder
            .decode_packet(packet, &mut self.scratch)
            .map_err(|_| AlacError::DecodeFailed("corrupt or truncated packet"))?;
        let needed = samples.len() * 4;
        if out.len() < needed {
            return Err(AlacError::BufferTooSmall { needed, available: out.len() });
        }
        for (dst, &s) in out[..needed].chunks_exact_mut(4).zip(samples.iter()) {
            let f = (s as f64 * SCALE) as f32;
            dst.copy_from_slice(&f.to_le_bytes());
        }
        Ok(needed)
    }
}
