//! Pure-Rust PCM bit-depth, FFT spectral bandwidth, and escape-frame repair helpers for ALAC.

use crate::{AlacError, AlacSession};
use std::f64::consts::PI;

const FFT_SIZE: usize = 4096;

/// Summary of 15-second prefetch ALAC audio analysis.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PcmAnalysis {
    pub effective_bits: u8,
    pub bandwidth_hz: u32,
    pub upsampled_suspect: bool,
    pub frames_analyzed: u32,
}

/// Decodes [frames] with the ALAC magic [cookie] and measures effective bit depth + spectral roll-off.
pub fn analyze_frames(cookie: &[u8], frames: &[&[u8]]) -> Result<PcmAnalysis, AlacError> {
    let mut session = AlacSession::from_cookie(cookie)?;
    let channels = session.channels as usize;
    let sample_rate = session.sample_rate;
    let container_bits = session.bit_depth;

    let mut or_mask: u32 = 0;
    let mut mono_buf: Vec<f64> = Vec::with_capacity(FFT_SIZE);
    let mut avg_power = vec![0.0_f64; FFT_SIZE / 2];
    let mut window_count: usize = 0;
    let mut frames_analyzed: u32 = 0;

    let hann: Vec<f64> = (0..FFT_SIZE)
        .map(|i| 0.5 * (1.0 - (2.0 * PI * (i as f64) / ((FFT_SIZE - 1) as f64)).cos()))
        .collect();

    for &pkt in frames {
        let samples = session
            .decoder
            .decode_packet(pkt, &mut session.scratch)
            .map_err(|_| AlacError::DecodeFailed("corrupt or truncated packet"))?;
        frames_analyzed += 1;
        for &s in samples.iter() {
            or_mask |= s as u32;
        }
        for ch_frame in samples.chunks_exact(channels) {
            let sum: f64 = ch_frame.iter().map(|&s| (s as f64) / 2_147_483_648.0).sum();
            mono_buf.push(sum / (channels as f64));
            if mono_buf.len() == FFT_SIZE {
                accumulate_fft(&mono_buf, &hann, &mut avg_power);
                mono_buf.clear();
                window_count += 1;
            }
        }
    }

    let effective_bits = compute_effective_bits(or_mask, container_bits);
    let (bandwidth_hz, upsampled_suspect) = if window_count > 0 {
        for p in avg_power.iter_mut() {
            *p /= window_count as f64;
        }
        estimate_bandwidth(&avg_power, sample_rate, effective_bits)
    } else {
        (sample_rate / 2, false)
    };

    Ok(PcmAnalysis {
        effective_bits,
        bandwidth_hz,
        upsampled_suspect,
        frames_analyzed,
    })
}

/// Analyzes pre-decoded MSB-aligned `i32` mono samples directly (used by unit tests and callers with PCM).
pub fn analyze_mono_i32(samples: &[i32], sample_rate: u32, container_bits: u8) -> PcmAnalysis {
    let mut or_mask: u32 = 0;
    for &s in samples {
        or_mask |= s as u32;
    }
    let effective_bits = compute_effective_bits(or_mask, container_bits);
    let hann: Vec<f64> = (0..FFT_SIZE)
        .map(|i| 0.5 * (1.0 - (2.0 * PI * (i as f64) / ((FFT_SIZE - 1) as f64)).cos()))
        .collect();
    let mut avg_power = vec![0.0_f64; FFT_SIZE / 2];
    let mut window_count = 0usize;
    for chunk in samples.chunks_exact(FFT_SIZE) {
        let mono: Vec<f64> = chunk.iter().map(|&s| (s as f64) / 2_147_483_648.0).collect();
        accumulate_fft(&mono, &hann, &mut avg_power);
        window_count += 1;
    }
    let (bandwidth_hz, upsampled_suspect) = if window_count > 0 {
        for p in avg_power.iter_mut() {
            *p /= window_count as f64;
        }
        estimate_bandwidth(&avg_power, sample_rate, effective_bits)
    } else {
        (sample_rate / 2, false)
    };
    PcmAnalysis {
        effective_bits,
        bandwidth_hz,
        upsampled_suspect,
        frames_analyzed: 0,
    }
}

