use std::ffi::c_void;
use std::fs;
use std::path::Path;

use jni::objects::{JClass, JString};
use jni::sys::{jint, JNI_ERR};
use jni::JNIEnv;
use cadence_container::{clear_init, clear_segment, decrypt_samples, parse_fragment, parse_init, Mp4Muxer, TrackMetadata, TrackSpec};

fn error(env: &mut JNIEnv, message: &str) -> jint {
    let _ = env.throw_new("java/io/IOException", message);
    -1
}

fn jstring(env: &mut JNIEnv, value: &JString) -> Result<String, jint> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|_| JNI_ERR)
}

fn boxes(data: &[u8]) -> Vec<(usize, usize, [u8; 4])> {
    let mut out = Vec::new();
    let mut at = 0usize;
    while at + 8 <= data.len() {
        let size = u32::from_be_bytes([data[at], data[at + 1], data[at + 2], data[at + 3]]) as usize;
        let mut kind = [0u8; 4];
        kind.copy_from_slice(&data[at + 4..at + 8]);
        let (header, total) = if size == 1 {
            if at + 16 > data.len() { break; }
            let s = usize::try_from(u64::from_be_bytes(data[at + 8..at + 16].try_into().unwrap())).unwrap_or(usize::MAX);
            (16usize, s)
        } else if size == 0 {
            (8usize, data.len() - at)
        } else {
            (8usize, size)
        };
        // Against the room left, not `at + total`: a 64-bit size near u64::MAX would overflow the sum.
        if total < header || total > data.len() - at { break; }
        out.push((at, at + total, kind));
        at += total;
    }
    out
}

/// The key-template path predates the KD plan and is not built here; the app only calls
/// [`decrypt_file_with_plan_meta`]. Kept so the JNI symbol resolves and reports why.
pub fn decrypt_file(_template_json: &str, _input: &Path, _output: &Path) -> Result<(), String> {
    Err("template decrypt is not available in this build; use the KD plan entry point".to_string())
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeDrm_decryptFile(
    mut env: JNIEnv,
    _class: JClass,
    template: JString,
    input: JString,
    output: JString,
) -> jint {
    let template = match jstring(&mut env, &template) { Ok(v) => v, Err(e) => return e };
    let input = match jstring(&mut env, &input) { Ok(v) => v, Err(e) => return e };
    let output = match jstring(&mut env, &output) { Ok(v) => v, Err(e) => return e };
    match decrypt_file(&template, Path::new(&input), Path::new(&output)) {
        Ok(()) => 0,
        Err(message) => error(&mut env, &message),
    }
}

type SampleDecryptorFn = unsafe extern "C" fn(*mut c_void, *mut u8, usize) -> i32;

fn load_native_decryptor() -> Result<SampleDecryptorFn, String> {
    unsafe {
        let handle = libc::dlopen(c"libcadence-native.so".as_ptr(),
                     libc::RTLD_NOW | libc::RTLD_GLOBAL);
        if handle.is_null() {
            return Err("libcadence-native.so not loadable".to_string());
        }
        let name = b"core_wrapper_decrypt_sample\0";
        let sym = libc::dlsym(handle, name.as_ptr() as *const libc::c_char);
        if sym.is_null() {
            return Err("core_wrapper_decrypt_sample not found".to_string());
        }
        Ok(std::mem::transmute::<*mut c_void, SampleDecryptorFn>(sym))
    }
}

/// One per-sample decrypt backend. Abstracted so the container, muxer, tag and
/// pipeline logic can be built and tested on the host without the Apple libs.
pub trait SampleDecryptor: Send + Sync {
    /// Decrypts `buf` in place. `kd` is the FairPlay context handle for the
    /// fragment (Apple's `Nfc` takes a 32-bit id widened into 64 bits).
    fn decrypt(&self, kd: i64, buf: &mut [u8]) -> i32;
}

/// Real backend: the Apple per-sample entry point, loaded from libcadence-native.so.
pub struct AppleDecryptor {
    f: SampleDecryptorFn,
}

impl AppleDecryptor {
    pub fn new() -> Result<Self, String> {
        load_native_decryptor().map(|f| AppleDecryptor { f })
    }
}

impl SampleDecryptor for AppleDecryptor {
    fn decrypt(&self, kd: i64, buf: &mut [u8]) -> i32 {
        unsafe { (self.f)(kd as *mut c_void, buf.as_mut_ptr(), buf.len()) }
    }
}

/// Host-only backend. Reversible but not the identity, so "nothing decrypted"
/// is still detectable in a checksum.
pub struct MockDecryptor;

impl SampleDecryptor for MockDecryptor {
    fn decrypt(&self, _kd: i64, buf: &mut [u8]) -> i32 {
        for b in buf.iter_mut() {
            *b ^= 0x5A;
        }
        0
    }
}

/// Host-only backend that leaves bytes untouched, for clear (unencrypted) fragmented input:
/// the output must then decode to exactly the source audio.
pub struct IdentityDecryptor;

impl SampleDecryptor for IdentityDecryptor {
    fn decrypt(&self, _kd: i64, _buf: &mut [u8]) -> i32 {
        0
    }
}

pub fn decrypt_file_with_kd(kd: *mut c_void, input: &Path, output: &Path) -> Result<(), String> {
    if kd.is_null() { return Err("null kd context".to_string()); }
    decrypt_file_with_plan(std::slice::from_ref(&(kd as i64)), input, output)
}

/// Decrypts a fragmented HLS package where different fragments are protected by
/// different FairPlay contexts. `kds[i]` is the KD handle for fragment `i`
/// (the last handle is reused for any extra fragments). Apple's HLS ALAC stream
/// puts a prefetch key on the first segment and the real content key on the
/// rest, so a single context for the whole file silently corrupts segment 1.
pub fn decrypt_file_with_plan(kds: &[i64], input: &Path, output: &Path) -> Result<(), String> {
    decrypt_file_with_plan_meta(kds, input, output, None)
}

/// Same as [`decrypt_file_with_plan`] but also writes `udta/meta/ilst` tags
/// (title/artist/album/track/disc/year/genre/lyrics/cover) into the `moov`.
pub fn decrypt_file_with_plan_meta(
    kds: &[i64],
    input: &Path,
    output: &Path,
    meta: Option<TrackMetadata>,
) -> Result<(), String> {
    let decryptor = AppleDecryptor::new()?;
    decrypt_with(&decryptor, kds, input, output, meta)
}

/// Backend-agnostic decrypt: everything except the per-sample cipher.
fn decrypt_or_error<D: SampleDecryptor + ?Sized>(decryptor: &D, kd: i64, buf: &mut [u8]) -> Result<(), String> {
    match decryptor.decrypt(kd, buf) {
        0 => Ok(()),
        rc => Err(format!("sample decrypt failed: {rc}")),
    }
}

/// Clear equivalent of one streamed HLS segment: same bytes with every sample decrypted, so
/// the player can read it as plain fragmented MP4. `init` is the package's original init.
pub fn clear_segment_with<D: SampleDecryptor + ?Sized>(
    decryptor: &D,
    kd: i64,
    init: &[u8],
    segment: &[u8],
) -> Result<Vec<u8>, String> {
    if kd == 0 {
        return Err("null kd context".to_string());
    }
    let init = parse_init(init).map_err(|e| format!("parse init: {e:?}"))?;
    clear_segment(segment, &init, |buf| decrypt_or_error(decryptor, kd, buf))
        .map_err(|e| format!("clear segment: {e:?}"))
}

/// The Apple decrypt entry point, resolved once: streaming calls it for every segment.
fn apple_decryptor() -> Result<&'static AppleDecryptor, String> {
    static APPLE: std::sync::OnceLock<Result<AppleDecryptor, String>> = std::sync::OnceLock::new();
    APPLE.get_or_init(AppleDecryptor::new).as_ref().map_err(Clone::clone)
}

