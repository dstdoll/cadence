//! What an error from Apple's libraries means for us, in one place (pure functions, no JNI, so the
//! table is unit-tested on the host: `rustc --edition 2021 --test src/apple_errors.rs`).
//!
//! `cxx_guard` calls [`classify`] for every caught C++ exception. The result is written into the
//! `[CXX-THROW]` log line (`class=… impact=… hint=…`) and decides whether the user gets a notice:
//! the Kotlin side maps [`Kind`] to a localized message and shows a toast when [`Impact`] says the
//! user felt it. The catalogue of what to expect, and where each name comes from, is in
//! `native/APPLE_ERRORS.md`; keep the two in step.
//!
//! The throw sites are known (every Apple call goes through `apple_externs!` and runs inside a named
//! entry point), so the entry point decides the class. The exception *type* and message refine the
//! hint only: the libraries throw a handful of types (see `KNOWN_TYPES`), and which one a given
//! failure produces is exactly what the first captured `[CXX-THROW]` lines will tell us.

/// What went wrong, in terms of the app (not of the C++ type).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    /// FairPlay/StoreServices refused to give a decrypt context for a track (`kd`).
    KeyRejected,
    /// The playback lease or the asset request failed (`m3u8`, `m4p_asset`).
    LeaseOrAsset,
    /// A sample could not be decrypted (`decrypt_sample`, `decrypt_m4p_sample`).
    Decrypt,
    /// Account sign-in inside StoreServices (`login`, the dialog/credential callbacks).
    SignIn,
    /// Minting the media user token (`mint_music_token`).
    TokenMint,
    /// Engine start-up (`init`): nothing will play until it succeeds.
    Init,
    /// A native self-test entry (never reaches users).
    SelfTest,
    /// An entry point this table does not know yet.
    Unknown,
}

/// How far the failure reaches; decides whether the user is told.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum Impact {
    /// Nothing the user can see.
    None,
    /// One track is skipped or does not start.
    Track,
    /// The account session is affected (sign-in, token).
    Session,
    /// The playback engine as a whole is unusable.
    App,
}

pub struct Class {
    #[allow(dead_code)]
    pub kind: Kind,
    pub impact: Impact,
    /// Stable, greppable name (`KEY_REJECTED`); also the key the Kotlin side maps to a message.
    pub code: &'static str,
    /// One sentence for the developer: the likely cause and where to look next.
    pub hint: &'static str,
}

/// Exception types the Apple libraries are known to define or throw (from their typeinfo symbols,
/// listed with `llvm-nm -D --defined-only`; see APPLE_ERRORS.md). Matched against the demangled type.
pub const KNOWN_TYPES: &[(&str, &str)] = &[
    ("ACException", "Apple Music Android core exception (libandroidappmusic); what() is the reason"),
    ("SVError", "streaming/playback error object (libandroidappmusic); category says which subsystem"),
    ("mlcore::MediaError", "media-library error (libmedialibrarycore)"),
    ("storeservicescore::StoreErrorCondition", "StoreServices error: has an error code and an external error code"),
    ("mediaplatform::ErrorCondition", "media-platform error condition (networking, parsing)"),
    ("std::__ndk1::system_error", "OS or networking failure raised through std::system_error"),
    ("std::bad_alloc", "out of memory"),
    ("std::runtime_error", "plain runtime failure; what() is the reason"),
    ("std::logic_error", "logic error raised by the library (a precondition did not hold)"),
];