fn compute_effective_bits(or_mask: u32, container_bits: u8) -> u8 {
    if or_mask == 0 {
        return container_bits;
    }
    let active = 32 - or_mask.trailing_zeros();
    let clamped = active.min(container_bits as u32) as u8;
    // Snap upward to standard PCM steps (16 / 20 / 24 / 32) so quiet LSBs don't under-report 24-bit as 23-bit.
    match clamped {
        0..=16 => 16,
        17..=20 => 20,
        21..=24 => 24,
        _ => 32,
    }
}

fn estimate_bandwidth(avg_power: &[f64], sample_rate: u32, effective_bits: u8) -> (u32, bool) {
    let mut peak = 1e-30_f64;
    for &p in avg_power {
        if p > peak {
            peak = p;
        }
    }
    let peak_db = 10.0 * peak.log10();
    // 20 dB above quantization noise floor, capped at -85 dB relative to signal peak.
    let quant_floor_db = -6.02 * (effective_bits as f64);
    let threshold_db = (quant_floor_db + 20.0).max(peak_db - 85.0);

    let mut max_bin = 0usize;
    for (k, &p) in avg_power.iter().enumerate().skip(1) {
        let db = 10.0 * p.max(1e-30).log10();
        if db >= threshold_db {
            max_bin = k;
        }
    }
    let bw = ((max_bin as f64) * (sample_rate as f64) / (FFT_SIZE as f64)).round() as u32;
    let upsampled = (sample_rate >= 88_200 && bw <= 24_000) || (sample_rate >= 44_100 && bw <= 17_000);
    (bw, upsampled)
}

fn accumulate_fft(mono: &[f64], hann: &[f64], out_power: &mut [f64]) {
    let mut re = vec![0.0_f64; FFT_SIZE];
    let mut im = vec![0.0_f64; FFT_SIZE];
    for i in 0..FFT_SIZE {
        re[i] = mono[i] * hann[i];
    }
    fft_radix2(&mut re, &mut im);
    let norm = (FFT_SIZE as f64) * (FFT_SIZE as f64);
    for k in 0..(FFT_SIZE / 2) {
        out_power[k] += (re[k] * re[k] + im[k] * im[k]) / norm;
    }
}

fn fft_radix2(re: &mut [f64], im: &mut [f64]) {
    let n = re.len();
    let mut j = 0usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while (j & bit) != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2usize;
    while len <= n {
        let ang = -2.0 * PI / (len as f64);
        let wlen_re = ang.cos();
        let wlen_im = ang.sin();
        for start in (0..n).step_by(len) {
            let (mut w_re, mut w_im) = (1.0_f64, 0.0_f64);
            for k in 0..(len / 2) {
                let u_re = re[start + k];
                let u_im = im[start + k];
                let v_re = re[start + k + len / 2] * w_re - im[start + k + len / 2] * w_im;
                let v_im = re[start + k + len / 2] * w_im + im[start + k + len / 2] * w_re;
                re[start + k] = u_re + v_re;
                im[start + k] = u_im + v_im;
                re[start + k + len / 2] = u_re - v_re;
                im[start + k + len / 2] = u_im - v_im;
                let next_re = w_re * wlen_re - w_im * wlen_im;
                w_im = w_re * wlen_im + w_im * wlen_re;
                w_re = next_re;
            }
        }
        len <<= 1;
    }
}

/// Repairs a full-block uncompressed ALAC escape frame's 3-bit `END` marker (`111`) and zero-padding bits.
/// Returns `true` if any bit was modified.
pub fn repair_escape_tail(frame: &mut [u8], max_block: usize, channels: usize, bits: usize) -> bool {
    let expected_len = (23 + max_block * channels * bits + 3 + 7) / 8;
    if frame.len() != expected_len || frame.len() < 4 {
        return false;
    }
    let end_bit = 23 + max_block * channels * bits;
    let total_bits = expected_len * 8;
    let mut changed = false;

    for i in 0..3 {
        let abs_bit = end_bit + i;
        let byte_idx = abs_bit / 8;
        let mask = 1u8 << (7 - (abs_bit % 8));
        if (frame[byte_idx] & mask) == 0 {
            frame[byte_idx] |= mask;
            changed = true;
        }
    }
    for abs_bit in (end_bit + 3)..total_bits {
        let byte_idx = abs_bit / 8;
        let mask = 1u8 << (7 - (abs_bit % 8));
        if (frame[byte_idx] & mask) != 0 {
            frame[byte_idx] &= !mask;
            changed = true;
        }
    }
    changed
}