fn throw_null(env: &mut JNIEnv, message: &str) -> jni::sys::jbyteArray {
    let _ = env.throw_new("java/io/IOException", message);
    std::ptr::null_mut()
}

/// Rewrites a package's init segment into its clear form (protected entries become ALAC).
#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeDrm_clearInit(
    mut env: JNIEnv,
    _class: JClass,
    init: jni::objects::JByteArray,
) -> jni::sys::jbyteArray {
    let init = match env.convert_byte_array(&init) {
        Ok(v) => v,
        Err(_) => return throw_null(&mut env, "cannot read init bytes"),
    };
    let clear = match clear_init(&init) {
        Ok(v) => v,
        Err(e) => return throw_null(&mut env, &format!("clear init: {e:?}")),
    };
    match env.byte_array_from_slice(&clear) {
        Ok(a) => a.into_raw(),
        Err(_) => throw_null(&mut env, "cannot allocate clear init"),
    }
}

/// Decrypts one HLS segment with the FairPlay context `kd` and returns its clear form.
#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeDrm_clearSegment(
    mut env: JNIEnv,
    _class: JClass,
    kd: jni::sys::jlong,
    init: jni::objects::JByteArray,
    segment: jni::objects::JByteArray,
) -> jni::sys::jbyteArray {
    let init = match env.convert_byte_array(&init) {
        Ok(v) => v,
        Err(_) => return throw_null(&mut env, "cannot read init bytes"),
    };
    let segment = match env.convert_byte_array(&segment) {
        Ok(v) => v,
        Err(_) => return throw_null(&mut env, "cannot read segment bytes"),
    };
    let decryptor = match apple_decryptor() {
        Ok(d) => d,
        Err(e) => return throw_null(&mut env, &e),
    };
    let clear = match clear_segment_with(decryptor, kd, &init, &segment) {
        Ok(v) => v,
        Err(e) => return throw_null(&mut env, &e),
    };
    match env.byte_array_from_slice(&clear) {
        Ok(a) => a.into_raw(),
        Err(_) => throw_null(&mut env, "cannot allocate clear segment"),
    }
}

