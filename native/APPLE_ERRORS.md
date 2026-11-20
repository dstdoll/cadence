# Native errors: what to expect, how to read them, what to do

The native layer (`libcadence-native.so`) runs Apple's closed libraries. When one of them throws a
C++ exception the app no longer dies: `cxx_guard` catches it, skips that call, the entry point reports
failure, and **one line** goes to `cadence.log` (the file *Settings > export log* shares), in order
with the Kotlin lines around it. This page lists those lines, the error classes, and what Apple's
libraries can throw. Code: `cadence-native-rs/src/{cxx_guard,events,apple_errors,support,logfmt}.rs`.
Keep `apple_errors.rs` (the class table, unit-tested) and this page in step.

## Log lines (grep the code in brackets)

| Line starts with | Meaning | Process |
|---|---|---|
| `[CXX-THROW] class=… impact=… entry=… apple_call=… threw <type>: "<what>" …` | an Apple call threw; caught, call skipped, the entry point returns failure | lives |
| `[CXX-SKIP] entry=… aborted: N later Apple call(s) were skipped after <call> threw` | the rest of that entry point was skipped (it cannot continue on half-built state) | lives |
| `[CXX-TERMINATE] uncaught C++ exception <type>: "<what>" on thread …` | a throw with no catch anywhere (typically on a thread Apple created); this line says what it was | aborts next |
| `[RUST-PANIC] "<message>" at file:line on thread …; entry=… apple_call=…` | a Rust panic. `panic in a function that cannot unwind` means a C++ exception or panic escaped an `extern "C"`/JNI frame: an Apple call there is not wrapped | aborts next |
| `[NATIVE-INFO] native layer loaded: …` | printed at start; proves the hooks are active in this build | lives |
| `previous run ended with === NATIVE CRASH SIGxxx … ===` | a fatal signal in the *last* run, copied from `native-crash.log` on the next start (`NativeCrashImport`) | n/a |

Fields: `entry` = the native entry point (`kd`, `m3u8`, `m4p_asset`, `login`, `mint_music_token`,
`init`, `decrypt_sample`, `decrypt_m4p_sample`, `dialog_handler`, `credential_handler`) with its detail
(`adam=… key=…`); `apple_call` = the Apple function that threw, demangled, with the Rust name in
brackets; `thread` = kernel thread name and tid. Every line is redacted like all other log lines.
The fatal-signal line (`=== NATIVE CRASH SIGSEGV (signal 11) fault=null tid=… entry=… apple_call=… ===`)
lives in `native-crash.log` because a signal handler cannot do more; it names the entry point and Apple
call the crashing thread was in. It never contains addresses, ids, or other data.

## Classes (`class=`), user notice, and what to do

| class | entry point | impact | the user sees | likely cause / what to do |
|---|---|---|---|---|
| `KEY_REJECTED` | `kd` | track | toast: couldn't unlock a track, skipped | FairPlay key service rejected the track: not offered in the account's storefront, expired lease, broken FairPlay state. Compare the storefront in the log (`storefront=us` vs `mx`). The key is not retried for 60 s (`FailedKeyMemory`). Look for the `FairPlay*` lines below in logcat just before |
| `LEASE_OR_ASSET` | `m3u8`, `m4p_asset` | track | toast: track didn't start | no playback lease, or the asset isn't offered for this account |
| `DECRYPT` | `decrypt_sample`, `decrypt_m4p_sample` | track | toast: couldn't be decrypted | stale decrypt context; the caller renews the key once |
| `SIGN_IN` | `login`, dialog/credential callbacks | session | toast: sign-in error, try again | wrong password, 2FA state, a changed prompt |
| `TOKEN_MINT` | `mint_music_token` | session | toast: sign in again | network error or rejected session |
| `ENGINE_INIT` | `init` | app | toast: restart the app | identity/FairPlay files missing or unreadable under `files/mpl_db` |
| `OUT_OF_MEMORY` | any (`std::bad_alloc`) | app | toast: low memory | check `dumpsys meminfo` |
| `PREVIOUS_CRASH` | startup | app | toast: closed unexpectedly last time | read the imported `previous run ended with` lines |
| `UNKNOWN` | any other entry | none | nothing | an entry point missing from `apple_errors.rs`: add it |
| `SELFTEST` | `selftest.*` | none | nothing | device self-test only |

Toasts are rate limited (same class once per 30 s, at most 3 per minute: `NoticeLimiter`); every notice
is in the log whether or not it was shown. Strings: `native_notice_*` in `values/strings.xml` and
`values-es/strings.xml`.

## What Apple's libraries can throw (read from the shipped `.so` files)

Typeinfo symbols, `llvm-nm -D --defined-only <lib> | grep _ZTI | llvm-cxxfilt`:

| Type | Library | Notes |
|---|---|---|
| `ACException` | `libandroidappmusic` | Apple Music Android core exception; `what()` is the reason |
| `SVError`, `SVErrorCategory`, `androidmediaservices::SVErrorReporter` | `libandroidappmusic` | streaming/playback error object and its category |
| `mlcore::MediaError`, `MediaErrorCategory` | `libmedialibrarycore` | media-library errors |
| `storeservicescore::StoreErrorCondition` | `libstoreservicescore` | StoreServices error: error code + external error code (already logged by the mint path as `code=… external=…`) |
| `storeservicescore::FairPlayErrorCategory`, `ITunesStoreErrorCategory`, `ErrorCategory` | `libstoreservicescore` | the categories behind `std::error_condition` values |
| `mediaplatform::ErrorCondition`, `ErrorCategory` | `libmediaplatform` | networking/parsing |
| `std::__ndk1::error_condition` | all | wrapped OS/network errors |
| `mediaplatform::UnitTestException` | `libmediaplatform` | test-only |

