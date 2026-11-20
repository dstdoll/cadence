#!/usr/bin/env bash
# Regenerates native/cadence-native-rs/src/cxx_trampoline.s from cxx_trampoline.cpp.
# Labels are prefixed (`cxxg`) so they cannot clash with the ones rustc emits for the rest of the
# crate (it fails with "symbol 'GCC_except_table0' is already defined" otherwise); -fvisibility=hidden
# keeps the entry points hidden. After regenerating, run native/selftest-cxx-guard.sh on a phone.
set -euo pipefail
cd "$(dirname "$0")/cadence-native-rs/src"
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang++"
raw="$(mktemp)"
"$CXX" -S -O2 -std=c++17 -fexceptions -frtti -fPIC -fvisibility=hidden -nostdinc++ \
    -isystem "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/c++/v1" cxx_trampoline.cpp -o "$raw"
grep -vE '^\s*\.(addrsig|ident|file)' "$raw" \
  | sed -E 's/\.L([A-Za-z_]+)([0-9]*)/.Lcxxg_\1\2/g; s/GCC_except_table([0-9]+)/cxxg_lsda\1/g; s/DW\.ref\.__gxx_personality_v0/cxxg_ref_gxx_personality/g; s/__clang_call_terminate/cxxg_call_terminate/g' \
  > cxx_trampoline.s
rm -f "$raw"
echo "wrote $(wc -l < cxx_trampoline.s) lines to native/cadence-native-rs/src/cxx_trampoline.s"
grep -nE '^\s*\.(globl|hidden)|bl\s+(__cxa|_ZSt|cadence_terminate)|_ZTI' cxx_trampoline.s | head -30