/// Decrypted samples of one fragment, kept in order for the muxer.
pub struct FragmentOut {
    samples: Vec<Vec<u8>>,
    durations: Vec<u32>,
}

/// Parses one `moof`+`mdat` segment and decrypts its samples. Pure: it only
/// borrows the package bytes, so it is safe to call from several threads.
fn process_fragment<D: SampleDecryptor + ?Sized>(
    decryptor: &D,
    segment: &[u8],
    init: &cadence_container::InitSegment,
    kd: i64,
    scale_num: u64,
    scale_den: u64,
) -> Result<FragmentOut, String> {
    let fragment = parse_fragment(segment, init).map_err(|e| format!("parse fragment: {e:?}"))?;
    // Decrypt a copy of the whole segment in place (the same routine streaming uses), then
    // slice the samples out of it for the muxer.
    let mut clear = segment.to_vec();
    decrypt_samples(&mut clear, &fragment, |buf| decrypt_or_error(decryptor, kd, buf))
        .map_err(|e| format!("decrypt fragment: {e:?}"))?;
    let mut samples = Vec::with_capacity(fragment.samples.len());
    let mut durations = Vec::with_capacity(fragment.samples.len());
    for sample in &fragment.samples {
        samples.push(clear[sample.offset..sample.offset + sample.size as usize].to_vec());
        // A zero here means the time table would be wrong; fail instead of writing it.
        let scaled = u64::from(sample.duration) * scale_num / scale_den;
        if scaled == 0 || scaled > u64::from(u32::MAX) {
            return Err(format!("sample duration {} does not scale to the output timescale", sample.duration));
        }
        durations.push(scaled as u32);
    }
    Ok(FragmentOut { samples, durations })
}

pub fn decrypt_with<D: SampleDecryptor + ?Sized>(
    decryptor: &D,
    kds: &[i64],
    input: &Path,
    output: &Path,
    meta: Option<TrackMetadata>,
) -> Result<(), String> {
    decrypt_with_workers(decryptor, kds, input, output, meta, 1)
}

/// Same as [`decrypt_with`], but decrypts up to `workers` fragments at a time.
/// Fragments are independent (per-sample CBC, no cross-sample chaining), so the
/// only ordering requirement is that samples are muxed in fragment order.
pub fn decrypt_with_workers<D: SampleDecryptor + ?Sized>(
    decryptor: &D,
    kds: &[i64],
    input: &Path,
    output: &Path,
    meta: Option<TrackMetadata>,
    workers: usize,
) -> Result<(), String> {
    if kds.is_empty() { return Err("empty kd plan".to_string()); }
    if kds.contains(&0) { return Err("null kd context in plan".to_string()); }
    let data = fs::read(input).map_err(|e| format!("read input: {e}"))?;
    let all = boxes(&data);
    let moov = all.iter().find(|b| &b.2 == b"moov").ok_or("no moov in package")?;
    let init = parse_init(&data[..moov.1]).map_err(|e| format!("parse init: {e:?}"))?;
    let spec = TrackSpec {
        codec: init.codec,
        cookie: init.cookie.clone(),
        channels: init.channels,
        sample_rate: init.sample_rate,
        bit_depth: init.bit_depth,
    };
    let scale_num = u64::from(init.sample_rate);
    let scale_den = u64::from(init.timescale.max(1));

    // Fragment ranges first: parallel work needs to know the whole set.
    let mut ranges: Vec<(usize, usize, i64)> = Vec::new();
    let mut index = 0usize;
    let mut fragment_index = 0usize;
    while index < all.len() {
        if &all[index].2 != b"moof" { index += 1; continue; }
        let kd = kds[fragment_index.min(kds.len() - 1)];
        fragment_index += 1;
        let moof_start = all[index].0;
        let Some(mdat) = all[index + 1..].iter().find(|b| &b.2 == b"mdat") else { break; };
        if mdat.0 < all[index].1 { break; }
        ranges.push((moof_start, mdat.1, kd));
        index += 1;
    }
    if ranges.is_empty() { return Err("no moof/mdat fragments in package".to_string()); }

    let mut muxer = Mp4Muxer::create(output, spec).map_err(|e| format!("muxer: {e}"))?;
    if let Some(m) = meta.clone() {
        muxer.set_metadata(m);
    }
    let mut timing = StageTimes::default();

    if workers <= 1 {
        for (moof_start, mdat_end, kd) in &ranges {
            let t = std::time::Instant::now();
            let out = process_fragment(decryptor, &data[*moof_start..*mdat_end], &init, *kd, scale_num, scale_den)?;
            timing.parse_us += t.elapsed().as_micros();
            let t = std::time::Instant::now();
            timing.samples += out.samples.len() as u64;
            for (plain, duration) in out.samples.iter().zip(out.durations.iter().copied()) {
                timing.bytes += plain.len() as u64;
                muxer.add_sample(plain, duration).map_err(|e| format!("add sample: {e}"))?;
            }
            timing.mux_us += t.elapsed().as_micros();
        }
    } else {
        let t = std::time::Instant::now();
        let (tx, rx) = std::sync::mpsc::channel::<(usize, Result<FragmentOut, String>)>();
        let next = std::sync::atomic::AtomicUsize::new(0);
        std::thread::scope(|scope| {
            for _ in 0..workers.min(ranges.len()) {
                let tx = tx.clone();
                let next = &next;
                let ranges = &ranges;
                let data = &data;
                let init = &init;
                scope.spawn(move || loop {
                    let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    if i >= ranges.len() { break; }
                    let (a, b, kd) = ranges[i];
                    let out = process_fragment(decryptor, &data[a..b], init, kd, scale_num, scale_den);
                    let _ = tx.send((i, out));
                });
            }
        });
        drop(tx);
        let mut slots: Vec<Option<FragmentOut>> = Vec::new();
        slots.resize_with(ranges.len(), || None);
        let mut first_error: Option<String> = None;
        for (i, res) in rx {
            match res {
                Ok(out) => slots[i] = Some(out),
                Err(e) => {
                    if first_error.is_none() { first_error = Some(e); }
                }
            }
        }
        timing.decrypt_us += t.elapsed().as_micros();
        if let Some(e) = first_error { return Err(e); }
        // Mux strictly in fragment order: stts/stsz must describe decode order.
        let t = std::time::Instant::now();
        for slot in slots.into_iter() {
            let out = slot.ok_or("fragment slot missing after parallel decrypt")?;
            timing.samples += out.samples.len() as u64;
            for (plain, duration) in out.samples.iter().zip(out.durations.iter().copied()) {
                timing.bytes += plain.len() as u64;
                muxer.add_sample(plain, duration).map_err(|e| format!("add sample: {e}"))?;
            }
        }
        timing.mux_us += t.elapsed().as_micros();
    }

    muxer.finish().map_err(|e| format!("finish: {e}"))?;
    let t_fast = std::time::Instant::now();
    faststart(output)?;
    timing.faststart_us += t_fast.elapsed().as_micros();
    timing.report();
    Ok(())
}


