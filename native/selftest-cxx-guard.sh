#!/usr/bin/env bash
# On-device proof that an Apple C++ exception is caught instead of aborting the app.
#
# Builds libcadence-native.so with `--features selftest` (strip --strip-all, like the real build),
# a stand-in "Apple library" that throws (tests/cxx_guard/thrower_so.cpp) and a dlopen harness, then
# runs them on a connected phone from /data/local/tmp with the app's own shipped libc++_shared.so.
# Nothing touches the installed app; the temp directory is removed afterwards.
#
# It also runs the host unit tests of the pure log-formatting helpers first.
#
# usage: native/selftest-cxx-guard.sh [adb-serial]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
SERIAL="${1:-}"
ADB=(adb ${SERIAL:+-s "$SERIAL"})
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
API=31
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXXSO="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
JNI="$ROOT/backend/src/main/jniLibs/arm64-v8a"
T="$ROOT/native/cadence-native-rs"
W="$(mktemp -d)"
echo "== host unit tests of the log formatting (logfmt.rs)"
rustc --edition 2021 --test "$T/src/logfmt.rs" -o "$W/logfmt_test" && "$W/logfmt_test" | tail -3
trap 'rm -rf "$W"; "${ADB[@]}" shell rm -rf /data/local/tmp/cxxguard >/dev/null 2>&1 || true' EXIT

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android$API-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export CXX_aarch64_linux_android="$BIN/aarch64-linux-android$API-clang++"
export AR_aarch64_linux_android="$BIN/llvm-ar"
# Stand-in Apple library: linked against libc++_shared by full path (-L on the sysroot would pull in
# a static libc), and put on the cdylib's link line so its `cpp_throw` resolves like Apple's symbols.
"$BIN/aarch64-linux-android$API-clang++" -shared -fPIC -O1 -nostdlib++ "$T/tests/cxx_guard/thrower_so.cpp" "$CXXSO" -o "$W/libthrower_so.so"
export RUSTFLAGS="-C target-cpu=generic -C link-arg=-Wl,-z,max-page-size=16384 -L native=$W"
export CARGO_TARGET_DIR="$T/target/selftest"
(cd "$T" && cargo build --release --locked --target aarch64-linux-android --features selftest)
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libcadence_native.so" "$W/libcadence-native-selftest.so"
"$BIN/llvm-strip" --strip-all "$W/libcadence-native-selftest.so"
rustc --target aarch64-linux-android -O "$T/tests/cxx_guard/harness.rs" -C linker="$BIN/aarch64-linux-android$API-clang" -o "$W/harness"

D=/data/local/tmp/cxxguard
"${ADB[@]}" shell "rm -rf $D; mkdir -p $D/libs"
for f in "$JNI"/*.so; do
    case "$(basename "$f")" in libcadence-native.so|libcadence_android_drm.so|libcadence_auth_android.so) ;; *) "${ADB[@]}" push "$f" "$D/libs/" >/dev/null;; esac
done
"${ADB[@]}" push "$W/libthrower_so.so" "$D/libs/" >/dev/null
"${ADB[@]}" push "$W/libcadence-native-selftest.so" "$W/harness" "$D/" >/dev/null
"${ADB[@]}" shell "chmod 755 $D/harness; cd $D && LD_LIBRARY_PATH=$D/libs ./harness $D/libcadence-native-selftest.so"
