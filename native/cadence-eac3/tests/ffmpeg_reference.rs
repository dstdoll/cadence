use std::fs;
use std::path::PathBuf;
use std::process::Command;

use cadence_eac3::Eac3Session;

struct TempDir(PathBuf);

impl TempDir {
    fn new(name: &str) -> Self {
        let mut p = std::env::temp_dir();
        p.push(format!("cadence-eac3-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).expect("create temp dir");
        Self(p)
    }

    fn path(&self, name: &str) -> PathBuf {
        self.0.join(name)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn split_syncframes(bytes: &[u8]) -> Vec<&[u8]> {
    let mut frames = Vec::new();
    let mut i = 0usize;
    while i + 6 <= bytes.len() {
        if bytes[i] == 0x0B && bytes[i + 1] == 0x77 {
            let bsid = (bytes[i + 5] >> 3) & 0x1F;
            let frame_len = if bsid > 10 {
                let frmsiz = (((bytes[i + 2] & 0x07) as usize) << 8) | (bytes[i + 3] as usize);
                (frmsiz + 1) * 2
            } else {
                // Use Eac3Session / ffmpeg raw frame size: scan for next 0x0B77 or parse AC-3 frmsizcod.
                let fscod = (bytes[i + 4] >> 6) as usize;
                let frmsizecod = (bytes[i + 4] & 0x3F) as usize;
                ac3_frame_bytes(fscod, frmsizecod).unwrap_or(bytes.len() - i)
            };
            if frame_len >= 8 && i + frame_len <= bytes.len() {
                frames.push(&bytes[i..i + frame_len]);
                i += frame_len;
                continue;
            }
        }
        i += 1;
    }
    frames
}

fn ac3_frame_bytes(fscod: usize, frmsizecod: usize) -> Option<usize> {
    const WORDS_48: [usize; 38] = [
        64, 64, 80, 80, 96, 96, 112, 112, 128, 128, 160, 160, 192, 192, 224, 224, 256, 256, 320,
        320, 384, 384, 448, 448, 512, 512, 640, 640, 768, 768, 896, 896, 1024, 1024, 1152, 1152,
        1280, 1280,
    ];
    if fscod == 0 && frmsizecod < 38 {
        Some(WORDS_48[frmsizecod] * 2)
    } else {
        None
    }
}

fn run_ffmpeg_case(name: &str, codec: &str, layout: &str, channels: usize, bitrate: &str) {
    let dir = TempDir::new(name);
    let src_wav = dir.path("input.wav");
    let encoded = dir.path("encoded.raw");
    let ref_pcm = dir.path("ref.f32");

    // Generate 0.5s 48kHz multi-channel sine wave input.
    let status = Command::new("/usr/bin/ffmpeg")
        .args([
            "-y",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=48000:duration=0.5",
            "-ac",
            &channels.to_string(),
            src_wav.to_str().unwrap(),
        ])
        .status()
        .expect("run ffmpeg sine");
    assert!(status.success(), "ffmpeg sine failed");

    // Encode to raw AC-3 / E-AC-3 bitstream.
    let fmt = if codec == "ac3" { "ac3" } else { "eac3" };
    let status = Command::new("/usr/bin/ffmpeg")
        .args([
            "-y",
            "-v",
            "error",
            "-i",
            src_wav.to_str().unwrap(),
            "-c:a",
            codec,
            "-b:a",
            bitrate,
            "-channel_layout",
            layout,
            "-f",
            fmt,
            encoded.to_str().unwrap(),
        ])
        .status()
        .expect("run ffmpeg encode");
    assert!(status.success(), "ffmpeg encode {codec} {layout} failed");

    // Decode reference stereo downmix via ffmpeg (normalize multi-channel downmix to match ATSC A/52 Lo/Ro).
    let mut args = vec![
        "-y",
        "-v",
        "error",
        "-f",
        fmt,
        "-i",
        encoded.to_str().unwrap(),
        "-ac",
        "2",
    ];
    if channels > 2 {
        args.extend(["-af", "aresample=rematrix_maxval=1.0"]);
    }
    args.extend(["-f", "f32le", ref_pcm.to_str().unwrap()]);
    let status = Command::new("/usr/bin/ffmpeg")
        .args(&args)
        .status()
        .expect("run ffmpeg decode");
    assert!(status.success(), "ffmpeg decode {codec} {layout} failed");

    let raw_bs = fs::read(&encoded).expect("read encoded");
    let ref_bytes = fs::read(&ref_pcm).expect("read ref pcm");
    let ref_samples: Vec<f32> = ref_bytes
        .chunks_exact(4)
        .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();

    let frames = split_syncframes(&raw_bs);
    assert!(!frames.is_empty(), "no syncframes found for {name}");

    let mut session = Eac3Session::new(48_000, 2).expect("session");
    let mut our_samples = Vec::with_capacity(ref_samples.len());
    let mut frame_buf = vec![0.0f32; session.max_output_bytes() / 4];

    for (idx, frame) in frames.iter().enumerate() {
        let n = session
            .decode_to_f32(frame, &mut frame_buf)
            .unwrap_or_else(|e| panic!("{name}: frame {idx} failed: {e}"));
        our_samples.extend_from_slice(&frame_buf[..n]);
    }
    assert_eq!(
        our_samples.len(),
        ref_samples.len(),
        "{name}: sample count mismatch"
    );

    // Skip first 2048 samples (MDCT startup / filterbank settle) and compare RMS + correlation.
    let start = 2048.min(our_samples.len());
    let mut dot = 0.0f64;
    let mut norm_a = 0.0f64;
    let mut norm_b = 0.0f64;
    let mut max_err = 0.0f32;
    for i in start..our_samples.len() {
        let a = our_samples[i] as f64;
        let b = ref_samples[i] as f64;
        dot += a * b;
        norm_a += a * a;
        norm_b += b * b;
        let err = (our_samples[i] - ref_samples[i]).abs();
        if err > max_err {
            max_err = err;
        }
    }
    let corr = dot / (norm_a.sqrt() * norm_b.sqrt()).max(1e-12);
    let rms_a = (norm_a / ((our_samples.len() - start) as f64)).sqrt();
    let rms_b = (norm_b / ((ref_samples.len() - start) as f64)).sqrt();
    let rms_ratio = rms_a / rms_b.max(1e-12);

    eprintln!(
        "{name}: frames={} samples={} corr={corr:.6} rms_ours={rms_a:.5} rms_ref={rms_b:.5} ratio={rms_ratio:.4} max_err={max_err:.5}",
        frames.len(),
        our_samples.len()
    );
    assert!(corr > 0.98, "{name}: low correlation {corr}");
    assert!(
        (0.85..=1.15).contains(&rms_ratio),
        "{name}: RMS ratio out of range: {rms_ratio}"
    );
}

#[test]
fn matches_ffmpeg_ac3_mono() {
    run_ffmpeg_case("ac3_mono", "ac3", "mono", 1, "96k");
}

#[test]
fn matches_ffmpeg_ac3_stereo() {
    run_ffmpeg_case("ac3_stereo", "ac3", "stereo", 2, "192k");
}

#[test]
fn matches_ffmpeg_ac3_51() {
    run_ffmpeg_case("ac3_51", "ac3", "5.1", 6, "384k");
}

#[test]
fn matches_ffmpeg_eac3_mono() {
    run_ffmpeg_case("eac3_mono", "eac3", "mono", 1, "96k");
}

#[test]
fn matches_ffmpeg_eac3_stereo() {
    run_ffmpeg_case("eac3_stereo", "eac3", "stereo", 2, "192k");
}

#[test]
fn matches_ffmpeg_eac3_51() {
    run_ffmpeg_case("eac3_51", "eac3", "5.1", 6, "384k");
}

#[test]
fn matches_real_apple_atmos_dsotm() {
    let path = std::path::Path::new("/tmp/dsotm.eac3");
    if !path.exists() {
        return;
    }
    let raw = std::fs::read(path).expect("read dsotm");
    let frames = split_syncframes(&raw);
    let ref_pcm = Command::new("/usr/bin/ffmpeg")
        .args([
            "-v",
            "error",
            "-drc_scale",
            "0",
            "-f",
            "eac3",
            "-i",
            "/tmp/dsotm.eac3",
            "-ac",
            "2",
            "-af",
            "aresample=rematrix_maxval=1.0",
            "-f",
            "f32le",
            "-",
        ])
        .output()
        .expect("ffmpeg")
        .stdout;
    let ref_samples: Vec<f32> = ref_pcm
        .chunks_exact(4)
        .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();
    let mut session = Eac3Session::new(48_000, 2).expect("session");
    let mut our_samples = Vec::with_capacity(ref_samples.len());
    let mut frame_buf = vec![0.0f32; session.max_output_bytes() / 4];
    for frame in &frames {
        let n = session.decode_to_f32(frame, &mut frame_buf).expect("decode dsotm frame");
        our_samples.extend_from_slice(&frame_buf[..n]);
    }
    assert_eq!(our_samples.len(), ref_samples.len());
    let start = 2048.min(our_samples.len());
    let (mut dot, mut na, mut nb, mut max_err) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    for (&a, &b) in our_samples[start..].iter().zip(ref_samples[start..].iter()) {
        let (a, b) = (a as f64, b as f64);
        dot += a * b;
        na += a * a;
        nb += b * b;
        max_err = max_err.max((a - b).abs());
    }
    let corr = dot / (na.sqrt() * nb.sqrt());
    let rms_ours = (na / (our_samples.len() - start) as f64).sqrt();
    let rms_ref = (nb / (ref_samples.len() - start) as f64).sqrt();
    let ratio = rms_ours / rms_ref;
    eprintln!("dsotm: frames={} corr={corr:.6} rms_ours={rms_ours:.5} rms_ref={rms_ref:.5} ratio={ratio:.4} max_err={max_err:.5}", frames.len());
    assert!(corr > 0.98, "dsotm: low correlation {corr}");
    assert!((0.85..=1.15).contains(&ratio), "dsotm: RMS ratio {ratio}");
}

#[test]
fn matches_ffmpeg_eac3_transient_blksw() {
    let tmp = std::env::temp_dir().join("cadence_eac3_test_transient.eac3");
    let ref_pcm_path = std::env::temp_dir().join("cadence_eac3_test_transient_ref.f32");
    let status = Command::new("/usr/bin/ffmpeg")
        .args([
            "-y",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "aevalsrc='if(lt(mod(t,0.05),0.0005),0.8*sin(2*PI*1000*t),0)|if(lt(mod(t+0.025,0.05),0.0005),0.8*sin(2*PI*2000*t),0)':s=48000:d=0.5",
            "-ar",
            "48000",
            "-c:a",
            "eac3",
            "-b:a",
            "192k",
            "-f",
            "eac3",
            tmp.to_str().unwrap(),
        ])
        .status()
        .expect("encode transient eac3");
    assert!(status.success(), "ffmpeg transient encode failed");

    let status = Command::new("/usr/bin/ffmpeg")
        .args([
            "-y",
            "-v",
            "error",
            "-f",
            "eac3",
            "-i",
            tmp.to_str().unwrap(),
            "-ac",
            "2",
            "-f",
            "f32le",
            ref_pcm_path.to_str().unwrap(),
        ])
        .status()
        .expect("decode transient eac3");
    assert!(status.success(), "ffmpeg transient decode failed");

    let raw_bs = fs::read(&tmp).expect("read transient bs");
    let ref_bytes = fs::read(&ref_pcm_path).expect("read transient ref");
    let ref_samples: Vec<f32> = ref_bytes
        .chunks_exact(4)
        .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();
    let frames = split_syncframes(&raw_bs);
    let mut session = Eac3Session::new(48_000, 2).expect("session");
    let mut our_samples = Vec::with_capacity(ref_samples.len());
    let mut frame_buf = vec![0.0f32; session.max_output_bytes() / 4];
    for (idx, frame) in frames.iter().enumerate() {
        let n = session
            .decode_to_f32(frame, &mut frame_buf)
            .unwrap_or_else(|e| panic!("transient frame {idx} failed: {e}"));
        our_samples.extend_from_slice(&frame_buf[..n]);
    }
    assert_eq!(our_samples.len(), ref_samples.len());
    let start = 2048.min(our_samples.len());
    let (mut dot, mut na, mut nb, mut max_err) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    for (&a, &b) in our_samples[start..].iter().zip(ref_samples[start..].iter()) {
        let (a, b) = (a as f64, b as f64);
        dot += a * b;
        na += a * a;
        nb += b * b;
        max_err = max_err.max((a - b).abs());
    }
    let corr = dot / (na.sqrt() * nb.sqrt()).max(1e-12);
    let ratio = (na / (our_samples.len() - start) as f64).sqrt()
        / (nb / (ref_samples.len() - start) as f64).sqrt().max(1e-12);
    eprintln!("transient_blksw: frames={} corr={corr:.6} ratio={ratio:.4} max_err={max_err:.5}", frames.len());
    assert!(corr > 0.99, "transient_blksw: low correlation {corr}");
    assert!((0.90..=1.10).contains(&ratio), "transient_blksw: RMS ratio {ratio}");
}
