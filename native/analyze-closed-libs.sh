#!/usr/bin/env bash
# Measures the actual call surface of the closed Apple libraries shipped in the app.
#
# The question it answers is "what would have to be implemented to remove this library?",
# and the answer is not "all of its exports": almost none are used. It measures in two ways,
# because both together give the real number:
#
#   1. Static imports: which symbols of each library another references in its dynsym. Names are
#      normalized by stripping the `@@VERSION` suffix; without that, a library with versioned
#      symbols (libxml2, libCoreFP) looks unused when it is actually used.
#   2. Dynamic resolution: which exports appear as a **string** in another library. That is how
#      libandroidappmusic reaches CoreFP/CoreLSKD/CoreADI: dlopen by filename and dlsym by
#      function name, leaving zero static imports. Looking only at dynsym gives the wrong
#      verdict ("nobody uses them, they can be deleted") and breaks playback.
#
# Requires the NDK (ANDROID_NDK_HOME, or the newest one under ANDROID_HOME).
#
#   ./native/analyze-closed-libs.sh
set -euo pipefail
cd "$(dirname "$0")/.."
LIBS="backend/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

python3 -I - "$BIN" "$LIBS" <<'PY'
import os, re, subprocess, sys
BIN, LIBDIR = sys.argv[1], sys.argv[2]
libs = sorted(f for f in os.listdir(LIBDIR) if f.endswith('.so'))
def run(*a): return subprocess.run(list(a), capture_output=True, text=True).stdout
def norm(s): return s.split('@')[0]
INTERNALS = {'__bss_start', '_edata', '_end', '_fini', '_init'}

defined, undefined, needed, soname, strings_map, sizes = {}, {}, {}, {}, {}, {}
for l in libs:
    p = os.path.join(LIBDIR, l)
    sizes[l] = os.path.getsize(p)
    defined[l] = {norm(x.split()[-1]) for x in
        run(BIN+'/llvm-nm','-D','--defined-only','--no-sort',p).splitlines() if len(x.split()) >= 2} - INTERNALS
    undefined[l] = {norm(x.split()[-1]) for x in
        run(BIN+'/llvm-nm','-D','--undefined-only','--no-sort',p).splitlines() if len(x.split()) >= 2}
    d = run(BIN+'/llvm-readelf','-d',p)
    needed[l] = re.findall(r'NEEDED.*?\[(.*?)\]', d)
    so = re.findall(r'SONAME.*?\[(.*?)\]', d); soname[l] = so[0] if so else l
    strings_map[l] = set(subprocess.run(['strings','-a',p], capture_output=True, text=True).stdout.splitlines())

print('=' * 96)
print('ACTUAL SURFACE OF EACH LIBRARY')
print('=' * 96)
print(f"{'library':<30}{'MB':>7}{'exports':>9}{'used':>8}{'dyn':>6}  how reached")
print('-' * 96)
rows = []
for l in sorted(libs, key=lambda x: -sizes[x]):
    static = {s for other in libs if other != l for s in (undefined[other] & defined[l])}
    dynamic = {s for s in defined[l] if any(s in strings_map[o] for o in libs if o != l)}
    by_name = [o for o in libs if o != l and soname[l] in strings_map[o]]
    in_needed = [o for o in libs if soname[l] in needed[o]]
    how = []
    if static: how.append(f"static link from {len(set(o for o in libs if o != l and undefined[o] & defined[l]))}")
    if by_name: how.append("dlopen by name: " + ", ".join(by_name))
    if not how and in_needed: how.append(f"NEEDED only by {', '.join(in_needed)}")
    if not how: how.append("not referenced (loaded by runtime)")
    print(f"{l:<30}{sizes[l]/1048576:>7.2f}{len(defined[l]):>9}{len(static):>8}"
          f"{len(dynamic):>6}  {'; '.join(how)}")
    rows.append((l, sizes[l], defined[l], static, dynamic, by_name))

print()
print('=' * 96)
print('LIBRARIES WITH ZERO STATIC IMPORTS: size and entry points')
print('=' * 96)
total = 0
for l, t, defs, est, din, callers in rows:
    if est: continue
    total += t
    print(f"\n{l}  ({t/1048576:.2f} MB, {len(defs)} exports)")
    if callers:
        print(f"  referenced by filename: {', '.join(callers)}")
        used = sorted(s for s in defs if any(s in strings_map[o] for o in callers))
        print(f"  exports named by that library: {len(used)} of {len(defs)}")
        for s in used: print(f"      {s}")
        if not used:
            print("      none -> loaded for its load-time side effects (JNI_OnLoad), not its functions")
    else:
        print("  not named anywhere: loaded by runtime (System.loadLibrary) or ours")
print(f"\ntotal with zero static imports: {total/1048576:.2f} MB")
print("""
Read carefully: "no static imports" does NOT mean "safe to delete". A library reached via
dlopen+dlsym leaves no trace in anyone's dynsym, and removing it breaks playback at runtime,
not at link time. The number that matters for removing it is the list of named exports
above: that is what would have to be implemented.""")
PY
