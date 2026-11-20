# The Closed Libraries: What They Weigh and What Removing Them Takes

The numbers in this document are produced by `./native/analyze-closed-libs.sh`. If the libraries
change, re-run it; do not edit by hand.

## Summary

Out of the 70 MB in `jniLibs/arm64-v8a`, three closed libraries total **17.45 MB** and the app
reaches them through **11 functions** — not 99.

| library | size | exports | referenced | real functions |
|---|---|---|---|---|
| `libCoreLSKD.so` | 7.92 MB | 89 | 7 | 6 + `JNI_OnLoad` |
| `libCoreFP.so` | 7.82 MB | 7 | 6 | 5 + `JNI_OnLoad` |
| `libCoreADI.so` | 1.71 MB | 3 | 1 | `JNI_OnLoad` only |

`libCoreLSKD.so` is the most striking case: 89 exports of which 7 are referenced. The other 82 are
unused surface area.

## How They Are Reached (and Why `dynsym` Misleads)

None of the three leaves a **single static import** in anyone's `dynsym`. Looking at that alone,
the verdict would be "nobody uses them, they can be deleted" -- which is false: removing them
breaks playback at runtime, not at link time.

The real path is dynamic. `libandroidappmusic.so` carries the library filenames (`libCoreFP.so`,
`libCoreADI.so`, `libCoreLSKD.so`) and function names as **strings**, and imports `dlopen`/`dlsym`:
it opens each library by filename and resolves functions by name. That creates no relocation entries,
so it is invisible to any analysis that only reads symbol tables.

Two details worth keeping in mind:

- **`libandroidappmusic.so` also references `JNI_OnLoad`**, i.e., it calls `dlsym(h, "JNI_OnLoad")`
  and invokes it manually. `dlopen` alone does not invoke `JNI_OnLoad`: the Java runtime does that
  in `System.loadLibrary`, and on the Kotlin side the only library loaded that way is
  `cadence_auth_android`. The entire Apple chain is pulled in by `libandroidappmusic`.
- **`libCoreADI.so` has neither of its two functions referenced**, only `JNI_OnLoad`. It is loaded
  for its initialization side effect, not because anything is called on it afterward. Its
  `JNI_OnLoad` is obfuscated: it stores `JavaVM*` and dispatches to a single computed indirect call
  leading to `vdfut768ig` (a single 874 KB function with MBA and control-flow flattening). That is
  what provisions the machine state (`adi.pb`).

Some exports carry version suffixes (`@@VERS_1.73.0` in CoreFP, `@@LIBXML2_...` in libxml2).
Comparing names without normalizing that suffix makes the library appear unused. The script
normalizes it; any manual analysis has to do the same.

## What the Minimum Set of Libraries Is Today

`./native/minimal-libs.sh` computes it from the real roots: what Kotlin loads with
`System.loadLibrary`, which is three (`cadence_auth_android`, `cadence_android_drm`,
`cadence-native`).

**The minimum is all 21 — the full 70.18 MB. Not a single one can be removed without surgery.**

The reason is `DT_NEEDED`, not calls. Bionic requires **all** `DT_NEEDED` entries to be present on
`dlopen`, whether or not any symbol from them is used; if one is missing, `System.loadLibrary` fails
and the app does not start. And the chain from our root leaves nothing out:

```
libcadence-native.so  (root, NativeWrapper/NativeAlac/NativeEac3)
  -> libandroidappmusic.so        23 NEEDED entries, 15 of them in the APK
       -> libCoreFP.so  libCoreADI.so  libCoreLSKD.so
       -> libmedialibrarycore.so  libstoreservicescore.so  libdaapkit.so
       -> libCoreFoundation.so  libdispatch.so -> libBlocksRuntime.so
       -> libicuuc  libicui18n  libicudata  libxml2  libcurl -> libstdc++.so
  -> libmediaplatform.so  libstoreservicescore.so  libc++_shared.so
```

The `dlopen` closure adds **not a single library** beyond `DT_NEEDED` (B − A = 0 MB). That is, the
three closed libraries are not there "because someone opens them at runtime"; they are hard load
dependencies. Looking only at the `dlopen` path understates the problem.

## What Can Be Removed Today

