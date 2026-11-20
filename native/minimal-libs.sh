#!/usr/bin/env bash
# What is the minimum set of native libraries the app needs?
#
# Starts from the **real roots**: the ones Kotlin loads via System.loadLibrary, which are
# three (NativeAuth.kt, NativeDrm.kt, NativeWrapper/NativeAlac/NativeEac3.kt). From there it
# computes the transitive closure across two edge kinds:
#
#   NEEDED  - DT_NEEDED. **Required at load time**: bionic requires all of them to be present
#             on dlopen, whether or not any symbol from them is used. If one is missing,
#             System.loadLibrary fails and the app does not start.
#   dlopen  - the library names the other file and uses dlopen/dlsym. Only needed at runtime
#             when that functionality is requested.
#
# The difference between the two closures is what could be removed without preventing startup.
#
#   ./native/minimal-libs.sh
set -euo pipefail
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
exec python3 -I "$(dirname "$0")/minimal-libs.py" \
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" \
    "backend/src/main/jniLibs/arm64-v8a"
