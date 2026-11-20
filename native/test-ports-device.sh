#!/usr/bin/env bash
# Runs the port crates' tests on a connected Android device, not on the build machine.
#
# The host run (test-ports.sh) proves the ports are right on x86-64. The code that ships is
# arm64 on bionic, a different target: another endianness would not matter here, but pointer
# width assumptions, the libc++ string layout the ABI tests poke at, and anything that reads the
# file system do. This cross-compiles every test binary for aarch64-linux-android, pushes them to
# /data/local/tmp and runs them there under the shell user. Nothing is installed and the app is
# not involved.
#
# The capture goes along with them: native/vectors.json when there is one (the real capture, kept
# out of git), otherwise the committed synthetic sample. It is pointed at through
# CADENCE_VECTORS_FILE, since the path the tests would otherwise use is a build-machine path, and
# it is required, so a test cannot pass by skipping. Everything pushed is removed afterwards.
#
#   native/test-ports-device.sh            # ANDROID_SERIAL picks the device when there are several
set -uo pipefail
cd "$(dirname "$0")"
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 2; }
API=29 # minSdk
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android$API-clang"
# Build-machine paths stay out of the binaries that go to the device.
export RUSTFLAGS="--remap-path-prefix=$HOME=/home --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo --remap-path-prefix=$PWD=/src"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "no device connected" >&2; exit 2; }
DIR=/data/local/tmp/cadence-port-tests
CAPTURE=vectors.sample.json; [ -f vectors.json ] && CAPTURE=vectors.json
trap 'adb shell "rm -rf $DIR" >/dev/null 2>&1' EXIT

echo "device: $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
echo "capture: $CAPTURE"
adb shell "rm -rf $DIR && mkdir -p $DIR" >/dev/null
adb push "$CAPTURE" "$DIR/vectors.json" >/dev/null

total=0; failed=0; suites=0
for crate in apple-ss-sys device-guid fairplay-proto store-config vectors-fixture; do
    bins=$(cd "$crate" && cargo test --no-run --locked --target aarch64-linux-android --message-format=json 2>/dev/null \
        | python3 -I -c 'import json,sys
for l in sys.stdin:
    try: o = json.loads(l)
    except ValueError: continue
    if o.get("reason") == "compiler-artifact" and o.get("executable") and o.get("profile", {}).get("test"):
        print(o["executable"])')
    if [ -z "$bins" ]; then echo "  FAIL  $crate: did not build for aarch64-linux-android"; failed=$((failed+1)); continue; fi
    for b in $bins; do
        name="$crate-$(basename "$b" | sed 's/-[0-9a-f]*$//')"
        adb push "$b" "$DIR/$name" >/dev/null
        out=$(adb shell "cd $DIR && chmod 755 $name && CADENCE_VECTORS_FILE=$DIR/vectors.json CADENCE_VECTORS_REQUIRED=1 TMPDIR=$DIR ./$name 2>&1; echo EXIT=\$?" | tr -d '\r')
        rc=$(printf '%s\n' "$out" | sed -n 's/^EXIT=//p' | tail -1)
        n=$(printf '%s\n' "$out" | grep -oE '[0-9]+ passed' | awk '{s+=$1} END {print s+0}')
        suites=$((suites+1)); total=$((total+n))
        if [ "$rc" = "0" ]; then printf '  PASS  %-34s %s tests\n' "$name" "$n"
        else
            printf '  FAIL  %-34s exit %s\n' "$name" "$rc"; failed=$((failed+1))
            printf '%s\n' "$out" | grep -E 'panicked|FAILED|error' | head -4 | sed 's/^/          /'
        fi
    done
done
echo
echo "on device: $total tests in $suites binaries, $failed failed"
# A run that executed nothing is not a pass.
[ "$failed" -eq 0 ] && [ "$total" -gt 0 ]
