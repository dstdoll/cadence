//! Standard ATSC A/52 & ETSI TS 102 366 lookup tables.

/// 50-band frequency band-to-bin boundaries (Table A.5 / 7.21).
pub const BND_TO_BIN: [usize; 50] = [
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
    26, 27, 28, 31, 34, 37, 40, 43, 46, 49, 55, 61, 67, 73, 79, 85, 97, 109, 121, 133, 157, 181,
    205, 229, 253,
];

/// Masking curve hearing threshold by `(fscod, band)` (ATSC A/52 Table 7.18).
pub const HEARING_THRESHOLD: [[i16; 50]; 3] = [
    // 48 kHz
    [
        0x4d0, 0x4d0, 0x440, 0x400, 0x3e0, 0x3c0, 0x3b0, 0x3b0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0,
        0x390, 0x390, 0x390, 0x380, 0x380, 0x370, 0x370, 0x360, 0x360, 0x350, 0x350, 0x340, 0x340,
        0x330, 0x320, 0x310, 0x300, 0x2f0, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x310, 0x340, 0x390, 0x3e0,
        0x420, 0x460, 0x490, 0x4a0, 0x460, 0x440, 0x440, 0x520, 0x800, 0x840, 0x840,
    ],
    // 44.1 kHz
    [
        0x4f0, 0x4f0, 0x460, 0x410, 0x3e0, 0x3d0, 0x3c0, 0x3b0, 0x3b0, 0x3a0, 0x3a0, 0x3a0, 0x3a0,
        0x3a0, 0x390, 0x390, 0x390, 0x380, 0x380, 0x380, 0x370, 0x370, 0x360, 0x360, 0x350, 0x350,
        0x340, 0x340, 0x320, 0x310, 0x300, 0x2f0, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x320, 0x350, 0x390,
        0x3e0, 0x420, 0x450, 0x4a0, 0x490, 0x460, 0x440, 0x480, 0x630, 0x840, 0x840,
    ],
    // 32 kHz
    [
        0x580, 0x580, 0x4b0, 0x450, 0x420, 0x3f0, 0x3e0, 0x3d0, 0x3c0, 0x3b0, 0x3b0, 0x3b0, 0x3a0,
        0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x390, 0x390, 0x390, 0x390, 0x380, 0x380,
        0x380, 0x370, 0x360, 0x350, 0x340, 0x330, 0x320, 0x310, 0x300, 0x2f0, 0x2f0, 0x2f0, 0x300,
        0x310, 0x330, 0x350, 0x3c0, 0x410, 0x470, 0x4a0, 0x460, 0x440, 0x450, 0x4e0,
    ],
];

pub const SLOW_DECAY: [i16; 4] = [0x0f, 0x11, 0x13, 0x15];
pub const FAST_DECAY: [i16; 4] = [0x3f, 0x53, 0x67, 0x7b];
pub const SLOW_GAIN: [i16; 4] = [0x540, 0x4d8, 0x478, 0x410];
pub const DB_PER_BIT: [i16; 4] = [0x000, 0x700, 0x900, 0xb00];
pub const FLOOR: [i16; 8] = [0x2f0, 0x2b0, 0x270, 0x230, 0x1f0, 0x170, 0x0f0, -0x0800];

/// Bits per bin for `bap` 0..=15 (note: 1, 2, 4 use grouped mantissas).
pub const BAP_TABLE: [u8; 64] = [
    0, 1, 1, 1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 9, 9, 9, 9,
    10, 10, 10, 10, 11, 11, 11, 11, 12, 12, 12, 12, 13, 13, 13, 13, 14, 14, 14, 14, 14, 14, 14, 14,
    15, 15, 15, 15, 15, 15, 15, 15, 15,
];

/// Asymmetric mantissa bits for standard `bap` >= 6 (0 means grouped).
pub const SYMMETRIC_QUANT_BITS: [u8; 16] = [0, 0, 0, 3, 0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16];

/// Scale factors for exponent 0..=24 (`2^-exp`).
pub const EXP_SCALE: [f32; 25] = [
    1.0,
    0.5,
    0.25,
    0.125,
    0.0625,
    0.03125,
    0.015625,
    0.0078125,
    0.00390625,
    0.001953125,
    0.0009765625,
    0.00048828125,
    0.00024414063,
    0.00012207031,
    0.000061035156,
    0.000030517578,
    0.000015258789,
    0.0000076293945,
    0.0000038146973,
    0.0000019073486,
    0.0000009536743,
    0.00000047683716,
    0.00000023841858,
    0.00000011920929,
    0.000000059604645,
];

/// 3-level symmetric quantization (`bap == 1`, 3 values in 5 bits).
pub const LEVEL_3: [f32; 3] = [-2.0 / 3.0, 0.0, 2.0 / 3.0];

/// 5-level symmetric quantization (`bap == 2`, 3 values in 7 bits).
pub const LEVEL_5: [f32; 5] = [-0.8, -0.4, 0.0, 0.4, 0.8];

/// 11-level symmetric quantization (`bap == 4`, 2 values in 7 bits).
pub const LEVEL_11: [f32; 11] = [
    -10.0 / 11.0,
    -8.0 / 11.0,
    -6.0 / 11.0,
    -4.0 / 11.0,
    -2.0 / 11.0,
    0.0,
    2.0 / 11.0,
    4.0 / 11.0,
    6.0 / 11.0,
    8.0 / 11.0,
    10.0 / 11.0,
];

/// 7-level quantization (`bap == 3`, 3 bits).
#[allow(dead_code)]
pub const LEVEL_7: [f32; 7] = [
    -6.0 / 7.0,
    -4.0 / 7.0,
    -2.0 / 7.0,
    0.0,
    2.0 / 7.0,
    4.0 / 7.0,
    6.0 / 7.0,
];

/// 15-level quantization (`bap == 5`, 4 bits).
#[allow(dead_code)]
pub const LEVEL_15: [f32; 15] = [
    -14.0 / 15.0,
    -12.0 / 15.0,
    -10.0 / 15.0,
    -8.0 / 15.0,
    -6.0 / 15.0,
    -4.0 / 15.0,
    -2.0 / 15.0,
    0.0,
    2.0 / 15.0,
    4.0 / 15.0,
    6.0 / 15.0,
    8.0 / 15.0,
    10.0 / 15.0,
    12.0 / 15.0,
    14.0 / 15.0,
];

/// Standard ATSC A/52 256-entry KBD half-window (used symmetrically for 512-point IMDCT).
pub fn build_kbd_window_256() -> [f32; 256] {
    let mut win = [0.0f32; 256];
    let alpha_pi = 5.0 * std::f64::consts::PI;
    let bessel_i0 = |x: f64| -> f64 {
        let mut sum = 1.0;
        let mut term = 1.0;
        let half_x2 = 0.25 * x * x;
        for k in 1..25 {
            term *= half_x2 / ((k * k) as f64);
            sum += term;
        }
        sum
    };
    let denom = bessel_i0(alpha_pi);
    let mut acc = 0.0;
    let mut prefix = [0.0f64; 257];
    for n in 0..256 {
        let r = (2.0 * n as f64) / 256.0 - 1.0;
        let arg = alpha_pi * (1.0 - r * r).max(0.0).sqrt();
        acc += bessel_i0(arg) / denom;
        prefix[n + 1] = acc;
    }
    let total = acc + bessel_i0(0.0) / denom;
    for n in 0..256 {
        win[n] = (prefix[n + 1] / total).sqrt() as f32;
    }
    win
}