**None of the three, without first replacing what they do.** They are the FairPlay core
(CoreFP/CoreLSKD) and provisioning (CoreADI). A stub that exports the names satisfies `dlsym` and
lets the app load, but the first key request fails.

What *does* exist is a **single choke point**: `libandroidappmusic.so` is the only library that
declares all three in its `DT_NEEDED`. To stop them from being mandatory at load time, only that
one file needs patching: remove three entries from its `DT_NEEDED`. That is link-safe because no
library imports a single static symbol from the three (verified after normalizing `@@VERSION`
suffixes). And it leaves nothing orphaned: the three only depend on system libraries (`liblog`,
`libdl`, `libc`, `libz`, `libm`) plus `libstdc++.so`, which `libcurl` also needs.

| scenario | libs | jniLibs | what is lost |
|---|---|---|---|
| today, untouched | 21 | 70.18 MB | - |
| patch `DT_NEEDED` and drop CoreADI only | 20 | 68.47 MB | `adi.pb` provisioning |
| patch `DT_NEEDED` and drop all three | 18 | 52.73 MB | playback: first key fails |

## The Experiment, Already Run

`./native/surgery-drop-closed.sh` removes the three from `libandroidappmusic.so`'s `DT_NEEDED` with
`patchelf` and produces a `jniLibs` tree without them. The patch is surgical: the file stays the
**exact same size** (`patchelf` shifts entries in place), with identical ELF header and program
headers, and `.dynamic` at the same offset with 48 -> 45 entries.

Installed on an Android 10 (API 29) tablet, here is what happened:

| | with all 3 | without all 3 |
|---|---|---|
| APK | 152.61 MB | **135.14 MB** (−17.47 MB, −11.4%) |
| starts up | yes | **yes**, no `UnsatisfiedLinkError` or linker error |
| session / login | yes | **yes** |
| library, catalog, lyrics, covers | yes | **yes** (`me/library/songs -> 200`, `syllable-lyrics -> 200`) |
| playback | yes | **no**: `Key exchange for e1 failed` |

In short: the three libraries buy **exactly one thing** — playback — and cost 17.47 MB. Everything
else in the app works without them. The failure is clean and localized (key exchange), not a hang
or a crash.

### And One by One

Removing them together does not show which one matters for what. Repeated for each individually:

| without... | APK | starts up | plays |
|---|---|---|---|
| `libCoreADI.so` | 150.89 MB | yes | **no**: 0 keys, 13 exchange failures |
| `libCoreLSKD.so` | 144.69 MB | yes | **no**: `Key exchange for e1 failed` |
| `libCoreFP.so` | 144.78 MB | **no**: process dies at startup | - |

**None of the three can be removed.** The minimum remains all 21 libraries, 70.18 MB.

`libCoreFP.so` is the hardest case: not only does playback fail, the app does not start.
`libstoreservicescore.so` also names that file, not just `libandroidappmusic.so`, so it opens it
during initialization and the failure is fatal.

`libCoreADI.so` deserves a note because it almost slipped by as removable. On the first pass it
appeared to play: the detector saw `state=PlaybackState {state=3}` and counted it as good. But it
was playing with a **cached key** from the previous session, and cut off mid-track. Repeated from a
clean start: 0 keys obtained and 13 failures. So CoreADI does not only provision `adi.pb` on first
run -- it participates in every key exchange, consistent with supplying the machine data that
authenticates the request to the key server.

**The measurement trap, so we don't repeat it:** hearing audio start right after install proves
nothing. You have to start clean, play, and **skip tracks** to force a fresh key with no cache
possible. Measuring `state=3` alone is the playback equivalent of the same false-green that vector
tests used to have.

Two practical consequences:

1. **The `DT_NEEDED` patch is validated.** There is no need to guess whether bionic accepts the
   patched binary: it does, and the app starts without CoreADI or CoreLSKD. What is needed to
   actually remove them is replacing key exchange, not solving a link problem. With CoreFP not even
   that: you would also have to cover what `libstoreservicescore` asks it for at initialization.
2. **There is room for a degraded mode** (browsing the library without playback) without shipping
   17.47 MB, if that is ever needed. That is the only thing this measurement enables today.

### Reproducing the Experiment

