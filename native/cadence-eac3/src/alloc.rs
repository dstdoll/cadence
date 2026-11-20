//! Exponent unpacking, ATSC A/52 / ETSI TS 102 366 bit allocation, and mantissa decoding.

use crate::bitstream::BitReader;
use crate::tables::{
    BAP_TABLE, BND_TO_BIN, DB_PER_BIT, EXP_SCALE, FAST_DECAY, FLOOR, HEARING_THRESHOLD, LEVEL_11,
    LEVEL_3, LEVEL_5, SLOW_DECAY, SLOW_GAIN, SYMMETRIC_QUANT_BITS,
};
use crate::Eac3Error;

pub const EXP_REUSE: u8 = 0;
pub const EXP_D15: u8 = 1;
pub const EXP_D25: u8 = 2;
pub const EXP_D45: u8 = 3;

/// Unpacks differential exponents into `exps[start_bin..end_bin]`.
pub fn decode_exponents(
    r: &mut BitReader<'_>,
    strategy: u8,
    n_grps: usize,
    first_exp: u8,
    exps: &mut [u8],
) -> Result<(), Eac3Error> {
    let group_size = match strategy {
        EXP_D15 => 1,
        EXP_D25 => 2,
        EXP_D45 => 4,
        _ => return Ok(()),
    };
    let mut prev = first_exp;
    let mut idx = 0usize;
    for _ in 0..n_grps {
        let raw = r.read_bits(7)? as i32;
        if raw >= 125 {
            return Err(Eac3Error::DecodeFailed("invalid exponent group"));
        }
        let d1 = raw / 25 - 2;
        let rem = raw % 25;
        let d2 = rem / 5 - 2;
        let d3 = rem % 5 - 2;
        for d in [d1, d2, d3] {
            let next = ((prev as i32) + d).clamp(0, 24) as u8;
            prev = next;
            for _ in 0..group_size {
                if idx < exps.len() {
                    exps[idx] = next;
                    idx += 1;
                }
            }
        }
    }
    Ok(())
}

/// Computes `bap[start..end]` using the ATSC A/52 / ETSI TS 102 366 parametric bit allocation.
#[allow(clippy::too_many_arguments)]
pub fn compute_bap(
    fscod: usize,
    start: usize,
    end: usize,
    exps: &[u8],
    sdecay: i16,
    fdecay: i16,
    sgain: i16,
    dbknee: i16,
    floor: i16,
    fgain: i16,
    snroffset: i16,
    init_fastleak: Option<i16>,
    init_slowleak: Option<i16>,
    bap: &mut [u8],
) {
    if start >= end {
        return;
    }
    if snroffset <= -960 {
        bap[start..end].fill(0);
        return;
    }
    let mut psd = [0i16; 256];
    for bin in start..end {
        psd[bin] = 3072 - ((exps[bin] as i16) << 7);
    }

    let mut bnd = 0usize;
    while bnd < 50 && BND_TO_BIN[bnd] <= start {
        bnd += 1;
    }

    let mut bndpsd = [0i16; 50];
    let mut bin = start;
    while bin < end && bnd < 50 {
        let bnd_end = BND_TO_BIN[bnd].min(end);
        let mut acc = psd[bin];
        bin += 1;
        while bin < bnd_end {
            acc = log_add(acc, psd[bin]);
            bin += 1;
        }
        bndpsd[bnd] = acc;
        bnd += 1;
    }

    let bnd_start = {
        let mut b = 0usize;
        while b < 50 && BND_TO_BIN[b] <= start {
            b += 1;
        }
        b
    };
    let bnd_end = bnd;

    let is_lfe = bnd_start == 0 && bnd_end == 7;
    let mut excite = [0i16; 50];
    let mut lowcomp = 0i16;
    let mut fastleak: i16;
    let mut slowleak: i16;
    let begin = if bnd_start == 0 {
        lowcomp = calc_lowcomp(lowcomp, bndpsd[0], bndpsd[1], 0);
        excite[0] = bndpsd[0] - fgain - lowcomp;
        lowcomp = calc_lowcomp(lowcomp, bndpsd[1], bndpsd[2], 1);
        excite[1] = bndpsd[1] - fgain - lowcomp;
        fastleak = bndpsd[1] - fgain;
        slowleak = bndpsd[1] - sgain;
        let mut b_next = 7usize.min(bnd_end);
        for b in 2..7.min(bnd_end) {
            if !(is_lfe && b == 6) {
                lowcomp = calc_lowcomp(lowcomp, bndpsd[b], bndpsd[b + 1], b);
            }
            fastleak = bndpsd[b] - fgain;
            slowleak = bndpsd[b] - sgain;
            excite[b] = fastleak - lowcomp;
            if !(is_lfe && b == 6) && b + 1 < bnd_end && bndpsd[b] <= bndpsd[b + 1] {
                b_next = b + 1;
                break;
            }
        }
        b_next
    } else {
        fastleak = init_fastleak.unwrap_or(bndpsd[bnd_start] - fgain);
        slowleak = init_slowleak.unwrap_or(bndpsd[bnd_start] - sgain);
        bnd_start
    };
    for b in begin..bnd_end {
        if !(is_lfe && b == 6) && b + 1 < bnd_end {
            lowcomp = calc_lowcomp(lowcomp, bndpsd[b], bndpsd[b + 1], b);
        }
        let psd_val = bndpsd[b];
        fastleak = (fastleak - fdecay).max(psd_val - fgain);
        slowleak = (slowleak - sdecay).max(psd_val - sgain);
        excite[b] = (fastleak - lowcomp).max(slowleak);
    }

    let mut mask = [0i16; 50];
    for b in bnd_start..bnd_end {
        let mut m = excite[b];
        if bndpsd[b] < dbknee {
            m += (dbknee - bndpsd[b]) >> 2;
        }
        let base = m.max(HEARING_THRESHOLD[fscod][b]);
        mask[b] = ((base - snroffset - floor).max(0) & !31) + floor;
    }

    let mut bin_idx = start;
    for b in bnd_start..bnd_end {
        let next_bin = BND_TO_BIN[b].min(end);
        let m = mask[b];
        while bin_idx < next_bin {
            let address = ((psd[bin_idx] - m) >> 5).clamp(0, 63);
            bap[bin_idx] = BAP_TABLE[address as usize];
            bin_idx += 1;
        }
    }
}

