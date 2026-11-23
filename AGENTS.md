# AGENTS.md

Guide for AI coding agents working in **Cadence** (an Apple Music client for Android: Jetpack Compose UI, Kotlin backend, and Rust/JNI native engine).

## 1. Repository Layout & Boundaries

| Path | Responsibility | Key Rules |
|---|---|---|
| `app/` | Compose UI (`feature/`), playback (`playback/`), state (`state/`), design system (`core/`) | Talk to `backend/` **only** through `app.cadence.backend.api` (`Backend`, `AuthService`, `MusicRepository`, `SearchService`, `RecommendationService`, `AlacCodec`, `Eac3Codec`). |
| `backend/` | Apple Music API, catalog/search, downloads, HLS/DRM orchestration, JNI (`NativeWrapper`) | Owns `jniLibs/arm64-v8a/`. Keep `consumer-rules.pro` in sync with native JNI callbacks. |
| `native/` | Rust crates (`cadence-native-rs`, `cadence-android-drm`, `cadence-alac`, `cadence-eac3`, `cadence-container`, `cadence-hooks`, and pure-Rust ports `apple-ss-sys`, `device-guid`, `fairplay-proto`, `store-config`) | Prebuilt `.so`s are committed in `backend/src/main/jniLibs/arm64-v8a/`; normal Gradle builds do not need Rust. |
| `.github/workflows/` | `ci.yml` (JVM + `cadence-eac3` tests) and `release.yml` | `ci.yml` ignores `**/*.md` and `.gitignore`. |
| `tools/`, `docs/`, `graphify-out/` | Gitignored local developer scripts, notes, and optional knowledge graph | Never force-add untracked files here unless explicitly requested. |

## 2. Build & Test Commands

Requirements: JDK 21, Android SDK (compileSdk/targetSdk 37, minSdk 29). Set `ANDROID_HOME` or `local.properties` (`sdk.dir=...`).

```sh
# Debug / Release APKs (release enables R8 + resource shrinking)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# JVM unit tests (matches CI)
./gradlew :backend:testDebugUnitTest :app:testDebugUnitTest --build-cache --no-daemon

# Rust E-AC-3 decoder tests (matches CI; requires `ffmpeg` on PATH)
cargo test --manifest-path native/cadence-eac3/Cargo.toml

# Native port crates + adversarial vector checks (uses native/vectors.sample.json)
./native/test-ports.sh

# Rebuild Android arm64-v8a .so libs (only needed after editing Rust in cadence-native-rs or cadence-android-drm)
# Requires Rust `aarch64-linux-android` target + Android NDK
./native/build-android.sh
```

## 3. Critical Guardrails

- **No secrets or device identifiers in git:** Never commit `backend/src/main/assets/{native-data,engine,session.json}`, `native/vectors.json`, `*.jks`, `*.keystore`, `keystore.properties`, `ANDROID_ID`, FairPlay GUIDs, SPCs, or auth tokens. Release builds enforce `checkNoSeededCredentials`. Use `native/vectors.sample.json` for synthetic port tests.
- **Native ABI & packaging:** `arm64-v8a` only. Keep `useLegacyPackaging = compressLibs` (`false` by default in `app/build.gradle.kts`) and keep `libc++_shared.so` in `libcadence-native.so`'s `NEEDED` entries (`build-android.sh` checks this).
- **C++ exception guard:** StoreServices calls from `cadence-native-rs` must go through `cxx_guard` so Apple C++ throws do not unwind across Rust/JNI frames. When adding FFI symbols, follow `native/APPLE_ERRORS.md` and regenerate assembly via `native/regen-cxx-trampoline.sh` (verify on-device with `native/selftest-cxx-guard.sh`).
- **Language:** All code, identifiers, comments, error messages, tests, and commit messages must be in English. User-facing localization lives in `res/values/` and `res/values-es/`.

## 4. Usage & Debugging Workflows

### Exporting & Reading App Diagnostics
1. In the app: **Settings → Export log** (or broadcast `app.cadence.DEBUG_EXPORT_LOGS` in debug builds). Writes `Download/Cadence/Cadence-logs-<timestamp>.txt` containing:
   - Diagnostic summary (`storefront`, `subscription`, `provisioning` file ages/missing flags, `native error classes`).
   - `files/logs/cadence.log` (Kotlin + `[NATIVE-INFO]` / `[CXX-THROW]` / `A/` Apple logcat lines).
   - `native-crash.log` (fatal signal handler output from previous runs).
2. Read `native/APPLE_ERRORS.md` before interpreting `[CXX-THROW]`, `[CXX-SKIP]`, or `[CXX-TERMINATE]` lines.

### Headless Debug Broadcasts (`DebugReceiver`, debug builds only)
Invoke via `adb shell am broadcast -n app.cadence/.DebugReceiver -a <ACTION> ...`:
- **Playback / Player:** `app.cadence.DEBUG_PLAY`, `app.cadence.DEBUG_PLAYER` (e.g. `--es cmd next|previous|pause|play|seek|status [--el ms 30000]`), `app.cadence.DEBUG_PALETTE --es trackId <id>`.
- **Downloads / DRM:** `app.cadence.DEBUG_DOWNLOAD --es trackId <id>` (supports `--es codec aac|alac`, `--es atmos off|spatial|atmos`, `--es mode stream|twophase`, `--es shareCache true|false`, `--es size 1400x1400`), `app.cadence.DEBUG_DECRYPT`.
- **Search / Library:** `app.cadence.DEBUG_SEARCH --es q "<query>"`, `app.cadence.DEBUG_LIBRARY`.
- **Auth / DNS / Identity:** `app.cadence.DEBUG_DNS --es cmd probe [--es host init.itunes.apple.com]`, `app.cadence.DEBUG_AUTH`, `app.cadence.DEBUG_LOGIN`, `app.cadence.DEBUG_LOGIN_CODE`, `app.cadence.DEBUG_SESSION --es cmd export`, `app.cadence.DEBUG_IDENTITY --es cmd rotate`.

### Common Failure Modes
- **`KEY_REJECTED` (`Invalid CKC error`) with `fsi.pdat=missing` / `IC-Info.sids=missing` / `STOREFRONT_ID=missing`:** The account has an active subscription, and preshare `e1` may succeed, but the device identity was never provisioned (common with seeded sessions). Fix: sign out and sign in on the device so StoreServices completes provisioning.
- **`KEY_REJECTED` with `storefront` mismatch:** Account storefront in `session` disagrees with the requested track/catalog storefront; check `storefront=` in the log summary.
- **`UnknownHostException` on `is1-ssl.mzstatic.com` or `init.itunes.apple.com`:** DNS/Private DNS/ad-blocker interference or stale engine DNS state after network switches. Test with `DEBUG_DNS --es cmd probe`.
- **`UnsatisfiedLinkError` / missing `libcadence-native.so` symbols:** Check `adb logcat` for `dlopen failed` and verify `build-android.sh` passed `check_cxx_runtime`.
