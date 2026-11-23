# native/AGENTS.md

Instructions for working in `native/` (Rust crates, JNI bridges, and StoreServices ports). See root [AGENTS.md](../AGENTS.md) for overall project rules.

## Crate Map
- **Android JNI `.so` crates** (built into `backend/src/main/jniLibs/arm64-v8a/` via `./native/build-android.sh`):
  - `cadence-native-rs/` -> `libcadence-native.so` (`core.rs`, `sys.rs`, `engine.rs`, `bridges.rs`, `dns.rs`, `identity.rs`, `cxx_guard.rs`, `cxx_trampoline.cpp`/`.s`, `apple_errors.rs`, `events.rs`, `logfmt.rs`)
  - `cadence-android-drm/` -> `libcadence_android_drm.so`
- **Media & hook crates** (used by `cadence-native-rs` / `cadence-android-drm`):
  - `cadence-alac/`, `cadence-eac3/`, `cadence-container/`, `cadence-hooks/`
- **Pure-Rust StoreServices / FairPlay ports** (unit + vector tested):
  - `apple-ss-sys/` (`src/abi.rs` is the single source of truth for libc++ layout + symbol catalog)
  - `device-guid/`, `fairplay-proto/`, `store-config/`, `vectors-fixture/`, `vectors-sample/`

## Rules
1. **C++ Exception Boundary:** Every call into Apple's closed `.so`s from `cadence-native-rs` must go through `cxx_guard` (`cxx_guard.rs`). If you add or change FFI symbols, keep `apple_errors.rs` and `native/APPLE_ERRORS.md` in sync, and regenerate assembly with `./native/regen-cxx-trampoline.sh` (test on-device with `./native/selftest-cxx-guard.sh`).
2. **ABI & Runtime Checks:** `./native/build-android.sh` compiles with `--release --locked --target aarch64-linux-android`, strips symbols, sets `max-page-size=16384`, and runs `check_cxx_runtime` to ensure `libc++_shared.so` stays in `NEEDED`.
3. **No Device Captures in Git:** Never commit `native/vectors.json`. Use `native/vectors.sample.json` for synthetic port validation.

## Verification Commands
```sh
./native/test-ports.sh
cargo test --manifest-path native/cadence-eac3/Cargo.toml
./native/build-android.sh   # only when changing cadence-native-rs or cadence-android-drm
```