#[inline]
fn calc_lowcomp(mut a: i16, b0: i16, b1: i16, bin: usize) -> i16 {
    if bin < 7 {
        if (b0 + 256) == b1 {
            a = 384;
        } else if b0 > b1 {
            a = (a - 64).max(0);
        }
    } else if bin < 20 {
        if (b0 + 256) == b1 {
            a = 320;
        } else if b0 > b1 {
            a = (a - 64).max(0);
        }
    } else {
        a = (a - 128).max(0);
    }
    a
}

#[inline]
fn log_add(a: i16, b: i16) -> i16 {
    const LATAB: [i16; 260] = [
        0x40, 0x3f, 0x3e, 0x3d, 0x3c, 0x3b, 0x3a, 0x39, 0x38, 0x37, 0x36, 0x35, 0x34, 0x34, 0x33, 0x32,
        0x31, 0x30, 0x2f, 0x2f, 0x2e, 0x2d, 0x2c, 0x2c, 0x2b, 0x2a, 0x29, 0x29, 0x28, 0x27, 0x26, 0x26,
        0x25, 0x24, 0x24, 0x23, 0x23, 0x22, 0x21, 0x21, 0x20, 0x20, 0x1f, 0x1e, 0x1e, 0x1d, 0x1d, 0x1c,
        0x1c, 0x1b, 0x1b, 0x1a, 0x1a, 0x19, 0x19, 0x18, 0x18, 0x17, 0x17, 0x16, 0x16, 0x15, 0x15, 0x15,
        0x14, 0x14, 0x13, 0x13, 0x13, 0x12, 0x12, 0x12, 0x11, 0x11, 0x11, 0x10, 0x10, 0x10, 0x0f, 0x0f,
        0x0f, 0x0e, 0x0e, 0x0e, 0x0d, 0x0d, 0x0d, 0x0d, 0x0c, 0x0c, 0x0c, 0x0c, 0x0b, 0x0b, 0x0b, 0x0b,
        0x0a, 0x0a, 0x0a, 0x0a, 0x0a, 0x09, 0x09, 0x09, 0x09, 0x09, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08,
        0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x05, 0x05,
        0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04,
        0x04, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x03, 0x02,
        0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x02,
        0x02, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    ];
    let c = (a - b).abs() >> 1;
    let address = c.min(255) as usize;
    a.max(b) + LATAB[address]
}

pub struct BitAllocParams {
    pub sdecay: i16,
    pub fdecay: i16,
    pub sgain: i16,
    pub dbknee: i16,
    pub floor: i16,
}