#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_print(prio: i32, tag: *const std::os::raw::c_char, fmt: *const std::os::raw::c_char, ...) -> i32;
}

/// Stage timings for one decrypt, reported to logcat as `cadence-timing`.
#[derive(Default)]
struct StageTimes {
    parse_us: u128,
    decrypt_us: u128,
    mux_us: u128,
    faststart_us: u128,
    samples: u64,
    bytes: u64,
}

impl StageTimes {
    fn report(&self) {
        let secs = |us: u128| (us / 1000) as u64;
        #[cfg(target_os = "android")]
        {
            let tag = std::ffi::CString::new("cadence-timing").unwrap();
            let fmt = std::ffi::CString::new(
                "parse_ms=%llu decrypt_ms=%llu mux_ms=%llu faststart_ms=%llu samples=%llu bytes=%llu",
            ).unwrap();
            unsafe {
                __android_log_print(
                    4, tag.as_ptr(), fmt.as_ptr(),
                    secs(self.parse_us), secs(self.decrypt_us), secs(self.mux_us),
                    secs(self.faststart_us), self.samples, self.bytes,
                );
            }
        }
        #[cfg(not(target_os = "android"))]
        eprintln!(
            "cadence-timing: parse_ms={} decrypt_ms={} mux_ms={} faststart_ms={} samples={} bytes={}",
            secs(self.parse_us), secs(self.decrypt_us), secs(self.mux_us),
            secs(self.faststart_us), self.samples, self.bytes
        );
    }
}

/// Largest box accepted from a stream. Apple's fragments are a few MB; a size near this is a corrupt
/// header, not media.
const MAX_STREAM_BOX: usize = 1 << 30;

/// Reads one ISO-BMFF box (header + payload) from `r`.
fn read_box<R: std::io::Read>(r: &mut R) -> Result<Option<Vec<u8>>, String> {
    let mut hdr = [0u8; 8];
    match r.read_exact(&mut hdr) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(format!("read box header: {e}")),
    }
    let size = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as usize;
    if size < 8 { return Err(format!("bad box size {size}")); }
    if size > MAX_STREAM_BOX { return Err(format!("implausible box size {size}")); }
    // Read what is actually there instead of reserving the declared size first: a corrupt or
    // truncated package could otherwise allocate up to 4 GiB before failing.
    let mut b = Vec::with_capacity(size.min(1 << 20));
    b.extend_from_slice(&hdr);
    let want = (size - 8) as u64;
    let got = std::io::Read::read_to_end(&mut std::io::Read::take(&mut *r, want), &mut b)
        .map_err(|e| format!("read box body: {e}"))?;
    if (got as u64) < want { return Err(format!("read box body: truncated ({got} of {want} bytes)")); }
    Ok(Some(b))
}

