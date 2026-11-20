#!/usr/bin/env bash
# Runs the port test suites and verifies they cannot pass green without validating anything.
#
# Two passes:
#   1. the normal suite, requiring the capture (CADENCE_VECTORS_REQUIRED=1);
#   2. an adversarial matrix: the capture is intentionally broken and tests are verified to
#      FAIL. That second pass is the point: previously each tests/vectors.rs did
#      `let Some(x) = load() else { return }`, so without a capture it passed green.
#
# Uses native/vectors.json if present (the real device capture, gitignored) and otherwise
# native/vectors.sample.json (the checked-in synthetic sample). Both must pass.
#
#   ./native/test-ports.sh
set -uo pipefail
cd "$(dirname "$0")"
PORTS=(apple-ss-sys device-guid fairplay-proto store-config vectors-fixture)
ok=0; bad=0

# Runs a suite and enforces an expected outcome. A COMPILE error is also a failure: if you
# only look for "FAILED" in the output, a crate that fails to compile looks like it passed.
run_case() { # label, expected(pass|fail), dir, [env...]
    local label="$1" expected="$2" dir="$3"; shift 3
    local out rc
    out=$(cd "$dir" && env "$@" cargo test --quiet 2>&1); rc=$?
    local got; [ $rc -eq 0 ] && got=pass || got=fail
    if [ "$got" = "$expected" ]; then
        local n; n=$(printf '%s' "$out" | grep -oE '[0-9]+ passed' | awk '{s+=$1} END {print s+0}')
        if [ "$expected" = pass ] && [ "$n" -eq 0 ]; then
            printf '  BAD  %-52s passed but ran zero tests\n' "$label"; bad=$((bad+1)); return
        fi
        printf '  OK   %-52s %s%s\n' "$label" "$got" \
            "$([ "$expected" = pass ] && echo " ($n tests)")"
        ok=$((ok+1))
    else
        printf '  BAD  %-52s %s, expected %s\n' "$label" "$got" "$expected"
        printf '%s\n' "$out" | grep -E 'error|panicked' | head -4 | sed 's/^/         /'
        bad=$((bad+1))
    fi
}

capture() { [ -f vectors.json ] && echo vectors.json || echo vectors.sample.json; }
echo "=== 1) port suites (capture: $(capture)) ==="
for c in "${PORTS[@]}"; do run_case "$c" pass "$c" CADENCE_VECTORS_REQUIRED=1; done

echo
echo "=== 2) adversarial matrix: broken capture must FAIL ==="
BACKUP=$(mktemp -d); trap 'rm -rf "$BACKUP"' EXIT
for f in vectors.json vectors.sample.json; do [ -f "$f" ] && mv "$f" "$BACKUP/"; done

run_case "no capture + REQUIRED=1" fail device-guid CADENCE_VECTORS_REQUIRED=1
run_case "no capture, no REQUIRED (skipped)" pass device-guid CADENCE_VECTORS_REQUIRED=

echo '{ not json' > vectors.sample.json
run_case "unparseable capture" fail device-guid CADENCE_VECTORS_REQUIRED=1

echo '{"userAgent":"Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)"}' > vectors.sample.json
run_case "missing ckcRequests -> fairplay" fail fairplay-proto CADENCE_VECTORS_REQUIRED=1
run_case "missing deviceGuid -> device-guid" fail device-guid CADENCE_VECTORS_REQUIRED=1

echo '{"ckcRequests":[],"deviceGuid":{},"deviceGuidInputs":{},"userAgent":"x"}' > vectors.sample.json
run_case "empty ckcRequests (nothing to validate)" fail fairplay-proto CADENCE_VECTORS_REQUIRED=1

rm -f vectors.sample.json
for f in "$BACKUP"/*; do [ -e "$f" ] && mv "$f" .; done
echo
echo "=== 3) restored capture passes again ==="
run_case "device-guid with $(capture)" pass device-guid CADENCE_VECTORS_REQUIRED=1

echo
echo "RESULT: $ok passed, $bad failed"
[ "$bad" -eq 0 ]