```sh
./native/surgery-drop-closed.sh /tmp/jnilibs-without-closed
cp /tmp/jnilibs-without-closed/*.so backend/src/main/jniLibs/arm64-v8a/   # on a copy
rm -f app/build/outputs/apk/debug/app-debug.apk   # otherwise AGP repackages leaving holes
./gradlew :app:assembleDebug
git checkout -- backend/src/main/jniLibs/arm64-v8a                      # restore
```

The APK `rm` is not optional: AGP's incremental packager rewrites the zip leaving deleted entry
bytes as unreferenced padding, so the file comes out the same size even though its contents are
17 MB smaller. Measured: 160,562,499 vs. 160,562,280 bytes — a 219-byte difference for 17.45 MB
of removed libraries.

What *did* change with the ports is that the surface is **measured and small**: 11 functions. Before,
the quoted number was "the 11 proprietary libraries", which said nothing about what would actually
need to be written.

## The Order That Makes Sense to Tackle It

1. **Measure dynamic usage, not static.** Out of the 11, find which ones are actually called during
   a streaming session. `cadence-hooks` already does PLT hooking; an interposer counting calls
   across a full playback session gives that list. Several of the 6 in CoreLSKD may only run on the
   offline path.
2. **CoreADI first** (1.71 MB, single entry point). It is the smallest target with the narrowest
   surface. What needs to be reproduced is the effect of its `JNI_OnLoad` on `adi.pb`, not an API.
3. **CoreFP/CoreLSKD last** (15.74 MB). They are whitebox AES: the key is baked into obfuscated
   code, so "porting" them means extracting the key, not reimplementing an algorithm. It is the
   largest job of the three and best left untouched while the rest works.

## Where the Rest of the Size Goes (Outside These Three)

To put 17.45 MB in perspective, in the current debug APK (153 MB):

| item | size | note |
|---|---|---|
| `classes*.dex` | ~75 MB | unminified debug build; a release build with R8 shrinks it heavily |
| `lib/arm64-v8a` | 70 MB | of which 17.45 MB are these three |
| `libmedialibrarycore.so` | 13.21 MB | 614 genuinely used symbols: not a candidate |
| `libicudata_sv_apple.so` | 9.75 MB | ICU 57 data blob; trim rather than stub -- see below |
| `assets/native-data` | 5.07 MB | test session data; `.gitignore` keeps it out of the repo and it must not ship in release |

That is: the largest chunk in a release is not the closed libraries, it is DEX. Measure an
`assembleRelease` before operating on anything native.

## The Other Heavy Item: ICU Data

`./native/icu-data-audit.py` lists what is inside `libicudata_sv_apple.so` without modifying
anything. It is the second-largest file in the APK and exports a single symbol,
`icudt__sv_apple57_dat`: an ICU 57 data package whose format is standard and can be listed without
ICU tools.

9.73 MB of data across 883 items:

| type | items | MB | what it is |
|---|---|---|---|
| `.res` | 858 | 4.78 | per-locale resources: formats, names, collation |
| `.dict` | 5 | 2.75 | word-break dictionaries (CJK, Khmer, Burmese, Thai) |
| `.brk` | 14 | 1.46 | line- and word-break rules |
| `.icu` | 2 | 0.52 | base collation data |
| `.nrm` | 3 | 0.16 | Unicode normalization |

A single item, `brkitr/cjdict.dict`, weighs **1.96 MB**. The three trimming scenarios measured by
the tool: dropping `.dict` saves 2.75 MB, dropping zh/ko/ja collation saves 1.25 MB, and keeping
only `root/en/en_US/es/es_419/es_CL` in `.res` saves 3.81 MB.

**Why it is not applied.** Unlike the closed libraries, trimming this does not break linking: it
breaks text, and late. `.dict` files split words in Chinese and Japanese, and `.res` carries the
collation used to sort titles. Apple Music has catalog entries in those languages, so the failure is
not hypothetical -- it shows up the day a track with a Japanese title plays, not the day of the
change. Doing it right requires an explicit allowlist of what is kept and tests with content in
those languages, not just checking that the app starts.

It is, however, the **most viable** trim available: it does not depend on reimplementing
cryptography, only on deciding which languages are supported.
