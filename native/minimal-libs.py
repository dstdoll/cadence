import os, re, subprocess, sys
BIN, LIBDIR = sys.argv[1], sys.argv[2]
libs = sorted(f for f in os.listdir(LIBDIR) if f.endswith('.so'))
def run(*a): return subprocess.run(list(a), capture_output=True, text=True).stdout
def norm(s): return s.split('@')[0]
INTERNALS = {'__bss_start','_edata','_end','_fini','_init'}

needed, soname, strings_map, sizes, defs, unds = {}, {}, {}, {}, {}, {}
for l in libs:
    p = os.path.join(LIBDIR, l); sizes[l] = os.path.getsize(p)
    d = run(BIN+'/llvm-readelf','-d',p)
    needed[l] = re.findall(r'NEEDED.*?\[(.*?)\]', d)
    so = re.findall(r'SONAME.*?\[(.*?)\]', d); soname[l] = so[0] if so else l
    strings_map[l] = set(subprocess.run(['strings','-a',p],capture_output=True,text=True).stdout.splitlines())
    defs[l] = {norm(x.split()[-1]) for x in run(BIN+'/llvm-nm','-D','--defined-only','--no-sort',p).splitlines() if len(x.split())>=2} - INTERNALS
    unds[l] = {norm(x.split()[-1]) for x in run(BIN+'/llvm-nm','-D','--undefined-only','--no-sort',p).splitlines() if len(x.split())>=2}

by_soname = {}
for l in libs: by_soname.setdefault(soname[l], l)
def resolve_file(so): return by_soname.get(so)

# Roots: what Kotlin/Java code loads via System.loadLibrary. Do not guess; read from source
# so the answer stays accurate if one is added or removed later.
def roots_from_code():
    import glob
    names = set()
    patterns = [r'System\.loadLibrary\("([^"]+)"\)',
                r'NativeLibrary\.loadOrThrow\("([^"]+)"\)',
                r'NativeLibrary\.load\("([^"]+)"\)']
    for d in ('app/src/main', 'backend/src/main'):
        for f in glob.glob(d + '/**/*.kt', recursive=True) + glob.glob(d + '/**/*.java', recursive=True):
            text = open(f, encoding='utf-8', errors='replace').read()
            for pat in patterns:
                names |= set(re.findall(pat, text))
    return sorted(f'lib{n}.so' for n in names)

ROOTS = [r for r in roots_from_code() if r in libs]
_missing = [r for r in roots_from_code() if r not in libs]
if not ROOTS:
    sys.exit('found no System.loadLibrary calls in source: check paths')
print(f"roots read from Kotlin source: {', '.join(ROOTS)}")
if _missing:
    print(f"  (source also requests, missing from jniLibs: {', '.join(_missing)})")

def closure(roots, with_dlopen):
    """Transitive closure. DT_NEEDED is mandatory at load time; dlopen only at runtime."""
    seen, stack, edges = set(), list(roots), {}
    while stack:
        l = stack.pop()
        if l in seen: continue
        seen.add(l)
        for so in needed[l]:
            f = resolve_file(so)
            if f and f not in seen:
                edges.setdefault(f, []).append((l, 'NEEDED'))
                stack.append(f)
        if with_dlopen and ('dlopen' in unds[l] or 'android_dlopen_ext' in unds[l]):
            for other in libs:
                if other != l and soname[other] in strings_map[l] and other not in seen:
                    edges.setdefault(other, []).append((l, 'dlopen'))
                    stack.append(other)
    return seen, edges

load_set, b1 = closure(ROOTS, False)
all_set,  b2 = closure(ROOTS, True)

def table(title, group, edges):
    print('=' * 94); print(title); print('=' * 94)
    s = 0
    for l in sorted(group, key=lambda x: -sizes[x]):
        s += sizes[l]
        if l in ROOTS: via = 'ROOT  System.loadLibrary from Kotlin'
        else:
            vs = edges.get(l, [])
            kinds = sorted({t for _, t in vs}); who = sorted({w for w, _ in vs})
            via = f"{'+'.join(kinds):<8} <- {', '.join(who[:2])}{' ...' if len(who) > 2 else ''}"
        print(f"  {l:<30}{sizes[l]/1048576:>7.2f} MB  {via}")
    print(f"  {'':<30}{'-'*10}")
    print(f"  {'TOTAL':<30}{s/1048576:>7.2f} MB   ({len(group)} libraries)")
    return s

print()
a = table('A) MINIMUM FOR APP STARTUP (DT_NEEDED closure, mandatory at load time)', load_set, b1)
print()
b = table('B) MINIMUM TO FUNCTION (A + libraries reached via dlopen at runtime)', all_set, b2)

unused = [l for l in libs if l not in all_set]
print()
print('=' * 94); print('C) UNREACHABLE FROM ANY ROOT  ->  candidates to drop today'); print('=' * 94)
if not unused:
    print('  none: all libraries are in the closure')
else:
    t = 0
    for l in sorted(unused, key=lambda x: -sizes[x]):
        t += sizes[l]
        in_needed = [o for o in libs if soname[l] in needed[o]]
        named_by  = [o for o in libs if o != l and soname[l] in strings_map[o]]
        print(f"  {l:<30}{sizes[l]/1048576:>7.2f} MB  NEEDED by: {', '.join(in_needed) or 'none'}"
              f" | named by: {', '.join(named_by) or 'none'}")
    print(f"  -> {t/1048576:.2f} MB")

print()
print('=' * 94); print('D) DELTA'); print('=' * 94)
solo_dlopen = sorted(todo - carga, key=lambda x: -tam[x])
print(f"  jniLibs completo            : {sum(tam.values())/1048576:>7.2f} MB  ({len(libs)} libs)")
print(f"  A) para arrancar            : {a/1048576:>7.2f} MB  ({len(carga)} libs)")
print(f"  B) para funcionar           : {b/1048576:>7.2f} MB  ({len(todo)} libs)")
print(f"  solo por dlopen (B - A)     : {sum(tam[l] for l in solo_dlopen)/1048576:>7.2f} MB  ({len(solo_dlopen)} libs)")
for l in solo_dlopen: print(f"      {l:<28}{tam[l]/1048576:>7.2f} MB")
