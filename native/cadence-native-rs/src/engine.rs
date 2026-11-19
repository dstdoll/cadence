//! Rust orchestration for the native StoreServices engine. It owns the lifecycle that used to live
//! in native_core.c's `core_wrapper_*`: the ready/lease state, the device identity, adam-id
//! parsing and the call sequencing. The irreducible C++ ABI glue that touches the Apple blobs stays
//! in C and is reached one call at a time through the flat-C `core_fh_*` bridges (see native_core.c).

use crate::core;
use crate::identity;
use std::ffi::{c_ulong, c_void};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

static READY: AtomicBool = AtomicBool::new(false);
static LEASE: AtomicBool = AtomicBool::new(false);
// Serialises the init and lease sequences; the C glue they drive mutates process-wide globals.
static LIFECYCLE: Mutex<()> = Mutex::new(());

/// Configure FootHill, build the request context and resolve the session instance. The device
/// identity is this device's own Android ID; the Apple libraries provision the machine for it
/// themselves (see `identity`). Idempotent: returns the cached readiness once set.
pub fn init(base_dir: &str, device_id: &str, dns_servers: &str) -> bool {
    let _guard = LIFECYCLE.lock().unwrap_or_else(|e| e.into_inner());
    if READY.load(Ordering::Acquire) {
        return true;
    }
    let android_id = identity::resolve(base_dir, device_id, &core::nlog);
    core::configure(base_dir, dns_servers, &android_id);
    core::init_ctx();
    core::nlog(&format!("identity: {}", identity::adi_state(base_dir)));
    let ready = !core::instance().is_null();
    READY.store(ready, Ordering::Release);
    ready
}

/// Resolve the HLS playlist URL for a track, ensuring a playback lease first.
pub fn m3u8(adam_id: &str) -> Option<String> {
    if !READY.load(Ordering::Acquire) {
        return None;
    }
    ensure_lease();
    core::m3u8(parse_adam(adam_id))
}

/// Request a progressive `.m4p` asset and its `SVSampleDecryptor` context.
pub fn m4p_asset(adam_id: &str) -> Option<(String, *mut c_void)> {
    if !READY.load(Ordering::Acquire) {
        return None;
    }
    core::m4p_asset(parse_adam(adam_id))
}

/// Release a boxed `SVDecryptor` context returned by [`m4p_asset`].
pub unsafe fn release_m4p_context(ctx: *mut c_void) {
    core::release_m4p_context(ctx);
}

/// Fetch the FairPlay decrypt context for a track + key URI (0 on failure or not-ready).
pub fn kd(adam_id: &str, key_uri: &str) -> *mut c_void {
    if !READY.load(Ordering::Acquire) {
        return std::ptr::null_mut();
    }
    core::kd(adam_id, key_uri)
}

/// Set the Apple ID sign-in credentials the StoreServices credential handler will use. Call before
/// [login] on a fresh device (no account yet in `mpl_db`).
pub fn set_credentials(user: &str, pass: &str) {
    core::set_credentials(user, pass);
}

/// Supply the HSA2 verification code once the trusted-device/SMS prompt arrives; unblocks [login].
pub fn submit_code(code: &str) {
    core::submit_code(code);
}

/// True while sign-in is blocked waiting for a 2FA code (so the UI can show the code entry).
pub fn awaiting_code() -> bool {
    core::awaiting_code()
}

/// Run the real StoreServices account sign-in (AuthenticateFlow), which drives the dialog/credential
/// handlers with the credentials from [set_credentials]. **Blocks** until the exchange finishes —
/// including waiting for a 2FA code submitted via [submit_code] — so it must run off the UI thread.
/// Needs [init] first. Returns true only on a confirmed sign-in (responseType 6).
pub fn login() -> bool {
    if !READY.load(Ordering::Acquire) {
        return false;
    }
    core::login()
}

/// Mint the account's media-user-token via Apple's createMusicToken (run after a successful [login]).
/// Returns None when the engine isn't ready or Apple didn't return a token.
pub fn mint_music_token() -> Option<String> {
    if !READY.load(Ordering::Acquire) {
        return None;
    }
    core::mint_music_token()
}

fn ensure_lease() {
    if LEASE.load(Ordering::Acquire) {
        return;
    }
    let _guard = LIFECYCLE.lock().unwrap_or_else(|e| e.into_inner());
    if LEASE.load(Ordering::Acquire) {
        return;
    }
    core::ensure_lease();
    LEASE.store(true, Ordering::Release);
}

/// strtoul-style: consume leading ASCII digits, stop at the first non-digit (adam ids are numeric).
fn parse_adam(s: &str) -> c_ulong {
    let mut v: u64 = 0;
    for b in s.trim_start().bytes() {
        if !b.is_ascii_digit() {
            break;
        }
        v = v.wrapping_mul(10).wrapping_add((b - b'0') as u64);
    }
    v as c_ulong
}
