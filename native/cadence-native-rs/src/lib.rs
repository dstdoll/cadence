//! Rust JNI for the native StoreServices wrapper (libcadence-native.so). It is the thin layer the
//! `NativeWrapper` Kotlin object binds to: it marshals the JNI arguments and hands off to the Rust
//! orchestration in `engine`/`core`, which drives the closed Apple StoreServices libraries directly
//! over their C++ ABI (see `sys`/`bridges`). The library is now 100% Rust — JNI_OnLoad + the crash
//! handler live in `support`, and the lease std::function callbacks in `cppfn`; no C/C++ is compiled.

mod bridges;
mod core;
mod cppfn;
mod engine;
mod identity;
mod support;
mod sys;

use jni::objects::{JByteArray, JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::ffi::{c_int, c_void};

/// FairPlay per-sample decrypt — ported from native_core.c's `core_wrapper_decrypt_sample` (Rust
/// migration Phase 2). Exported (`#[no_mangle]`, kept by export.map) for both the `decryptSample`
/// JNI below and `NativeDrm`'s dlsym. `kd` is the `SVFootHillPContext*`; its first word is the 32-bit
/// context id the per-sample server call takes. Decrypts in place; returns 0 once it ran (the C
/// version ignored the server's rc and let the decoded audio be the judge).
#[no_mangle]
pub unsafe extern "C" fn core_wrapper_decrypt_sample(kd: *mut c_void, sample: *mut u8, size: usize) -> c_int {
    if kd.is_null() || sample.is_null() {
        return -1;
    }
    let state = *(kd as *const *mut c_void);
    if state.is_null() {
        return -2;
    }
    let _rc = sys::nfc_decrypt_sample(state, 5, sample as *mut c_void, sample as *mut c_void, size);
    0
}

#[no_mangle]
pub unsafe extern "C" fn core_wrapper_decrypt_m4p_sample(
    ctx: *mut c_void,
    sample: *mut u8,
    size: usize,
) -> c_int {
    core::decrypt_m4p_sample(ctx, sample, size)
}

/// Java string -> owned Rust String (empty on error), matching the old `from_jstring`.
fn to_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value).map(Into::into).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_init(
    mut env: JNIEnv,
    _class: JClass,
    base_dir: JString,
    device_id: JString,
    dns_servers: JString,
) -> jboolean {
    let base = to_string(&mut env, &base_dir);
    let device = to_string(&mut env, &device_id);
    let dns = to_string(&mut env, &dns_servers);
    if engine::init(&base, &device, &dns) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_setCredentials(
    mut env: JNIEnv,
    _class: JClass,
    user: JString,
    pass: JString,
) {
    let u = to_string(&mut env, &user);
    let p = to_string(&mut env, &pass);
    engine::set_credentials(&u, &p);
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_submitCode(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
) {
    let c = to_string(&mut env, &code);
    engine::submit_code(&c);
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_awaitingCode(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine::awaiting_code() { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_login(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine::login() { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_mintMusicToken(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match engine::mint_music_token() {
        Some(token) => match env.new_string(token) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_m3u8(
    mut env: JNIEnv,
    _class: JClass,
    adam_id: JString,
) -> jstring {
    let adam = to_string(&mut env, &adam_id);
    match engine::m3u8(&adam) {
        Some(url) => match env.new_string(url) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_kd(
    mut env: JNIEnv,
    _class: JClass,
    adam_id: JString,
    key_uri: JString,
) -> jlong {
    let adam = to_string(&mut env, &adam_id);
    let uri = to_string(&mut env, &key_uri);
    engine::kd(&adam, &uri) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_decryptSample(
    env: JNIEnv,
    _class: JClass,
    kd: jlong,
    sample: JByteArray,
) -> jbyteArray {
    if kd == 0 || sample.is_null() {
        return std::ptr::null_mut();
    }
    let mut bytes = match env.convert_byte_array(&sample) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let rc = unsafe { core_wrapper_decrypt_sample(kd as *mut c_void, bytes.as_mut_ptr(), bytes.len()) };
    if rc != 0 {
        return std::ptr::null_mut();
    }
    match env.byte_array_from_slice(&bytes) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_m4pAssetNative(
    mut env: JNIEnv,
    _class: JClass,
    adam_id: JString,
) -> jstring {
    let adam = to_string(&mut env, &adam_id);
    match engine::m4p_asset(&adam) {
        Some((url, ctx)) => {
            let encoded = format!("{}\n{}", ctx as isize, url);
            match env.new_string(encoded) {
                Ok(j) => j.into_raw(),
                Err(_) => {
                    unsafe { engine::release_m4p_context(ctx) };
                    std::ptr::null_mut()
                }
            }
        }
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeWrapper_releaseM4pContext(
    _env: JNIEnv,
    _class: JClass,
    ctx: jlong,
) {
    if ctx != 0 {
        unsafe { engine::release_m4p_context(ctx as *mut c_void) };
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeAlac_create(
    env: JNIEnv,
    _class: JClass,
    cookie: JByteArray,
) -> jlong {
    let bytes = match env.convert_byte_array(&cookie) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    match cadence_alac::AlacSession::from_cookie(&bytes) {
        Ok(session) => Box::into_raw(Box::new(session)) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeAlac_maxOutputBytes(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    float_out: jboolean,
) -> jint {
    if handle == 0 {
        return -1;
    }
    let session = unsafe { &*(handle as *const cadence_alac::AlacSession) };
    session.max_output_bytes(float_out == JNI_TRUE) as jint
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeAlac_decode(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    packet: JByteBuffer,
    in_offset: jint,
    in_len: jint,
    out: JByteBuffer,
    float_out: jboolean,
) -> jint {
    if handle == 0 {
        return -1;
    }
    if in_offset < 0 || in_len < 0 {
        return -2;
    }
    let (Ok(in_ptr), Ok(in_cap), Ok(out_ptr), Ok(out_cap)) = (
        env.get_direct_buffer_address(&packet),
        env.get_direct_buffer_capacity(&packet),
        env.get_direct_buffer_address(&out),
        env.get_direct_buffer_capacity(&out),
    ) else {
        return -2;
    };
    let (off, len) = (in_offset as usize, in_len as usize);
    if off.saturating_add(len) > in_cap {
        return -2;
    }
    let pkt_slice = unsafe { std::slice::from_raw_parts(in_ptr.add(off), len) };
    let out_slice = unsafe { std::slice::from_raw_parts_mut(out_ptr, out_cap) };
    let session = unsafe { &mut *(handle as *mut cadence_alac::AlacSession) };
    let res = if float_out == JNI_TRUE {
        session.decode_to_f32(pkt_slice, out_slice)
    } else {
        session.decode_to_pcm16(pkt_slice, out_slice)
    };
    match res {
        Ok(written) => written as jint,
        Err(cadence_alac::AlacError::BufferTooSmall { .. }) => -2,
        Err(_) => -3,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_engine_media_NativeAlac_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut cadence_alac::AlacSession)) };
    }
}