/// Streaming decrypt: fragments are consumed as they arrive instead of reading
/// the whole package into memory first. A producer thread reads `moof`+`mdat`
/// pairs into a bounded channel (depth 2) while the calling thread decrypts and
/// muxes them in order, so peak memory is O(fragment) rather than O(package).
///
/// This is the structure that later overlaps network download with decryption;
/// the ordering guarantee is unchanged (fragments are consumed in file order).
pub fn decrypt_streaming<D: SampleDecryptor + ?Sized>(
    decryptor: &D,
    kds: &[i64],
    input: &Path,
    output: &Path,
    meta: Option<TrackMetadata>,
) -> Result<(), String> {
    if kds.is_empty() { return Err("empty kd plan".to_string()); }
    let mut file = fs::File::open(input).map_err(|e| format!("open input: {e}"))?;

    // Head: everything up to and including moov, needed to parse the init.
    let mut head: Vec<u8> = Vec::new();
    loop {
        let Some(b) = read_box(&mut file)? else { return Err("no moov in package".to_string()); };
        let kind = b[4..8].to_vec();
        head.extend_from_slice(&b);
        if kind == b"moov" { break; }
        if head.len() > (8 << 20) { return Err("moov not found in first 8 MiB".to_string()); }
    }
    let init = parse_init(&head).map_err(|e| format!("parse init: {e:?}"))?;
    let spec = TrackSpec {
        codec: init.codec,
        cookie: init.cookie.clone(),
        channels: init.channels,
        sample_rate: init.sample_rate,
        bit_depth: init.bit_depth,
    };
    let scale_num = u64::from(init.sample_rate);
    let scale_den = u64::from(init.timescale.max(1));

    let (tx, rx) = std::sync::mpsc::sync_channel::<Result<Vec<u8>, String>>(2);
    let producer = std::thread::spawn(move || {
        loop {
            match read_box(&mut file) {
                Ok(Some(moof)) => {
                    // Index and marker boxes (styp, sidx, prft, emsg, free, mfra...) carry no
                    // samples; skip them instead of rejecting the package.
                    if &moof[4..8] != b"moof" { continue; }
                    let mdat = loop {
                        match read_box(&mut file) {
                            Ok(Some(b)) if &b[4..8] == b"mdat" => break b,
                            Ok(Some(b)) if &b[4..8] == b"moof" => {
                                let _ = tx.send(Err("moof without mdat".into()));
                                return;
                            }
                            Ok(Some(_)) => continue,
                            Ok(None) => { let _ = tx.send(Err("moof without mdat".into())); return; }
                            Err(e) => { let _ = tx.send(Err(e)); return; }
                        }
                    };
                    let mut seg = moof;
                    seg.extend_from_slice(&mdat);
                    if tx.send(Ok(seg)).is_err() { return; }
                }
                Ok(None) => return,
                Err(e) => { let _ = tx.send(Err(e)); return; }
            }
        }
    });

    let mut muxer = Mp4Muxer::create(output, spec).map_err(|e| format!("muxer: {e}"))?;
    if let Some(m) = meta.clone() { muxer.set_metadata(m); }
    let mut timing = StageTimes::default();
    let mut fi = 0usize;
    for item in rx {
        let seg = item?;
        let kd = kds[fi.min(kds.len() - 1)];
        fi += 1;
        let t = std::time::Instant::now();
        let out = process_fragment(decryptor, &seg, &init, kd, scale_num, scale_den)?;
        timing.parse_us += t.elapsed().as_micros();
        let t = std::time::Instant::now();
        timing.samples += out.samples.len() as u64;
        for (plain, duration) in out.samples.iter().zip(out.durations.iter().copied()) {
            timing.bytes += plain.len() as u64;
            muxer.add_sample(plain, duration).map_err(|e| format!("add sample: {e}"))?;
        }
        timing.mux_us += t.elapsed().as_micros();
    }
    producer.join().map_err(|_| "producer panicked".to_string())?;
    if fi == 0 { return Err("no fragments".to_string()); }

    muxer.finish().map_err(|e| format!("finish: {e}"))?;
    let t = std::time::Instant::now();
    faststart(output)?;
    timing.faststart_us += t.elapsed().as_micros();
    timing.report();
    Ok(())
}

