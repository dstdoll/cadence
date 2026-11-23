## Summary
<!-- Concise description of what changed and why. Link the issue below. -->
Closes #

## Root Cause / Design
<!-- For bug fixes: what caused the issue and how this fixes it. For features: key design choices and affected modules (`:app`, `:backend`, `native/`). -->

## Verification
- [ ] `./gradlew :backend:testDebugUnitTest :app:testDebugUnitTest`
- [ ] `cargo test --manifest-path native/cadence-eac3/Cargo.toml` (if `native/` changed)
- [ ] `./native/test-ports.sh` (if native ports changed)
- [ ] Rebuilt `jniLibs/arm64-v8a/` via `./native/build-android.sh` (if `cadence-native-rs` or `cadence-android-drm` changed)
- [ ] Tested on device / checked `Cadence-logs-*.txt` (note device model & Android version if applicable)

## Checklist
- [ ] `:app` talks to `:backend` only via `app.cadence.backend.api`
- [ ] All code, comments, tests, and identifiers are in English (UI translations only in `res/values*`)
- [ ] No device IDs, keystores, tokens, `vectors.json`, or seeded credentials included
