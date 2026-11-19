#!/usr/bin/env bash
# Builds the project's Rust libraries for Android (arm64-v8a) and copies them into the backend's
# jniLibs. Build-machine paths are remapped and symbols stripped, so the binaries carry no paths
# from the computer that built them.
#
# Needs the aarch64-linux-android Rust target and the Android NDK: ANDROID_NDK_HOME, or the newest
# NDK under ANDROID_HOME (default ~/Android/Sdk).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
API=31 # minSdk
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android$API-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export CXX_aarch64_linux_android="$BIN/aarch64-linux-android$API-clang++"
export AR_aarch64_linux_android="$BIN/llvm-ar"
# C/C++ sources compiled by build scripts: keep __FILE__ and debug paths free of this machine too.
export CFLAGS_aarch64_linux_android="-ffile-prefix-map=$HOME=/home -ffile-prefix-map=$ROOT=/src"
export CXXFLAGS_aarch64_linux_android="$CFLAGS_aarch64_linux_android"
# The most specific prefix goes last: rustc applies the last matching remapping.
export RUSTFLAGS="--remap-path-prefix=$HOME=/home --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo --remap-path-prefix=$ROOT=/src -C link-arg=-Wl,-z,max-page-size=16384"
OUT="$ROOT/backend/src/main/jniLibs/arm64-v8a"

build() { # crate directory, file cargo produces, name in jniLibs
    (cd "native/$1" && cargo build --release --locked --target aarch64-linux-android)
    cp "native/$1/target/aarch64-linux-android/release/$2" "$OUT/$3"
    "$BIN/llvm-strip" --strip-all "$OUT/$3"
    echo "built $3"
}

build cadence-android-drm libcadence_android_drm.so libcadence_android_drm.so
build cadence-native-rs libcadence_native.so libcadence-native.so
# libcadence_auth_android.so is prebuilt: its core crate is not part of this repository.
