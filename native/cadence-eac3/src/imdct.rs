//! 512-point and 256-point IMDCT, KBD windowing, overlap-add, coupling, rematrixing, and stereo downmix.

use crate::tables::build_kbd_window_256;

const INV_SQRT2: f32 = std::f32::consts::FRAC_1_SQRT_2;

/// Rematrixing band boundaries in MDCT bins (ATSC A/52 Section 7.5.2).
pub const REMAT_BANDS: [(usize, usize); 4] = [(13, 25), (25, 37), (37, 61), (61, 253)];

/// Per-channel IMDCT overlap-add state and precomputed KBD window.
pub struct ImdctState {
    win: [f32; 256],
    delay: Vec<[f32; 128]>,
}

impl ImdctState {
    pub fn new(max_channels: usize) -> Self {
        Self {
            win: build_kbd_window_256(),
            delay: vec![[0.0; 128]; max_channels],
        }
    }

    pub fn ensure_channels(&mut self, channels: usize) {
        if self.delay.len() < channels {
            self.delay.resize(channels, [0.0; 128]);
        }
    }

    pub fn reset(&mut self) {
        for d in &mut self.delay {
            d.fill(0.0);
        }
    }

    /// Transforms 256 frequency bins for `ch` into 256 time-domain samples.
    pub fn process_channel(
        &mut self,
        ch: usize,
        blksw: bool,
        coeffs: &[f32; 256],
        out: &mut [f32; 256],
    ) {
        let delay = &mut self.delay[ch];
        if !blksw {
            let mut time = [0.0f32; 512];
            imdct_512(coeffs, &mut time);
            let src1: &[f32; 128] = time[128..256].try_into().unwrap();
            vector_fmul_window(out, delay, src1, &self.win);
            delay.copy_from_slice(&time[256..384]);
        } else {
            let mut c1 = [0.0f32; 128];
            let mut c2 = [0.0f32; 128];
            for k in 0..128 {
                c1[k] = coeffs[2 * k];
                c2[k] = coeffs[2 * k + 1];
            }
            let mut t1 = [0.0f32; 256];
            let mut t2 = [0.0f32; 256];
            imdct_256(&c1, &mut t1);
            imdct_256(&c2, &mut t2);
            let src1: &[f32; 128] = t1[64..192].try_into().unwrap();
            vector_fmul_window(out, delay, src1, &self.win);
            delay.copy_from_slice(&t2[64..192]);
        }
    }
}

#[inline]
fn vector_fmul_window(
    out: &mut [f32; 256],
    src0: &[f32; 128],
    src1: &[f32; 128],
    win: &[f32; 256],
) {
    for i in 0..128 {
        let s0 = src0[i];
        let s1 = src1[127 - i];
        let wi = win[i];
        let wj = win[255 - i];
        out[i] = s0 * wj - s1 * wi;
        out[255 - i] = s0 * wi + s1 * wj;
    }
}

/// 512-point IMDCT (256 MDCT bins -> 512 time samples) using ATSC A/52 Section 7.9.1.
pub fn imdct_512(coeffs: &[f32; 256], out: &mut [f32; 512]) {
    let mut fft_re = [0.0f32; 128];
    let mut fft_im = [0.0f32; 128];
    imdct_core(coeffs, out, &mut fft_re, &mut fft_im);
}

/// 256-point IMDCT (128 MDCT bins -> 256 time samples) for `blksw == 1`.
pub fn imdct_256(coeffs: &[f32; 128], out: &mut [f32; 256]) {
    let mut fft_re = [0.0f32; 64];
    let mut fft_im = [0.0f32; 64];
    imdct_core(coeffs, out, &mut fft_re, &mut fft_im);
}

fn imdct_core(coeffs: &[f32], out: &mut [f32], fft_re: &mut [f32], fft_im: &mut [f32]) {
    let n = out.len();
    let n2 = n / 2;
    let n4 = n / 4;
    let scale = -2.0f32;

    for k in 0..n4 {
        let a = coeffs[2 * k];
        let b = coeffs[n2 - 1 - 2 * k];
        let angle = (2.0 * std::f32::consts::PI / (n as f32)) * (k as f32 + 0.125);
        let (s, c) = angle.sin_cos();
        // (a + i*b) * (c - i*s) = (a*c + b*s) + i*(b*c - a*s)
        fft_re[k] = a * c + b * s;
        fft_im[k] = b * c - a * s;
    }

    fft_radix2(fft_re, fft_im);

    // Unfold with ATSC A/52 n0 = N/4 + 0.5 shift.
    for m in 0..n4 {
        let angle = (2.0 * std::f32::consts::PI / (n as f32)) * (m as f32 + 0.125);
        let (s, c) = angle.sin_cos();
        // Z[m] * (c - i*s) = (re*c + im*s) + i*(im*c - re*s)
        let yr = (fft_re[m] * c + fft_im[m] * s) * scale;
        let yi = (fft_im[m] * c - fft_re[m] * s) * scale;

        let place_even = |p: usize, val: f32, dst: &mut [f32]| {
            let (n, v) = if p >= n4 { (p - n4, val) } else { (p + 3 * n4, -val) };
            dst[n] = v;
            if n < n2 {
                dst[n2 - 1 - n] = -v;
            } else {
                dst[3 * n2 - 1 - n] = v;
            }
        };
        place_even(2 * m, yr, out);
        place_even(n2 + 2 * m, yi, out);
    }
}