/// Muxes an already-clear fragmented MP4 (init + `moof`/`mdat` segments, no encryption) into a
/// plain `.m4a` with `moov` at the front and `udta` tags. The streaming download path decrypts
/// each HLS segment the same way playback does and concatenates them (clear init, then every
/// clear segment), so the bytes handed here are already plaintext ALAC/AAC and no per-sample
/// cipher runs: [`IdentityDecryptor`] leaves samples untouched while the streaming muxer rebuilds
/// the sample tables, writes the metadata and faststarts the file. This is the same muxer the
/// decrypt path uses, so the output is byte-for-byte what a two-phase decrypt of the same track
/// would have produced.
pub fn mux_clear_with_meta(input: &Path, output: &Path, meta: Option<TrackMetadata>) -> Result<(), String> {
    // `decrypt_streaming` requires a non-empty plan; the identity backend ignores the handle,
    // so one placeholder covers every fragment.
    decrypt_streaming(&IdentityDecryptor, &[1], input, output, meta)
}

/// Builds [`TrackMetadata`] from the flat JSON the app sends.
fn meta_from_json(json: &str, cover: Option<Vec<u8>>, cover_mime: Option<String>) -> TrackMetadata {
    let v: serde_json::Value = serde_json::from_str(json).unwrap_or(serde_json::Value::Null);
    let s = |k: &str| v.get(k).and_then(|x| x.as_str()).filter(|x| !x.is_empty()).map(str::to_string);
    let n = |k: &str| v.get(k).and_then(|x| x.as_u64()).map(|x| x as u16);
    TrackMetadata {
        title: s("title"),
        artist: s("artist"),
        album_artist: s("albumArtist"),
        album: s("album"),
        genre: s("genre"),
        year: s("year"),
        comment: s("comment"),
        lyrics: s("lyrics"),
        copyright: s("copyright"),
        encoder: s("encoder"),
        track: n("track").map(|t| (t, n("trackTotal").unwrap_or(0))),
        disc: n("disc").map(|d| (d, n("discTotal").unwrap_or(0))),
        compilation: v.get("compilation").and_then(|x| x.as_bool()),
        cover,
        cover_mime,
    }
}

