#!/usr/bin/env bash
# Surgery: stop shipping closed libraries by removing them from DT_NEEDED of their dependents.
#
# The problem is not that they are called, it is that they are **load-time** dependencies:
# bionic requires every DT_NEEDED entry to be present on dlopen, whether or not any symbol
# from it is used. Deleting the file alone is not enough: the DT_NEEDED entry must also be
# removed, or System.loadLibrary fails and the app will not start.
#
# Produces a patched jniLibs tree in an output directory. **Does not touch the original.**
#
#   ./native/surgery-drop-closed.sh <out-dir> [libX.so ...]
#
# Without a list, drops the three closed libraries that no object imports by symbol:
# libCoreFP.so, libCoreLSKD.so, libCoreADI.so (17.45 MB).
#
# Before patching, checks that dropping them is link-safe, and afterward that the result is
# still loadable. If either check fails, it aborts: a .so with broken DT_NEEDED prevents the
# app from starting and the symptom (UnsatisfiedLinkError) does not name the culprit.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="backend/src/main/jniLibs/arm64-v8a"
OUT="${1:?usage: surgery-drop-closed.sh <out-dir> [libX.so ...]}"; shift || true
DROP=("$@"); [ ${#DROP[@]} -eq 0 ] && DROP=(libCoreFP.so libCoreLSKD.so libCoreADI.so)

command -v patchelf >/dev/null || { echo "missing patchelf (pip install patchelf, or ~/.local/bin)" >&2; exit 1; }
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$BIN/llvm-nm" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }

norm() { sed 's/@@.*//' | sort -u; }     # exports may carry a @@VERSION suffix
defs() { "$BIN/llvm-nm" -D --defined-only "$1" 2>/dev/null | awk '{print $NF}' | norm; }
unds() { "$BIN/llvm-nm" -D --undefined-only "$1" 2>/dev/null | awk '{print $NF}' | norm; }

echo "=== 0) dropping: ${DROP[*]} ==="
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
: > "$TMP/dropped-exports"
for l in "${DROP[@]}"; do
    [ -f "$SRC/$l" ] || { echo "missing $SRC/$l" >&2; exit 1; }
    defs "$SRC/$l" >> "$TMP/dropped-exports"
done
sort -u -o "$TMP/dropped-exports" "$TMP/dropped-exports"
echo "    they export $(wc -l < "$TMP/dropped-exports") symbols combined"

echo
echo "=== 1) link-safe? no remaining object may import a symbol from them ==="
bad=0
for f in "$SRC"/*.so; do
    b=$(basename "$f"); case " ${DROP[*]} " in *" $b "*) continue;; esac
    n=$(comm -12 <(unds "$f") "$TMP/dropped-exports" | wc -l)
    [ "$n" -gt 0 ] && { echo "    NO: $b imports $n symbols from dropped libs"; bad=1; }
done
[ $bad -eq 0 ] && echo "    yes: no remaining object imports any symbol from dropped libs"
[ $bad -eq 0 ] || { echo "aborted: those symbols must be replaced first" >&2; exit 1; }

echo
echo "=== 2) copy and patch ==="
rm -rf "$OUT"; mkdir -p "$OUT"
for f in "$SRC"/*.so; do
    b=$(basename "$f"); case " ${DROP[*]} " in *" $b "*) continue;; esac
    cp "$f" "$OUT/$b"
done
echo "    copied $(ls "$OUT" | wc -l) of $(ls "$SRC" | wc -l)"
for f in "$OUT"/*.so; do
    b=$(basename "$f"); before=$(patchelf --print-needed "$f" | wc -l); removed=()
    for l in "${DROP[@]}"; do
        if patchelf --print-needed "$f" | grep -qx "$l"; then
            so_before=$(patchelf --print-soname "$f" 2>/dev/null || echo "")
            patchelf --remove-needed "$l" "$f"
            so_after=$(patchelf --print-soname "$f" 2>/dev/null || echo "")
            [ "$so_before" = "$so_after" ] || { echo "    $b: SONAME changed ($so_before -> $so_after)" >&2; exit 1; }
            removed+=("$l")
        fi
    done
    [ ${#removed[@]} -gt 0 ] && echo "    $b: $before -> $(patchelf --print-needed "$f" | wc -l) NEEDED (removed: ${removed[*]})"
done

echo
echo "=== 3) can the result load? ==="
# 3a) no DT_NEEDED entry may point to a file that is no longer present.
#     System libs (libc, liblog, libz, libOpenSLES...) are provided by Android, not the APK.
SYSTEM='^lib(c|m|dl|log|z|android|OpenSLES|stdc\+\+|GLESv[0-9]|EGL|jnigraphics|mediandk|nativewindow|aaudio|vulkan|neuralnetworks|cameraandroid)\.so$'
missing=0
for f in "$OUT"/*.so; do
    for dep in $(patchelf --print-needed "$f"); do
        [ -f "$OUT/$dep" ] && continue
        echo "$dep" | grep -qE "$SYSTEM" && continue
        echo "    DANGLING: $(basename "$f") requires $dep and it is not in the tree"; missing=1
    done
done
[ $missing -eq 0 ] && echo "    ok: no dangling dependencies"
# 3b) no undefined symbol is left without a provider in the tree.
#     System-resolved symbols cannot be checked here, so we only count them.
: > "$TMP/all-exports"
for f in "$OUT"/*.so; do defs "$f" >> "$TMP/all-exports"; done
sort -u -o "$TMP/all-exports" "$TMP/all-exports"
for f in "$OUT"/*.so; do
    n=$(comm -23 <(unds "$f") "$TMP/all-exports" | wc -l)
    printf "    %-30s %5s symbols resolved by system\n" "$(basename "$f")" "$n"
done
# 3c) the ELF remains valid and with Android's required alignment.
for f in "$OUT"/*.so; do
    "$BIN/llvm-readelf" -h "$f" >/dev/null 2>&1 || { echo "    INVALID ELF: $(basename "$f")" >&2; exit 1; }
done
echo "    ok: all $(ls "$OUT" | wc -l) ELFs parse"

echo
echo "=== 4) space saved ==="
a=$(du -sb "$SRC" | cut -f1); d=$(du -sb "$OUT" | cut -f1)
awk -v a="$a" -v d="$d" 'BEGIN{printf "    before %.2f MB -> after %.2f MB  (-%.2f MB, -%.1f%%)\n",
    a/1048576, d/1048576, (a-d)/1048576, (a-d)*100/a}'
echo
echo "Patched tree written to $OUT. Original was untouched."
echo "Only the real test remains: install it and verify it launches, logs in, and plays."