/// In-place forward radix-2 Cooley-Tukey FFT (`len` must be power of two).
fn fft_radix2(re: &mut [f32], im: &mut [f32]) {
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
        let half = len >> 1;
        let ang = -2.0 * std::f32::consts::PI / (len as f32);
        let (w_step_im, w_step_re) = ang.sin_cos();
        let mut i = 0usize;
        while i < n {
            let mut wr = 1.0f32;
            let mut wi = 0.0f32;
            for k in 0..half {
                let u_re = re[i + k];
                let u_im = im[i + k];
                let v_re = re[i + k + half] * wr - im[i + k + half] * wi;
                let v_im = re[i + k + half] * wi + im[i + k + half] * wr;
                re[i + k] = u_re + v_re;
                im[i + k] = u_im + v_im;
                re[i + k + half] = u_re - v_re;
                im[i + k + half] = u_im - v_im;
                let next_wr = wr * w_step_re - wi * w_step_im;
                wi = wr * w_step_im + wi * w_step_re;
                wr = next_wr;
            }
            i += len;
        }
        len <<= 1;
    }
}

/// Applies 2/0 stereo rematrixing in place for `acmod == 2`.
pub fn apply_rematrixing(
    left: &mut [f32; 256],
    right: &mut [f32; 256],
    flags: &[bool; 4],
    end_bin: usize,
) {
    for (b, &(start, stop)) in REMAT_BANDS.iter().enumerate() {
        if !flags[b] || start >= end_bin {
            continue;
        }
        let hi = stop.min(end_bin);
        for k in start..hi {
            let l = left[k];
            let r = right[k];
            left[k] = l + r;
            right[k] = l - r;
        }
    }
}

/// Downmixes `fbw_channels` (256 samples each) to interleaved stereo `[L0, R0, L1, R1, ...]`.
pub fn downmix_to_stereo(
    acmod: usize,
    channels: &[[f32; 256]],
    cmixlev: f32,
    surmixlev: f32,
    out_interleaved: &mut [f32],
) {
    match acmod {
        0 | 2 => {
            for n in 0..256 {
                out_interleaved[2 * n] = channels[0][n];
                out_interleaved[2 * n + 1] = channels[1][n];
            }
        }
        1 => {
            for n in 0..256 {
                let c = channels[0][n] * INV_SQRT2;
                out_interleaved[2 * n] = c;
                out_interleaved[2 * n + 1] = c;
            }
        }
        3 => {
            let norm = 1.0 / (1.0 + cmixlev);
            for n in 0..256 {
                let c = channels[1][n] * cmixlev;
                out_interleaved[2 * n] = (channels[0][n] + c) * norm;
                out_interleaved[2 * n + 1] = (channels[2][n] + c) * norm;
            }
        }
        4 => {
            let norm = 1.0 / (1.0 + surmixlev);
            for n in 0..256 {
                let s = channels[2][n] * surmixlev;
                out_interleaved[2 * n] = (channels[0][n] + s) * norm;
                out_interleaved[2 * n + 1] = (channels[1][n] + s) * norm;
            }
        }
        5 => {
            let norm = 1.0 / (1.0 + cmixlev + surmixlev);
            for n in 0..256 {
                let c = channels[1][n] * cmixlev;
                let s = channels[3][n] * surmixlev;
                out_interleaved[2 * n] = (channels[0][n] + c + s) * norm;
                out_interleaved[2 * n + 1] = (channels[2][n] + c + s) * norm;
            }
        }
        6 => {
            let norm = 1.0 / (1.0 + surmixlev);
            for n in 0..256 {
                out_interleaved[2 * n] = (channels[0][n] + channels[2][n] * surmixlev) * norm;
                out_interleaved[2 * n + 1] = (channels[1][n] + channels[3][n] * surmixlev) * norm;
            }
        }
        7 => {
            let norm = 1.0 / (1.0 + cmixlev + surmixlev);
            for n in 0..256 {
                let c = channels[1][n] * cmixlev;
                out_interleaved[2 * n] = (channels[0][n] + c + channels[3][n] * surmixlev) * norm;
                out_interleaved[2 * n + 1] = (channels[2][n] + c + channels[4][n] * surmixlev) * norm;
            }
        }
        _ => {
            for n in 0..256 {
                out_interleaved[2 * n] = channels[0][n];
                out_interleaved[2 * n + 1] = channels.get(1).map(|c| c[n]).unwrap_or(channels[0][n]);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn imdct_512_matches_direct_a52() {
        let mut coeffs = [0.0f32; 256];
        for (i, c) in coeffs.iter_mut().enumerate() {
            *c = ((i as f32) * 0.37).sin() * 0.25;
        }
        let mut out = [0.0f32; 512];
        imdct_512(&coeffs, &mut out);

        let n = 512.0f64;
        let mut max_err = 0.0f64;
        for sample in 0..512 {
            let mut sum = 0.0f64;
            for (k, &x) in coeffs.iter().enumerate() {
                let ang = (2.0 * std::f64::consts::PI / n)
                    * (sample as f64 + 0.5 + n / 4.0)
                    * (k as f64 + 0.5);
                sum += (x as f64) * ang.cos();
            }
            let expected = -2.0 * sum;
            max_err = max_err.max((out[sample] as f64 - expected).abs());
        }
        assert!(max_err < 1e-3, "imdct_512 max_err={max_err}");
    }
}
