#!/usr/bin/env python3
"""Measures what is inside libicudata_sv_apple.so and how much trimming it would save.

It is 9.75 MB, the second largest file in the APK after libmedialibrarycore.so, and
exports only one symbol: `icudt__sv_apple57_dat`, the ICU 57 data package. An ICU
package is a table of contents followed by items; the format is standard, so it can
be listed without ICU tools to see the exact cost of each item.

Read-only: reports only, modifies nothing.

    python3 native/icu-data-audit.py [path-to-.so]
"""
import struct
import sys
from collections import defaultdict

LIB_PATH = sys.argv[1] if len(sys.argv) > 1 else "backend/src/main/jniLibs/arm64-v8a/libicudata_sv_apple.so"
# Locales the app actually needs: its declared language (en-US) and store (cl).
USEFUL_LOCALES = {"root", "en", "en_US", "es", "es_419", "es_CL"}


def package_offsets(raw: bytes) -> tuple[int, int]:
    """Offset of the package and its table of contents. UDataInfo signature is da 27."""
    i = raw.find(b"\xda\x27")
    if i < 0:
        sys.exit("ICU data package signature not found")
    off = i - 2
    header_size = struct.unpack_from("<H", raw, off)[0]
    return off, off + header_size


def items(raw: bytes, base: int) -> list[tuple[str, int]]:
    """(name, size) for each item. Size comes from distance to the next item."""
    count = struct.unpack_from("<I", raw, base)[0]
    toc = [struct.unpack_from("<II", raw, base + 4 + i * 8) for i in range(count)]
    total_end = len(raw) - base

    def name_at(off: int) -> str:
        start = base + off
        return raw[start : raw.index(b"\0", start)].decode("ascii", "replace")

    out = []
    for i, (n_off, d_off) in enumerate(toc):
        end = toc[i + 1][1] if i + 1 < count else total_end
        out.append((name_at(n_off), max(0, end - d_off)))
    return out


def locale_of(name: str) -> str:
    return name.rsplit("/", 1)[-1].rsplit(".", 1)[0]


def main() -> None:
    raw = open(LIB_PATH, "rb").read()
    _, base = package_offsets(raw)
    entries = items(raw, base)
    total = sum(t for _, t in entries)
    print(f"{LIB_PATH}: {len(raw)/1048576:.2f} MB, {len(entries)} items, {total/1048576:.2f} MB of data\n")

    by_type = defaultdict(lambda: [0, 0])
    for n, t in entries:
        ext = n.rsplit(".", 1)[-1] if "." in n else "(none)"
        by_type[ext][0] += 1
        by_type[ext][1] += t
    print(f"{'type':<10}{'items':>7}{'MB':>9}   description")
    DESCRIPTIONS = {
        "res": "per-locale resources: formats, names, collation",
        "dict": "word-break dictionaries (CJK, Khmer, Burmese, Thai)",
        "brk": "line and word break rules",
        "icu": "base collation data",
        "nrm": "Unicode normalization",
        "cfu": "confusables (IDN security)",
    }
    for ext, (c, t) in sorted(by_type.items(), key=lambda kv: -kv[1][1]):
        print(f"  {ext:<8}{c:>7}{t/1048576:>9.2f}   {DESCRIPTIONS.get(ext, '')}")

    print("\n10 largest items:")
    for n, t in sorted(entries, key=lambda x: -x[1])[:10]:
        print(f"  {n:<44}{t/1024:>9.1f} KB")

    print("\ntrimming scenarios (none applied):")
    dicts = sum(t for n, t in entries if n.endswith(".dict"))
    cjk_res = sum(t for n, t in entries if n.endswith(".res") and locale_of(n) in {"zh", "ko", "ja"})
    only_useful = sum(
        t for n, t in entries if not n.endswith(".res") or locale_of(n) in USEFUL_LOCALES
    )
    print(f"  drop .dict (CJK/Khmer/Burmese)      : -{dicts/1048576:.2f} MB")
    print(f"  drop zh/ko/ja collation             : -{cjk_res/1048576:.2f} MB")
    print(f"  keep only {'/'.join(sorted(USEFUL_LOCALES))}")
    print(f"    in .res                           : -{(total-only_useful)/1048576:.2f} MB")

    print(
        "\nCaution: trimming this does not break linking, it breaks text handling. The .dict\n"
        "files split words in Chinese, Japanese, Khmer, and Burmese, and .res files carry\n"
        "the collation rules used to sort titles. Apple Music has catalog items in those\n"
        "languages, so breakage is not hypothetical: it appears as soon as a Japanese title\n"
        "plays. Any trim requires an explicit allowlist and tests with content in those\n"
        "languages, not just verifying that the app launches."
    )


if __name__ == "__main__":
    main()