impl BitAllocParams {
    pub fn from_codes(sd: usize, fd: usize, sg: usize, db: usize, fl: usize) -> Self {
        Self {
            sdecay: SLOW_DECAY[sd & 3],
            fdecay: FAST_DECAY[fd & 3],
            sgain: SLOW_GAIN[sg & 3],
            dbknee: DB_PER_BIT[db & 3],
            floor: FLOOR[fl & 7],
        }
    }
}

/// Stateful mantissa unpacker handling grouped 3-in-5 / 3-in-7 / 2-in-7 codes and LFSR dither.
pub struct MantissaDecoder {
    lfsr: u16,
    bap1_idx: usize,
    bap1_vals: [f32; 3],
    bap2_idx: usize,
    bap2_vals: [f32; 3],
    bap4_idx: usize,
    bap4_vals: [f32; 2],
}

impl MantissaDecoder {
    pub fn new() -> Self {
        Self {
            lfsr: 1,
            bap1_idx: 3,
            bap1_vals: [0.0; 3],
            bap2_idx: 3,
            bap2_vals: [0.0; 3],
            bap4_idx: 2,
            bap4_vals: [0.0; 2],
        }
    }

    pub fn reset_groups(&mut self) {
        self.bap1_idx = 3;
        self.bap2_idx = 3;
        self.bap4_idx = 2;
    }

    #[inline]
    fn next_dither(&mut self) -> f32 {
        let bit = ((self.lfsr >> 0) ^ (self.lfsr >> 2) ^ (self.lfsr >> 3) ^ (self.lfsr >> 5)) & 1;
        self.lfsr = (self.lfsr >> 1) | (bit << 15);
        // Scale to ~[-0.707, +0.707]
        ((self.lfsr as i16) as f32) * (0.70710677 / 32768.0)
    }

    pub fn decode_channel(
        &mut self,
        r: &mut BitReader<'_>,
        start: usize,
        end: usize,
        exps: &[u8],
        bap: &[u8],
        dither: bool,
        coeffs: &mut [f32],
    ) -> Result<(), Eac3Error> {
        for bin in start..end {
            let exp = exps[bin].min(24) as usize;
            let scale = EXP_SCALE[exp];
            let q = match bap[bin] {
                0 => {
                    if dither {
                        self.next_dither()
                    } else {
                        0.0
                    }
                }
                1 => {
                    if self.bap1_idx >= 3 {
                        let code = r.read_bits(5)? as usize;
                        if code >= 27 {
                            return Err(Eac3Error::DecodeFailed("invalid bap1 mantissa"));
                        }
                        self.bap1_vals[0] = LEVEL_3[code / 9];
                        self.bap1_vals[1] = LEVEL_3[(code % 9) / 3];
                        self.bap1_vals[2] = LEVEL_3[code % 3];
                        self.bap1_idx = 0;
                    }
                    let v = self.bap1_vals[self.bap1_idx];
                    self.bap1_idx += 1;
                    v
                }
                2 => {
                    if self.bap2_idx >= 3 {
                        let code = r.read_bits(7)? as usize;
                        if code >= 125 {
                            return Err(Eac3Error::DecodeFailed("invalid bap2 mantissa"));
                        }
                        self.bap2_vals[0] = LEVEL_5[code / 25];
                        self.bap2_vals[1] = LEVEL_5[(code % 25) / 5];
                        self.bap2_vals[2] = LEVEL_5[code % 5];
                        self.bap2_idx = 0;
                    }
                    let v = self.bap2_vals[self.bap2_idx];
                    self.bap2_idx += 1;
                    v
                }
                3 => {
                    let signed = r.read_signed(3)?;
                    (signed as f32) * (2.0 / 7.0)
                }
                4 => {
                    if self.bap4_idx >= 2 {
                        let code = r.read_bits(7)? as usize;
                        if code >= 121 {
                            return Err(Eac3Error::DecodeFailed("invalid bap4 mantissa"));
                        }
                        self.bap4_vals[0] = LEVEL_11[code / 11];
                        self.bap4_vals[1] = LEVEL_11[code % 11];
                        self.bap4_idx = 0;
                    }
                    let v = self.bap4_vals[self.bap4_idx];
                    self.bap4_idx += 1;
                    v
                }
                5 => {
                    let signed = r.read_signed(4)?;
                    (signed as f32) * (2.0 / 15.0)
                }
                b @ 6..=15 => {
                    let bits = SYMMETRIC_QUANT_BITS[b as usize] as usize;
                    let signed = r.read_signed(bits)?;
                    (signed as f32) / ((1u32 << (bits - 1)) as f32)
                }
                _ => 0.0,
            };
            coeffs[bin] = q * scale;
        }
        Ok(())
    }
}