/// Moves `moov` in front of `mdat` ("faststart") and patches the single `stco`
/// entry. Players that do not read the tail of a file otherwise find no sample
/// tables and report it as invalid. Best effort: any surprise leaves the file
/// exactly as the muxer wrote it.
fn faststart(path: &Path) -> Result<(), String> {
    use std::io::{Read, Seek, SeekFrom, Write};

    let mut file = fs::File::open(path).map_err(|e| format!("faststart open: {e}"))?;
    let len = file.metadata().map_err(|e| format!("faststart meta: {e}"))?.len() as usize;
    // Must cover ftyp (28) + the 8-byte mdat header at offset 28.
    let mut head = [0u8; 64];
    file.read_exact(&mut head).map_err(|e| format!("faststart head: {e}"))?;

    // Mp4Muxer always emits ftyp, mdat, moov in that order.
    let rd = |b: &[u8], at: usize| u32::from_be_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]]) as usize;
    if &head[4..8] != b"ftyp" {
        return Ok(());
    }
    let ftyp_end = rd(&head, 0);
    if ftyp_end + 8 > head.len() || ftyp_end > len {
        return Ok(());
    }
    let mdat_off = ftyp_end;
    if &head[mdat_off + 4..mdat_off + 8] != b"mdat" {
        return Ok(()); // already rearranged or unexpected layout
    }
    let mdat_size = rd(&head, mdat_off);
    let moov_off = mdat_off + mdat_size;
    if moov_off + 8 > len {
        return Ok(());
    }
    let mut moov_head = [0u8; 8];
    file.seek(SeekFrom::Start(moov_off as u64)).map_err(|e| format!("faststart seek: {e}"))?;
    file.read_exact(&mut moov_head).map_err(|e| format!("faststart moov hdr: {e}"))?;
    if &moov_head[4..8] != b"moov" {
        return Ok(());
    }
    let moov_size = rd(&moov_head, 0);
    if moov_size < 8 || moov_off + moov_size > len {
        return Ok(());
    }

    let mut moov = vec![0u8; moov_size];
    file.seek(SeekFrom::Start(moov_off as u64)).map_err(|e| format!("faststart seek2: {e}"))?;
    file.read_exact(&mut moov).map_err(|e| format!("faststart read moov: {e}"))?;

    // Single chunk => exactly one stco offset to move.
    let new_base = (ftyp_end + moov_size + 8) as u32;
    let mut patched = false;
    let mut i = 0usize;
    while i + 16 <= moov.len() {
        if &moov[i..i + 4] == b"stco" {
            let count = u32::from_be_bytes([moov[i + 8], moov[i + 9], moov[i + 10], moov[i + 11]]);
            if count >= 1 {
                moov[i + 12..i + 16].copy_from_slice(&new_base.to_be_bytes());
                patched = true;
            }
            break;
        }
        i += 1;
    }
    if !patched {
        return Ok(()); // nothing moved, keep the muxer's file
    }

    let tmp = path.with_extension("faststart.tmp");
    // On any failure (a full disk, a read error) the temporary copy, as large as the track, goes.
    let written = (|| -> Result<bool, String> {
        let mut out = fs::File::create(&tmp).map_err(|e| format!("faststart create: {e}"))?;
        out.write_all(&head[..ftyp_end]).map_err(|e| format!("faststart w ftyp: {e}"))?;
        out.write_all(&moov).map_err(|e| format!("faststart w moov: {e}"))?;
        out.write_all(&head[mdat_off..mdat_off + 8]).map_err(|e| format!("faststart w mdat: {e}"))?;

        file.seek(SeekFrom::Start((mdat_off + 8) as u64)).map_err(|e| format!("faststart seek3: {e}"))?;
        let mut buf = vec![0u8; 1 << 20];
        let mut left = mdat_size - 8;
        while left > 0 {
            let want = buf.len().min(left);
            let n = file.read(&mut buf[..want]).map_err(|e| format!("faststart copy: {e}"))?;
            if n == 0 { break; }
            out.write_all(&buf[..n]).map_err(|e| format!("faststart copy w: {e}"))?;
            left -= n;
        }
        if left != 0 {
            return Ok(false);
        }
        out.flush().map_err(|e| format!("faststart flush: {e}"))?;
        Ok(true)
    })();
    match written {
        Ok(true) => {}
        Ok(false) => { let _ = fs::remove_file(&tmp); return Ok(()); }
        Err(e) => { let _ = fs::remove_file(&tmp); return Err(e); }
    }
    drop(file);
    if let Err(e) = fs::rename(&tmp, path) {
        let _ = fs::remove_file(&tmp);
        return Err(format!("faststart rename: {e}"));
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeDrm_decryptFileWithKd(
    mut env: JNIEnv,
    _class: JClass,
    kd: jni::sys::jlong,
    input: JString,
    output: JString,
) -> jint {
    let input = match jstring(&mut env, &input) { Ok(v) => v, Err(e) => return e };
    let output = match jstring(&mut env, &output) { Ok(v) => v, Err(e) => return e };
    match decrypt_file_with_kd(kd as *mut c_void, Path::new(&input), Path::new(&output)) {
        Ok(()) => 0,
        Err(message) => error(&mut env, &message),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeDrm_decryptFileWithKdPlan(
    mut env: JNIEnv,
    _class: JClass,
    kds: jni::objects::JLongArray,
    input: JString,
    output: JString,
) -> jint {
    let count = match env.get_array_length(&kds) {
        Ok(v) => v,
        Err(_) => return error(&mut env, "cannot read kd array length"),
    };
    let mut handles = vec![0i64; count as usize];
    if count > 0
        && env.get_long_array_region(&kds, 0, &mut handles).is_err() {
            return error(&mut env, "cannot read kd array");
        }
    let input = match jstring(&mut env, &input) { Ok(v) => v, Err(e) => return e };
    let output = match jstring(&mut env, &output) { Ok(v) => v, Err(e) => return e };
    match decrypt_file_with_plan(&handles, Path::new(&input), Path::new(&output)) {
        Ok(()) => 0,
        Err(message) => error(&mut env, &message),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeDrm_decryptFileWithKdPlanMeta(
    mut env: JNIEnv,
    _class: JClass,
    kds: jni::objects::JLongArray,
    input: JString,
    output: JString,
    meta: JString,
    cover: jni::objects::JByteArray,
    cover_mime: JString,
    workers: jni::sys::jint,
) -> jint {
    let count = match env.get_array_length(&kds) {
        Ok(v) => v,
        Err(_) => return error(&mut env, "cannot read kd array length"),
    };
    let mut handles = vec![0i64; count as usize];
    if count > 0 && env.get_long_array_region(&kds, 0, &mut handles).is_err() {
        return error(&mut env, "cannot read kd array");
    }
    let input = match jstring(&mut env, &input) { Ok(v) => v, Err(e) => return e };
    let output = match jstring(&mut env, &output) { Ok(v) => v, Err(e) => return e };
    let meta_json = match jstring(&mut env, &meta) { Ok(v) => v, Err(e) => return e };
    let mime = if cover_mime.is_null() { String::new() } else {
        match jstring(&mut env, &cover_mime) { Ok(v) => v, Err(e) => return e }
    };

    let mut cover_bytes: Option<Vec<u8>> = None;
    if !cover.is_null() {
        if let Ok(len) = env.get_array_length(&cover) {
            if len > 0 {
                let mut buf = vec![0u8; len as usize];
                if env.get_byte_array_region(&cover, 0, unsafe {
                    std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut i8, buf.len())
                }).is_ok() {
                    cover_bytes = Some(buf);
                }
            }
        }
    }

    let trimmed = meta_json.trim();
    let md = if trimmed.is_empty() || trimmed == "{}" {
        None
    } else {
        Some(meta_from_json(trimmed, cover_bytes, if mime.is_empty() { None } else { Some(mime) }))
    };
    let decryptor = match AppleDecryptor::new() {
        Ok(d) => d,
        Err(e) => return error(&mut env, &e),
    };
    // Streaming is the shipping path: peak memory is O(fragment) instead of
    // O(package), which matters for long tracks (a 70-min ALAC package is ~311 MB
    // held resident by the batch path). `workers > 1` keeps the batch engine so
    // experiment E5 stays reproducible; E5 measured no speedup, so it is never
    // selected by the app (DrmService passes 1).
    let w = if workers < 1 { 1 } else { workers as usize };
    let result = if w <= 1 {
        decrypt_streaming(&decryptor, &handles, Path::new(&input), Path::new(&output), md)
    } else {
        decrypt_with_workers(&decryptor, &handles, Path::new(&input), Path::new(&output), md, w)
    };
    match result {
        Ok(()) => 0,
        Err(message) => error(&mut env, &message),
    }
}

/// Muxes an already-clear concatenated fragmented MP4 into a tagged `.m4a` (see
/// [`mux_clear_with_meta`]). The metadata/cover arguments mirror
/// [`Java_app_cadence_backend_engine_media_NativeDrm_decryptFileWithKdPlanMeta`]; there is no
/// key plan because the segments were already decrypted one at a time by the streaming cache.
#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeDrm_muxClearWithMeta(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
    output: JString,
    meta: JString,
    cover: jni::objects::JByteArray,
    cover_mime: JString,
) -> jint {
    let input = match jstring(&mut env, &input) { Ok(v) => v, Err(e) => return e };
    let output = match jstring(&mut env, &output) { Ok(v) => v, Err(e) => return e };
    let meta_json = match jstring(&mut env, &meta) { Ok(v) => v, Err(e) => return e };
    let mime = if cover_mime.is_null() { String::new() } else {
        match jstring(&mut env, &cover_mime) { Ok(v) => v, Err(e) => return e }
    };

    let mut cover_bytes: Option<Vec<u8>> = None;
    if !cover.is_null() {
        if let Ok(len) = env.get_array_length(&cover) {
            if len > 0 {
                let mut buf = vec![0u8; len as usize];
                if env.get_byte_array_region(&cover, 0, unsafe {
                    std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut i8, buf.len())
                }).is_ok() {
                    cover_bytes = Some(buf);
                }
            }
        }
    }

    let trimmed = meta_json.trim();
    let md = if trimmed.is_empty() || trimmed == "{}" {
        None
    } else {
        Some(meta_from_json(trimmed, cover_bytes, if mime.is_empty() { None } else { Some(mime) }))
    };
    match mux_clear_with_meta(Path::new(&input), Path::new(&output), md) {
        Ok(()) => 0,
        Err(message) => error(&mut env, &message),
    }
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_env: JNIEnv, _class: JClass) -> jint {
    0x0001_0006
}

#[cfg(test)]
mod hardening_tests {
    use super::*;

    #[test]
    fn a_corrupt_box_size_fails_without_a_huge_allocation() {
        // Header claims ~4 GiB, only 100 bytes follow.
        let mut data = vec![0xFF, 0xFF, 0xFF, 0xF0, b'm', b'd', b'a', b't'];
        data.extend(std::iter::repeat_n(7u8, 100));
        assert!(read_box(&mut std::io::Cursor::new(&data)).is_err());
        // Within the limit but truncated.
        let mut short = 5000u32.to_be_bytes().to_vec();
        short.extend_from_slice(b"mdat");
        short.extend(std::iter::repeat_n(1u8, 300));
        let err = read_box(&mut std::io::Cursor::new(&short)).unwrap_err();
        assert!(err.contains("truncated"), "{err}");
    }

    #[test]
    fn a_whole_box_is_read_exactly() {
        let mut data = 16u32.to_be_bytes().to_vec();
        data.extend_from_slice(b"free");
        data.extend_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
        let b = read_box(&mut std::io::Cursor::new(&data)).unwrap().unwrap();
        assert_eq!(b, data);
        assert!(read_box(&mut std::io::Cursor::new(Vec::<u8>::new())).unwrap().is_none());
    }

    #[test]
    fn a_64bit_size_near_the_limit_does_not_overflow() {
        let mut data = 16u32.to_be_bytes().to_vec();
        data.extend_from_slice(b"ftyp");
        data.extend_from_slice(&[0u8; 8]);
        data.extend_from_slice(&1u32.to_be_bytes());
        data.extend_from_slice(b"mdat");
        data.extend_from_slice(&(u64::MAX - 4).to_be_bytes());
        data.extend_from_slice(&[0u8; 32]);
        let found = boxes(&data);
        assert_eq!(found.len(), 1, "only the valid ftyp box");
    }
}
