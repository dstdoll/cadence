use std::path::Path;

// Builds libcadence-native.so: 100% Rust now (lib.rs + engine/core/bridges/cppfn/support/sys),
// linked against the closed Apple blobs. No C/C++ is compiled — the former C core, C bridges and C++
// support are all ported to Rust (see native/RUST_MIGRATION.md). We still link the Apple dylibs and
// libc++ (their runtime), and force a few sibling libs into NEEDED (see below).
fn main() {
    // ~/.cargo/config.toml may set `-C target-cpu=native`, which bakes the build machine's CPU into the
    // phone library. build-android.sh sets RUSTFLAGS explicitly (with target-cpu=generic); refuse the rest.
    if std::env::var("CARGO_ENCODED_RUSTFLAGS").map_or(false, |f| f.contains("target-cpu=native")) {
        panic!("target-cpu=native in RUSTFLAGS: build with native/build-android.sh");
    }
    let manifest = std::env::var("CARGO_MANIFEST_DIR").unwrap();
    let abi = Path::new(&manifest)
        .join("../../backend/src/main/jniLibs/arm64-v8a")
        .canonicalize()
        .expect("jniLibs/arm64-v8a must exist");

    println!("cargo:rustc-link-search=native={}", abi.display());
    for lib in ["storeservicescore", "androidappmusic", "c++_shared"] {
        println!("cargo:rustc-link-lib=dylib={lib}");
    }
    for lib in ["log", "dl"] {
        println!("cargo:rustc-link-lib=dylib={lib}");
    }
    // These carry no symbol our objects reference directly, so the linker's default --as-needed
    // drops them from NEEDED. One of their load-time constructors primes bionic's per-net resolver
    // controller; without them, configure()'s _resolv_set_nameservers_for_net walks an uninitialised
    // controller list and SIGSEGVs. Force them into NEEDED with a self-contained --no-as-needed group
    // (the leading --push-state/trailing --pop-state keep the setting from leaking to rustc's own
    // link inputs).
    println!("cargo:rustc-link-arg=-Wl,--push-state,--no-as-needed");
    println!("cargo:rustc-link-arg=-lmediaplatform");
    println!("cargo:rustc-link-arg=-landroid");
    println!("cargo:rustc-link-arg=-lm");
    println!("cargo:rustc-link-arg=-Wl,--pop-state");
    // The Apple blobs resolve some symbols only at load time from their sibling libs.
    println!("cargo:rustc-link-arg=-Wl,--allow-shlib-undefined");
    println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
    // NativeDrm dlopen()s this lib and dlsym()s core_wrapper_decrypt_sample, so it must stay in the
    // dynamic symbol table alongside the JNI entry points. rustc's cdylib version script localizes
    // non-Rust symbols; an extra version script re-globalizes it (an explicit name beats `local: *`).
    println!("cargo:rustc-link-arg=-Wl,--version-script={}", Path::new(&manifest).join(if std::env::var("CARGO_FEATURE_SELFTEST").is_ok() { "export_selftest.map" } else { "export.map" }).display());
    // The crate builds libcadence_native.so but the app loads it as libcadence-native.so (renamed
    // when staged); pin the SONAME to the loaded name.
    println!("cargo:rustc-link-arg=-Wl,-soname,libcadence-native.so");

    println!("cargo:rerun-if-changed=build.rs");
}