`libCoreFP`, `libCoreADI` define none (plain C ABI): their failures come back as return codes, not
exceptions. **Which of these a given failure actually throws is not known yet**: the first captured
`[CXX-THROW]` lines will say; add the answer to `KNOWN_TYPES` in `apple_errors.rs` and to the table above.

### FairPlay stages Apple's libraries report in their own log (logcat)

Apple's code logs failures as text lines (not exceptions); on a device they appear in `adb logcat`
around a failure, and name the stage that broke:
`FairPlayLoadLibraryWithPath failed`, `FairPlayInitContext`, `FairPlayIsValidContext failed`,
`FairPlayGetGUID failed`, `FairPlaySAPInit failed` (and `failed again`), `FairPlaySAPExchange failed`,
`FairPlaySAPPrimeForAction failed`, `FairPlaySAPSign failed`, `FairPlaySAPProcessResponse failed`,
`FairPlayGetSubscriptionStatus: error`, `FairPlayGenerateSubscriptionRequest ERROR invalid sbsync data`,
`FairPlayImportSubscriptionResponse() err`, `FairPlayStopSubscriptionLease`,
`FairPlayConfig::config() ERROR ADISetAndroidID / FairPlaySetAndroidID status`, and in
`libandroidappmusic`: `Fairplay error.`, `FairPlay ERROR creating`, `FairPlay ERROR decrypting`.
When a `[CXX-THROW] class=KEY_REJECTED` appears, read the `FairPlay*` lines just above it in logcat to
see which stage failed.

## Track keys refused while everything else works

One case of `KEY_REJECTED` is worth recognising, because nothing about it points at its cause.
Observed on an Android 10 tablet whose session had been seeded rather than signed in on the device:

- the shared key (`key e1 adam=0`) is still delivered, so the SPC, the certificate and the device
  GUID are accepted;
- every track key comes back as `ERROR parsing ckc` / `Invalid CKC error`, for any track, including
  ones that played an hour earlier;
- the catalogue API answers 200 and the subscription reads as active;
- the same account plays on another device;
- `provisioning: ...` in the app log reads `adi.pb=missing fsi.pdat=missing IC-Info.sids=missing`,
  with `no sign-in this run`.

The last line is the cause: **the identity the libraries are configured with was never
provisioned**. Nothing fails at first. That tablet was given track keys for about two and a half
hours with no provisioning files at all, then every one was refused from one request to the next.
What it had been playing kept going from the decrypted cache and stopped part-way through each
track, where the cache ended.

What it is not, each ruled out there: not the track or the storefront (a track that had just played
was refused too); not the network or the account's token (both unchanged when it started working
again); and not the device id as such -- replacing it with a fresh random one changed nothing,
because a new id is just as unprovisioned.

`_deviceHasOfflineSlot() ERROR EMPTY fairPlaySubscriptionStatus` is not a sign of it either: it is
logged on every key exchange, 180 times in that log while keys were being delivered.

It ends when the identity is provisioned: by signing in on the device, which is what makes the
libraries run the provisioning, or by restoring an identity together with the `adi.pb`, `fsi.pdat`
and `IC-Info.sids` that belong to it. With the set back in place the next start was given six track
keys and refused none.

## Proving it still works

`native/selftest-cxx-guard.sh <adb-serial>` runs, on a phone and outside the app: caught throws of
std, non-std and `int` exceptions (type + message in the line, secrets scrubbed), scope skipping, the
inline-asm call path, 200,000 throws with flat memory, the `[RUST-PANIC]` line, the `[CXX-TERMINATE]`
line for a throw on a thread with only C++ frames, and the fatal-signal line. After changing
`cxx_trampoline.cpp`, regenerate its assembly with `native/regen-cxx-trampoline.sh` first.


## Hooks inside Apple's libraries (`[HOOK]`, `[CXX-RAISE]`, `A/` lines)

Built from `native/cadence-hooks` (host-tested) and `cadence-native-rs/src/hooks_glue.rs`; installed in `JNI_OnLoad`.

| line | meaning |
|---|---|
| `[HOOK] logger=on throw=on slots=6 already=0` | both hooks installed; `slots` = `__cxa_throw` PLT slots patched (6 libraries expected). `throw=off (reason)` = the throw hook could not install (logging still works); `logger=off throw=off (files/hooks.off exists)` = switched off by the user's kill switch |
| `[HOOK] N Apple/native log line(s) dropped in the last second (rate limit)` | more than 25 lines/s arrived; the rest were counted, not written |
| `[HOOK] N record(s) lost so far because the hook queue was full` | the 512-entry queue overflowed (the drain thread was starved) |
| `[CXX-RAISE] type=<mangled> xN at lib.so+0xOFF <- lib.so+0xOFF ... (thread name)` | a C++ exception was THROWN inside a patched library, whether or not anyone caught it. `type` is the mangled C++ type (`N11storeservices7SVErrorE` = `storeservices::SVError`); `xN` the count for this type at this call site (the first 3 per site are written in full); `at` is the code that threw, the rest is the call chain. Resolve `lib.so+0xOFF` against the shipped library with `llvm-symbolizer`/`addr2line` or `readelf -s` |
| `[CXX-RAISE] summary: ... thrown N more time(s)` | every 10 s: throws beyond the first 3 per site, counted only |
| `A/<tag> [thread] <P> t<tid> message` in cadence.log | a line an Apple library (or any warning/error of the process) wrote to Android's log, with its priority letter and thread id |

Kill switch: create an empty file `files/hooks.off` in the app's private directory (`run-as app.cadence touch files/hooks.off` on a debug build, or clear app data to remove it) and restart the app.
Design, audit and the facts it rests on: `docs/plan-native-hooks.md`.