/// Classifies a caught exception. `entry` is the Scope label, `apple_call` the Rust-side name of the
/// call that threw, `ty` the demangled type, `what` its message (may be empty).
pub fn classify(entry: &str, apple_call: &str, ty: &str, what: &str) -> Class {
    let (kind, impact, code, base) = match entry {
        "kd" => (
            Kind::KeyRejected,
            Impact::Track,
            "KEY_REJECTED",
            "Apple's FairPlay key service threw while preparing this track's key (not available in the account's storefront, an expired lease, or broken FairPlay state). The track is skipped and not retried for a minute. Look at the FairPlay* lines the Apple libs write to logcat just before this",
        ),
        "m3u8" | "m4p_asset" => (
            Kind::LeaseOrAsset,
            Impact::Track,
            "LEASE_OR_ASSET",
            "the playback lease or asset request threw (no active subscription lease, or the asset is not offered for this account). The track does not start",
        ),
        "decrypt_sample" | "decrypt_m4p_sample" => (
            Kind::Decrypt,
            Impact::Track,
            "DECRYPT",
            "decrypting a sample threw (stale decrypt context after the key was invalidated). The caller renews the key once, then the track is skipped",
        ),
        "login" | "dialog_handler" | "credential_handler" => (
            Kind::SignIn,
            Impact::Session,
            "SIGN_IN",
            "the StoreServices sign-in flow threw (wrong password, 2FA state, or a changed prompt). Sign-in did not complete",
        ),
        "mint_music_token" => (
            Kind::TokenMint,
            Impact::Session,
            "TOKEN_MINT",
            "minting the media user token threw (network error, or Apple rejected the session). Playback of library content may fail until signing in again",
        ),
        "init" | "release_m4p_context" => (
            Kind::Init,
            Impact::App,
            "ENGINE_INIT",
            "starting the native engine threw (FairPlay/identity files missing or unreadable under files/mpl_db). Nothing will play until init succeeds",
        ),
        e if e.starts_with("selftest") => (Kind::SelfTest, Impact::None, "SELFTEST", "native self-test"),
        _ => (
            Kind::Unknown,
            Impact::None,
            "UNKNOWN",
            "an entry point that apple_errors.rs does not know threw: add it to the table in apple_errors.rs and to APPLE_ERRORS.md",
        ),
    };
    let _ = apple_call;
    // Out of memory is app-wide whatever was running.
    let (impact, code, hint) = if ty.contains("bad_alloc") {
        (Impact::App, "OUT_OF_MEMORY", "the Apple library ran out of memory: check process memory (dumpsys meminfo) and what else was loaded")
    } else {
        (impact, code, base)
    };
    // A short type-specific suffix so the first captured exception types teach us something.
    let known = KNOWN_TYPES.iter().find(|(name, _)| ty.contains(name)).map(|(_, why)| *why);
    let _ = (what, known);
    Class { kind, impact, code, hint }
}

/// The hint, extended with what is known about the thrown type (for the log line).
pub fn describe(class: &Class, ty: &str) -> String {
    match KNOWN_TYPES.iter().find(|(name, _)| ty.contains(name)) {
        Some((_, why)) => format!("{}; type note: {}", class.hint, why),
        None => format!("{}; type not in KNOWN_TYPES yet: add it with what this failure means", class.hint),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_entry_point_decides_the_class() {
        let c = classify("kd", "get_persistent_key", "std::runtime_error", "");
        assert_eq!((c.kind, c.impact, c.code), (Kind::KeyRejected, Impact::Track, "KEY_REJECTED"));
        let c = classify("m3u8", "x", "ACException", "");
        assert_eq!((c.kind, c.impact), (Kind::LeaseOrAsset, Impact::Track));
        let c = classify("login", "x", "int", "");
        assert_eq!((c.kind, c.impact), (Kind::SignIn, Impact::Session));
        let c = classify("mint_music_token", "x", "int", "");
        assert_eq!((c.kind, c.impact), (Kind::TokenMint, Impact::Session));
        let c = classify("init", "x", "int", "");
        assert_eq!((c.kind, c.impact), (Kind::Init, Impact::App));
        let c = classify("decrypt_sample", "x", "int", "");
        assert_eq!((c.kind, c.impact), (Kind::Decrypt, Impact::Track));
    }

    #[test]
    fn callbacks_on_apple_threads_count_as_sign_in() {
        assert_eq!(classify("dialog_handler", "ap_handle_dialog_response", "", "").kind, Kind::SignIn);
        assert_eq!(classify("credential_handler", "ap_handle_credentials_response", "", "").kind, Kind::SignIn);
    }

    #[test]
    fn out_of_memory_is_app_wide_whatever_was_running() {
        let c = classify("kd", "get_persistent_key", "std::bad_alloc", "std::bad_alloc");
        assert_eq!((c.code, c.impact), ("OUT_OF_MEMORY", Impact::App));
    }

    #[test]
    fn self_tests_never_reach_users() {
        let c = classify("selftest.throw", "cpp_throw", "std::runtime_error", "boom");
        assert_eq!((c.kind, c.impact), (Kind::SelfTest, Impact::None));
    }

    #[test]
    fn an_unknown_entry_point_is_flagged_for_the_table() {
        let c = classify("brand_new_entry", "x", "int", "");
        assert_eq!(c.kind, Kind::Unknown);
        assert!(c.hint.contains("add it to the table"));
    }

    #[test]
    fn impacts_are_ordered_by_reach() {
        assert!(Impact::None < Impact::Track && Impact::Track < Impact::Session && Impact::Session < Impact::App);
    }

    #[test]
    fn known_and_unknown_types_get_different_notes() {
        let c = classify("kd", "x", "ACException", "");
        assert!(describe(&c, "ACException").contains("type note: Apple Music Android core exception"));
        assert!(describe(&c, "SomethingNew").contains("not in KNOWN_TYPES yet"));
    }
}
