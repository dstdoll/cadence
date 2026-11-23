use cadence_alac::analysis::analyze_mono_i32;
use std::f64::consts::PI;

#[test]
fn detects_16_bit_padded_in_24_bit_container() {
    let sr = 96_000u32;
    let mut samples = Vec::with_capacity(8192);
    for i in 0..8192 {
        // 16-bit sine wave placed in top 16 bits of MSB-aligned i32 (bottom 16 bits zero).
        let v16 = (16000.0 * (2.0 * PI * 1000.0 * (i as f64) / (sr as f64)).sin()) as i16;
        samples.push((v16 as i32) << 16);
    }
    let res = analyze_mono_i32(&samples, sr, 24);
    assert_eq!(res.effective_bits, 16);
    assert!(res.bandwidth_hz <= 2000, "expected low bandwidth for 1kHz tone, got {}", res.bandwidth_hz);
    assert!(res.upsampled_suspect);
}

#[test]
fn detects_true_24_bit_high_bandwidth_signal() {
    let sr = 96_000u32;
    let mut samples = Vec::with_capacity(8192);
    for i in 0..8192 {
        // Sum of 1 kHz + 35 kHz tones with non-zero 24-bit LSBs (bits 8..15 active, bits 0..7 zero).
        let s1 = 0.4 * (2.0 * PI * 1000.0 * (i as f64) / (sr as f64)).sin();
        let s2 = 0.3 * (2.0 * PI * 35_000.0 * (i as f64) / (sr as f64)).sin();
        let s24 = ((s1 + s2) * 8_388_607.0) as i32;
        samples.push(s24 << 8);
    }
    let res = analyze_mono_i32(&samples, sr, 24);
    assert_eq!(res.effective_bits, 24);
    assert!(res.bandwidth_hz >= 34_000, "expected >=34kHz, got {}", res.bandwidth_hz);
    assert!(!res.upsampled_suspect);
}
