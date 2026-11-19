use cadence_container::{mux_m4p, TrackMetadata};

fn boxed(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(8 + body.len());
    out.extend_from_slice(&((8 + body.len()) as u32).to_be_bytes());
    out.extend_from_slice(kind);
    out.extend_from_slice(body);
    out
}

fn full_box(kind: &[u8; 4], version: u8, flags: u32, payload: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(4 + payload.len());
    body.push(version);
    body.extend_from_slice(&flags.to_be_bytes()[1..4]);
    body.extend_from_slice(payload);
    boxed(kind, &body)
}

fn build_synthetic_m4p(samples: &[&[u8]], key: u8) -> Vec<u8> {
    let ftyp = boxed(b"ftyp", b"M4A \x00\x00\x00\x00M4A mp42isom");

    let mut mdhd = vec![0u8; 20];
    mdhd[8..12].copy_from_slice(&44100u32.to_be_bytes());
    let mdhd = full_box(b"mdhd", 0, 0, &mdhd);

    let esds_payload = [0x03, 0x19, 0x00, 0x01, 0x00, 0x04, 0x11, 0x40, 0x15, 0x00, 0x00, 0x00, 0x00, 0x03, 0xE8, 0x00, 0x00, 0x03, 0xE8, 0x00, 0x05, 0x02, 0x12, 0x10];
    let esds = full_box(b"esds", 0, 0, &esds_payload);
    let frma = boxed(b"frma", b"mp4a");
    let sinf = boxed(b"sinf", &frma);

    let mut enca_body = vec![0u8; 28];
    enca_body[16..18].copy_from_slice(&2u16.to_be_bytes());
    enca_body[18..20].copy_from_slice(&16u16.to_be_bytes());
    enca_body[24..28].copy_from_slice(&(44100u32 << 16).to_be_bytes());
    enca_body.extend_from_slice(&esds);
    enca_body.extend_from_slice(&sinf);
    let enca = boxed(b"enca", &enca_body);

    let mut stsd_payload = Vec::new();
    stsd_payload.extend_from_slice(&1u32.to_be_bytes());
    stsd_payload.extend_from_slice(&enca);
    let stsd = full_box(b"stsd", 0, 0, &stsd_payload);

    let mut stts_payload = Vec::new();
    stts_payload.extend_from_slice(&1u32.to_be_bytes());
    stts_payload.extend_from_slice(&(samples.len() as u32).to_be_bytes());
    stts_payload.extend_from_slice(&1024u32.to_be_bytes());
    let stts = full_box(b"stts", 0, 0, &stts_payload);

    let mut stsc_payload = Vec::new();
    stsc_payload.extend_from_slice(&1u32.to_be_bytes());
    stsc_payload.extend_from_slice(&1u32.to_be_bytes());
    stsc_payload.extend_from_slice(&(samples.len() as u32).to_be_bytes());
    stsc_payload.extend_from_slice(&1u32.to_be_bytes());
    let stsc = full_box(b"stsc", 0, 0, &stsc_payload);

    let mut stsz_payload = Vec::new();
    stsz_payload.extend_from_slice(&0u32.to_be_bytes());
    stsz_payload.extend_from_slice(&(samples.len() as u32).to_be_bytes());
    for s in samples {
        stsz_payload.extend_from_slice(&(s.len() as u32).to_be_bytes());
    }
    let stsz = full_box(b"stsz", 0, 0, &stsz_payload);

    // Placeholder stco; patched after moov size is known.
    let stco_placeholder = full_box(b"stco", 0, 0, &[0, 0, 0, 1, 0, 0, 0, 0]);
    let stbl = boxed(b"stbl", &[stsd, stts, stsc, stsz, stco_placeholder].concat());
    let minf = boxed(b"minf", &stbl);
    let mdia = boxed(b"mdia", &[mdhd.clone(), minf].concat());
    let trak = boxed(b"trak", &mdia);
    let moov_placeholder = boxed(b"moov", &trak);

    let mdat_offset = (ftyp.len() + moov_placeholder.len() + 8) as u32;
    let mut stco_payload = Vec::new();
    stco_payload.extend_from_slice(&1u32.to_be_bytes());
    stco_payload.extend_from_slice(&mdat_offset.to_be_bytes());
    let stco = full_box(b"stco", 0, 0, &stco_payload);

    let stbl = boxed(b"stbl", &[
        full_box(b"stsd", 0, 0, &stsd_payload),
        full_box(b"stts", 0, 0, &stts_payload),
        full_box(b"stsc", 0, 0, &stsc_payload),
        full_box(b"stsz", 0, 0, &stsz_payload),
        stco,
    ].concat());
    let moov = boxed(b"moov", &boxed(b"trak", &boxed(b"mdia", &[mdhd, boxed(b"minf", &stbl)].concat())));

    let mut mdat_body = Vec::new();
    for s in samples {
        for &b in *s {
            mdat_body.push(b ^ key);
        }
    }
    let mdat = boxed(b"mdat", &mdat_body);

    [ftyp, moov, mdat].concat()
}

#[test]
fn decrypts_non_fragmented_m4p_and_writes_clear_tagged_m4a() {
    let raw_samples: [&[u8]; 3] = [b"frame-one-data", b"frame-two-longer", b"frame-3"];
    let key = 0x5Au8;
    let m4p = build_synthetic_m4p(&raw_samples, key);

    let out_path = std::env::temp_dir().join(format!("cadence_m4p_test_{}.m4a", std::process::id()));
    let meta = TrackMetadata {
        title: Some("the rot".to_string()),
        artist: Some("Dean Blunt".to_string()),
        album: Some("BLACK METAL 2".to_string()),
        ..Default::default()
    };

    mux_m4p(&m4p, &out_path, meta.clone(), |buf| {
        for b in buf.iter_mut() {
            *b ^= key;
        }
        Ok::<(), String>(())
    })
    .expect("mux_m4p should succeed");

    let clear = std::fs::read(&out_path).expect("read output m4a");
    let _ = std::fs::remove_file(&out_path);

    let mut drms_m4p = m4p.clone();
    if let Some(pos) = drms_m4p.windows(4).position(|w| w == b"enca") {
        drms_m4p[pos..pos + 4].copy_from_slice(b"drms");
    }
    let out_drms = std::env::temp_dir().join(format!("cadence_drms_test_{}.m4a", std::process::id()));
    mux_m4p(&drms_m4p, &out_drms, meta, |buf| {
        for b in buf.iter_mut() {
            *b ^= key;
        }
        Ok::<(), String>(())
    })
    .expect("mux_m4p drms should succeed");
    let drms_clear = std::fs::read(&out_drms).expect("read drms m4a");
    let _ = std::fs::remove_file(&out_drms);
    assert_eq!(clear, drms_clear);

    assert!(clear.windows(4).any(|w| w == b"mp4a"));
    assert!(!clear.windows(4).any(|w| w == b"enca"));
    assert!(!clear.windows(4).any(|w| w == b"sinf"));
    assert!(clear.windows(7).any(|w| w == b"the rot"));
    assert!(clear.windows(10).any(|w| w == b"Dean Blunt"));
    for s in raw_samples {
        assert!(clear.windows(s.len()).any(|w| w == s));
    }
}
