use cadence_alac::{AlacError, AlacSession};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

struct TempDir(PathBuf);

impl TempDir {
    fn new(tag: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("cadence_alac_test_{}_{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("create temp dir");
        Self(dir)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn be_u16(b: &[u8]) -> u16 {
    u16::from_be_bytes([b[0], b[1]])
}

fn be_u32(b: &[u8]) -> u32 {
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

fn be_u64(b: &[u8]) -> u64 {
    u64::from_be_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]])
}

/// Minimal MP4 box walker to extract the `alac` 24-byte config + raw access units from `mdat`.
fn parse_mp4_alac(data: &[u8]) -> (Vec<u8>, Vec<Vec<u8>>) {
    let mut cookie = Vec::new();
    let mut sample_sizes = Vec::new();
    let mut chunk_offsets = Vec::new();
    let mut stsc: Vec<(u32, u32)> = Vec::new(); // (first_chunk_1based, samples_per_chunk)

    fn walk(
        buf: &[u8],
        cookie: &mut Vec<u8>,
        sample_sizes: &mut Vec<u32>,
        chunk_offsets: &mut Vec<u64>,
        stsc: &mut Vec<(u32, u32)>,
    ) {
        let mut pos = 0usize;
        while pos + 8 <= buf.len() {
            let mut size = be_u32(&buf[pos..pos + 4]) as usize;
            let typ = &buf[pos + 4..pos + 8];
            let mut hdr = 8usize;
            if size == 1 {
                size = be_u64(&buf[pos + 8..pos + 16]) as usize;
                hdr = 16;
            } else if size == 0 {
                size = buf.len() - pos;
            }
            if size < hdr || pos + size > buf.len() {
                break;
            }
            let payload = &buf[pos + hdr..pos + size];
            match typ {
                b"moov" | b"trak" | b"mdia" | b"minf" | b"stbl" => {
                    walk(payload, cookie, sample_sizes, chunk_offsets, stsc);
                }
                b"stsd" => {
                    // 4B ver/flags + 4B entry_count, then sample entry boxes
                    if payload.len() > 8 {
                        walk(&payload[8..], cookie, sample_sizes, chunk_offsets, stsc);
                    }
                }
                b"alac" => {
                    // Inside stsd: AudioSampleEntry has 28B header before child boxes;
                    // Inside the inner 'alac' box: 4B ver/flags + 24B ALACSpecificConfig.
                    if payload.len() == 28 && payload[0..4] == [0, 0, 0, 0] {
                        *cookie = payload[4..28].to_vec();
                    } else if payload.len() > 28 {
                        walk(&payload[28..], cookie, sample_sizes, chunk_offsets, stsc);
                    }
                }
                b"stsz" => {
                    let fixed_size = be_u32(&payload[4..8]);
                    let count = be_u32(&payload[8..12]) as usize;
                    if fixed_size != 0 {
                        sample_sizes.resize(count, fixed_size);
                    } else {
                        for i in 0..count {
                            let off = 12 + i * 4;
                            sample_sizes.push(be_u32(&payload[off..off + 4]));
                        }
                    }
                }
                b"stco" => {
                    let count = be_u32(&payload[4..8]) as usize;
                    for i in 0..count {
                        let off = 8 + i * 4;
                        chunk_offsets.push(be_u32(&payload[off..off + 4]) as u64);
                    }
                }
                b"co64" => {
                    let count = be_u32(&payload[4..8]) as usize;
                    for i in 0..count {
                        let off = 8 + i * 8;
                        chunk_offsets.push(be_u64(&payload[off..off + 8]));
                    }
                }
                b"stsc" => {
                    let count = be_u32(&payload[4..8]) as usize;
                    for i in 0..count {
                        let off = 8 + i * 12;
                        stsc.push((be_u32(&payload[off..off + 4]), be_u32(&payload[off + 4..off + 8])));
                    }
                }
                _ => {}
            }
            pos += size;
        }
    }

    walk(data, &mut cookie, &mut sample_sizes, &mut chunk_offsets, &mut stsc);
    assert_eq!(cookie.len(), 24, "expected 24-byte ALACSpecificConfig");
    assert!(!sample_sizes.is_empty(), "no ALAC samples found");

    let mut packets = Vec::with_capacity(sample_sizes.len());
    let mut sample_idx = 0usize;
    for (chunk_idx, &chunk_off) in chunk_offsets.iter().enumerate() {
        let chunk_num = (chunk_idx + 1) as u32;
        let spc = stsc
            .iter()
            .rev()
            .find(|&&(first, _)| first <= chunk_num)
            .map(|&(_, n)| n as usize)
            .expect("stsc entry");
        let mut off = chunk_off as usize;
        for _ in 0..spc {
            if sample_idx >= sample_sizes.len() {
                break;
            }
            let sz = sample_sizes[sample_idx] as usize;
            packets.push(data[off..off + sz].to_vec());
            off += sz;
            sample_idx += 1;
        }
    }
    assert_eq!(packets.len(), sample_sizes.len());
    let _ = be_u16; // keep helper symmetry
    (cookie, packets)
}

fn gen_alac_m4a(out_path: &Path, sample_rate: u32, channels: u8, sample_fmt: &str) {
    let ch_layout = if channels == 1 { "mono" } else { "stereo" };
    // Multi-tone signal (440 Hz + 1 kHz + 5 kHz) for 1.5 seconds
    let filter = format!(
        "aevalsrc='0.3*sin(2*PI*440*t)+0.2*sin(2*PI*1000*t)+0.1*sin(2*PI*5000*t)':s={}:c={}:d=1.5",
        sample_rate, ch_layout
    );
    let status = Command::new("ffmpeg")
        .args([
            "-y",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            &filter,
            "-c:a",
            "alac",
            "-sample_fmt",
            sample_fmt,
            out_path.to_str().unwrap(),
        ])
        .status()
        .expect("run ffmpeg encode");
    assert!(status.success(), "ffmpeg encode failed");
}

fn decode_with_ffmpeg(m4a_path: &Path, fmt: &str) -> Vec<u8> {
    let out = Command::new("ffmpeg")
        .args(["-v", "error", "-i", m4a_path.to_str().unwrap(), "-f", fmt, "-acodec", if fmt == "s16le" { "pcm_s16le" } else { "pcm_f32le" }, "-"])
        .output()
        .expect("run ffmpeg decode");
    assert!(out.status.success(), "ffmpeg decode failed: {}", String::from_utf8_lossy(&out.stderr));
    out.stdout
}

fn verify_profile(dir: &Path, name: &str, sample_rate: u32, channels: u8, sample_fmt: &str, expected_bits: u8) {
    let m4a = dir.join(format!("{name}.m4a"));
    gen_alac_m4a(&m4a, sample_rate, channels, sample_fmt);
    let raw = fs::read(&m4a).unwrap();
    let (cookie, packets) = parse_mp4_alac(&raw);

    let mut session_16 = AlacSession::from_cookie(&cookie).expect("session 16");
    let mut session_32 = AlacSession::from_cookie(&cookie).expect("session f32");
    assert_eq!(session_16.sample_rate(), sample_rate);
    assert_eq!(session_16.channels(), channels);
    assert_eq!(session_16.bit_depth(), expected_bits);

    let mut got_s16 = Vec::new();
    let mut got_f32 = Vec::new();
    let mut scratch16 = vec![0u8; session_16.max_output_bytes(false)];
    let mut scratch32 = vec![0u8; session_32.max_output_bytes(true)];

    for pkt in &packets {
        let n16 = session_16.decode_to_pcm16(pkt, &mut scratch16).expect("decode pcm16");
        got_s16.extend_from_slice(&scratch16[..n16]);
        let n32 = session_32.decode_to_f32(pkt, &mut scratch32).expect("decode f32");
        got_f32.extend_from_slice(&scratch32[..n32]);
    }

    let ref_s16 = decode_with_ffmpeg(&m4a, "s16le");
    assert_eq!(got_s16.len(), ref_s16.len(), "{name}: s16 byte count mismatch");
    if expected_bits == 16 {
        assert_eq!(got_s16, ref_s16, "{name}: 16-bit PCM is not bit-exact against ffmpeg");
    } else {
        // 24-bit truncated to 16-bit vs ffmpeg s16le (ffmpeg may dither or round): verify <= 1 LSB
        let max_lsb_diff = got_s16
            .chunks_exact(2)
            .zip(ref_s16.chunks_exact(2))
            .map(|(a, b)| (i16::from_le_bytes([a[0], a[1]]) as i32 - i16::from_le_bytes([b[0], b[1]]) as i32).abs())
            .max()
            .unwrap_or(0);
        assert!(max_lsb_diff <= 1, "{name}: 24->16 max_lsb_diff={max_lsb_diff}");
    }

    let ref_f32 = decode_with_ffmpeg(&m4a, "f32le");
    assert_eq!(got_f32.len(), ref_f32.len(), "{name}: f32 byte count mismatch");
    let max_f32_err = got_f32
        .chunks_exact(4)
        .zip(ref_f32.chunks_exact(4))
        .map(|(a, b)| {
            let fa = f32::from_le_bytes([a[0], a[1], a[2], a[3]]);
            let fb = f32::from_le_bytes([b[0], b[1], b[2], b[3]]);
            (fa - fb).abs()
        })
        .fold(0.0f32, f32::max);
    assert!(max_f32_err < 1e-6, "{name}: f32 max error {max_f32_err} >= 1e-6");

    // Mid-stream seek simulation: decode starting from packet index 5 in a fresh session
    let mut seek_session = AlacSession::from_cookie(&cookie).unwrap();
    let mut seek_buf = vec![0u8; seek_session.max_output_bytes(true)];
    let n_seek = seek_session.decode_to_f32(&packets[5], &mut seek_buf).expect("mid-stream decode");
    let pkt_bytes = n_seek;
    let offset = (0..5)
        .map(|i| {
            let mut s = AlacSession::from_cookie(&cookie).unwrap();
            let mut b = vec![0u8; s.max_output_bytes(true)];
            s.decode_to_f32(&packets[i], &mut b).unwrap()
        })
        .sum::<usize>();
    assert_eq!(&seek_buf[..pkt_bytes], &got_f32[offset..offset + pkt_bytes], "{name}: seek packet mismatch");
}

#[test]
fn empirical_ffmpeg_reference_profiles() {
    let dir = TempDir::new("profiles");
    verify_profile(&dir.0, "16_44k_stereo", 44_100, 2, "s16p", 16);
    verify_profile(&dir.0, "24_48k_stereo", 48_000, 2, "s32p", 24);
    verify_profile(&dir.0, "24_96k_stereo", 96_000, 2, "s32p", 24);
    verify_profile(&dir.0, "24_192k_stereo", 192_000, 2, "s32p", 24);
    verify_profile(&dir.0, "16_44k_mono", 44_100, 1, "s16p", 16);
}

#[test]
fn cookie_variants_and_explicit_errors() {
    let dir = TempDir::new("errors");
    let m4a = dir.0.join("sample.m4a");
    gen_alac_m4a(&m4a, 44_100, 2, "s16p");
    let raw = fs::read(&m4a).unwrap();
    let (cookie24, packets) = parse_mp4_alac(&raw);

    // 28-byte version/flags + cookie
    let mut cookie28 = vec![0u8; 4];
    cookie28.extend_from_slice(&cookie24);
    let mut s28 = AlacSession::from_cookie(&cookie28).expect("28-byte cookie");
    let mut out = vec![0u8; s28.max_output_bytes(false)];
    assert!(s28.decode_to_pcm16(&packets[0], &mut out).is_ok());

    // Invalid cookies
    assert!(matches!(AlacSession::from_cookie(&[0u8; 10]), Err(AlacError::InvalidCookie(_))));
    let mut bad_depth = cookie24.clone();
    bad_depth[5] = 8;
    assert!(matches!(AlacSession::from_cookie(&bad_depth), Err(AlacError::InvalidCookie(_))));

    // Undersized output buffer
    let mut tiny = [0u8; 16];
    assert!(matches!(
        s28.decode_to_pcm16(&packets[0], &mut tiny),
        Err(AlacError::BufferTooSmall { .. })
    ));

    // Empty and truncated packets return Err, never panic
    assert!(matches!(s28.decode_to_pcm16(&[], &mut out), Err(AlacError::DecodeFailed(_))));
    assert!(matches!(s28.decode_to_f32(&packets[0][..3], &mut out), Err(AlacError::DecodeFailed(_))));
}
