import os, re, subprocess, sys
BIN, LIBDIR = sys.argv[1], sys.argv[2]
libs = sorted(f for f in os.listdir(LIBDIR) if f.endswith('.so'))
def run(*a): return subprocess.run(list(a), capture_output=True, text=True).stdout
def norm(s): return s.split('@')[0]
INTERNOS = {'__bss_start','_edata','_end','_fini','_init'}

needed, soname, textos, tam, defs, unds = {}, {}, {}, {}, {}, {}
for l in libs:
    p = os.path.join(LIBDIR, l); tam[l] = os.path.getsize(p)
    d = run(BIN+'/llvm-readelf','-d',p)
    needed[l] = re.findall(r'NEEDED.*?\[(.*?)\]', d)
    so = re.findall(r'SONAME.*?\[(.*?)\]', d); soname[l] = so[0] if so else l
    textos[l] = set(subprocess.run(['strings','-a',p],capture_output=True,text=True).stdout.splitlines())
    defs[l] = {norm(x.split()[-1]) for x in run(BIN+'/llvm-nm','-D','--defined-only','--no-sort',p).splitlines() if len(x.split())>=2} - INTERNOS
    unds[l] = {norm(x.split()[-1]) for x in run(BIN+'/llvm-nm','-D','--undefined-only','--no-sort',p).splitlines() if len(x.split())>=2}

por_soname = {}
for l in libs: por_soname.setdefault(soname[l], l)
def archivo(so): return por_soname.get(so)

# Los roots: lo que el codigo Kotlin carga con System.loadLibrary. No se adivinan, se leen
# del codigo, para que la respuesta siga siendo cierta si manana se agrega o se saca uno.
def roots_del_codigo():
    import glob
    nombres = set()
    patrones = [r'System\.loadLibrary\("([^"]+)"\)',
                r'NativeLibrary\.loadOrThrow\("([^"]+)"\)',
                r'NativeLibrary\.load\("([^"]+)"\)']
    for d in ('app/src/main', 'backend/src/main'):
        for f in glob.glob(d + '/**/*.kt', recursive=True) + glob.glob(d + '/**/*.java', recursive=True):
            texto = open(f, encoding='utf-8', errors='replace').read()
            for pat in patrones:
                nombres |= set(re.findall(pat, texto))
    return sorted(f'lib{n}.so' for n in nombres)

ROOTS = [r for r in roots_del_codigo() if r in libs]
_faltan = [r for r in roots_del_codigo() if r not in libs]
if not ROOTS:
    sys.exit('no encontre ningun System.loadLibrary en el codigo: revisa las rutas')
print(f"roots leidos del codigo Kotlin: {', '.join(ROOTS)}")
if _faltan:
    print(f"  (el codigo tambien pide, y no estan en jniLibs: {', '.join(_faltan)})")

def cierre(roots, con_dlopen):
    """Cierre transitivo. DT_NEEDED es obligatorio al cargar; dlopen solo en runtime."""
    vistos, pila, borde = set(), list(roots), {}
    while pila:
        l = pila.pop()
        if l in vistos: continue
        vistos.add(l)
        for so in needed[l]:
            f = archivo(so)
            if f and f not in vistos:
                borde.setdefault(f, []).append((l, 'NEEDED'))
                pila.append(f)
        if con_dlopen and ('dlopen' in unds[l] or 'android_dlopen_ext' in unds[l]):
            for otra in libs:
                if otra != l and soname[otra] in textos[l] and otra not in vistos:
                    borde.setdefault(otra, []).append((l, 'dlopen'))
                    pila.append(otra)
    return vistos, borde

carga, b1 = cierre(ROOTS, False)
todo,  b2 = cierre(ROOTS, True)

def tabla(titulo, conj, borde):
    print('=' * 94); print(titulo); print('=' * 94)
    s = 0
    for l in sorted(conj, key=lambda x: -tam[x]):
        s += tam[l]
        if l in ROOTS: via = 'ROOT  System.loadLibrary desde Kotlin'
        else:
            vs = borde.get(l, [])
            tipos = sorted({t for _, t in vs}); quien = sorted({w for w, _ in vs})
            via = f"{'+'.join(tipos):<8} <- {', '.join(quien[:2])}{' ...' if len(quien) > 2 else ''}"
        print(f"  {l:<30}{tam[l]/1048576:>7.2f} MB  {via}")
    print(f"  {'':<30}{'-'*10}")
    print(f"  {'TOTAL':<30}{s/1048576:>7.2f} MB   ({len(conj)} librerias)")
    return s

print()
a = tabla('A) MINIMO PARA QUE LA APP ARRANQUE (cierre de DT_NEEDED, obligatorio al cargar)', carga, b1)
print()
b = tabla('B) MINIMO PARA QUE FUNCIONE (A + lo que se alcanza por dlopen en runtime)', todo, b2)

sobran = [l for l in libs if l not in todo]
print()
print('=' * 94); print('C) NO ALCANZABLES DESDE NINGUN ROOT  ->  candidatas a salir hoy'); print('=' * 94)
if not sobran:
    print('  ninguna: todas estan en el cierre')
else:
    t = 0
    for l in sorted(sobran, key=lambda x: -tam[x]):
        t += tam[l]
        en_needed = [o for o in libs if soname[l] in needed[o]]
        nombrada  = [o for o in libs if o != l and soname[l] in textos[o]]
        print(f"  {l:<30}{tam[l]/1048576:>7.2f} MB  NEEDED de: {', '.join(en_needed) or 'nadie'}"
              f" | nombrada por: {', '.join(nombrada) or 'nadie'}")
    print(f"  -> {t/1048576:.2f} MB")

print()
print('=' * 94); print('D) DELTA'); print('=' * 94)
solo_dlopen = sorted(todo - carga, key=lambda x: -tam[x])
print(f"  jniLibs completo            : {sum(tam.values())/1048576:>7.2f} MB  ({len(libs)} libs)")
print(f"  A) para arrancar            : {a/1048576:>7.2f} MB  ({len(carga)} libs)")
print(f"  B) para funcionar           : {b/1048576:>7.2f} MB  ({len(todo)} libs)")
print(f"  solo por dlopen (B - A)     : {sum(tam[l] for l in solo_dlopen)/1048576:>7.2f} MB  ({len(solo_dlopen)} libs)")
for l in solo_dlopen: print(f"      {l:<28}{tam[l]/1048576:>7.2f} MB")
