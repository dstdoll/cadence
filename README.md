# Cadence

An Apple Music client for Android with a Material 3 Expressive interface. Sign in with your Apple ID and
browse, search, stream and download your music in lossless ALAC or Dolby Atmos, with synced lyrics (plus
optional AMLL fallback), a queue, a local-first library with JSON backup, ALAC escape-frame integrity
checking and repair, standalone cover/motion artwork saving, and an audio processing chain (equalizer,
tone, replay gain, dither). The interface is available in English, Spanish, and Simplified Chinese.

- **UI:** Kotlin and Jetpack Compose (`app/`).
- **Data and engine:** catalogue, search, sign-in and downloads (`backend/`), on top of a native engine
  written in Rust (`native/`) that drives Apple's own libraries for sign-in and playback.
- **Platform:** Android 10 or newer (API 29+), arm64 devices only.

Prebuilt APKs are published under [Releases](https://github.com/dstdoll/cadence/releases).

## Build

Requirements: JDK 21 and the Android SDK (platform 37). Point `ANDROID_HOME` at the SDK, or put its path in
`local.properties` (`sdk.dir=...`).

```sh
git clone https://github.com/dstdoll/cadence.git cadence
cd cadence
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Nothing else is needed: no account data, keys or config files. On first launch the app reads the
developer token it needs from Apple Music's web player (a network connection is required once), and you
sign in with your Apple ID and the verification code.

## Other tasks

| Task | Command |
| --- | --- |
| Release APK (minified) | `./gradlew :app:assembleRelease` |
| Gradle unit tests | `./gradlew testDebugUnitTest` |
| Rust crate tests | `(cd native/cadence-alac && cargo test)` |

Release builds use the debug key unless a `signing.properties` file (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) exists at `~/.config/cadence/` or at the path in `CADENCE_SIGNING`.

## Native libraries

The Rust libraries are committed prebuilt in `backend/src/main/jniLibs/arm64-v8a`, so a normal build does
not need Rust. To rebuild them, install the `aarch64-linux-android` Rust target and the Android NDK, then
run `native/build-android.sh`.

## Layout

| Path | Contents |
| --- | --- |
| `app/` | Screens, navigation, theme, playback service and the audio processing chain |
| `backend/` | Apple Music catalogue, search, sign-in, downloads and the native bridge |
| `native/` | Rust crates: Apple library bridge, DRM engine, ALAC/EAC-3 decoders, auth and container handling |

## Special thanks

- [@Zhaarey](https://github.com/Zhaarey)
- [@WorldObservationLog](https://github.com/WorldObservationLog)
- [@Itouakirai](https://github.com/Itouakirai)
- [@br0kendolllx-debug](https://github.com/br0kendolllx-debug)
