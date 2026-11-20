#!/usr/bin/env bash
# Mide la superficie real de las librerias cerradas de Apple que la app embarca.
#
# La pregunta que responde es "¿que habria que implementar para poder sacar esta libreria?",
# y la respuesta no es "todos sus exports": casi ninguno se usa. Se mide de dos formas,
# porque las dos juntas son las que dan el numero:
#
#   1. Imports estaticos: que simbolos de cada libreria referencia otra en su dynsym. Los
#      nombres se normalizan quitando el sufijo `@@VERSION`; sin eso, una libreria con
#      simbolos versionados (libxml2, libCoreFP) parece no usarse cuando si se usa.
#   2. Resolucion dinamica: que exports aparecen como **string** en otra libreria. Es como
#      libandroidappmusic alcanza a CoreFP/CoreLSKD/CoreADI: dlopen por nombre de archivo y
#      dlsym por nombre de funcion, sin dejar ni un import estatico. Mirar solo el dynsym
#      da el veredicto equivocado ("nadie las usa, se pueden borrar") y rompe el playback.
#
# Necesita el NDK (ANDROID_NDK_HOME, o el mas nuevo bajo ANDROID_HOME).
#
#   ./native/analyze-closed-libs.sh
set -euo pipefail
cd "$(dirname "$0")/.."
LIBS="backend/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK no encontrado (pone ANDROID_NDK_HOME)" >&2; exit 1; }
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

python3 -I - "$BIN" "$LIBS" <<'PY'
import os, re, subprocess, sys
BIN, LIBDIR = sys.argv[1], sys.argv[2]
libs = sorted(f for f in os.listdir(LIBDIR) if f.endswith('.so'))
def run(*a): return subprocess.run(list(a), capture_output=True, text=True).stdout
def norm(s): return s.split('@')[0]
INTERNOS = {'__bss_start', '_edata', '_end', '_fini', '_init'}

definidos, indefinidos, needed, soname, textos, tam = {}, {}, {}, {}, {}, {}
for l in libs:
    p = os.path.join(LIBDIR, l)
    tam[l] = os.path.getsize(p)
    definidos[l] = {norm(x.split()[-1]) for x in
        run(BIN+'/llvm-nm','-D','--defined-only','--no-sort',p).splitlines() if len(x.split()) >= 2} - INTERNOS
    indefinidos[l] = {norm(x.split()[-1]) for x in
        run(BIN+'/llvm-nm','-D','--undefined-only','--no-sort',p).splitlines() if len(x.split()) >= 2}
    d = run(BIN+'/llvm-readelf','-d',p)
    needed[l] = re.findall(r'NEEDED.*?\[(.*?)\]', d)
    so = re.findall(r'SONAME.*?\[(.*?)\]', d); soname[l] = so[0] if so else l
    textos[l] = set(subprocess.run(['strings','-a',p], capture_output=True, text=True).stdout.splitlines())

print('=' * 96)
print('SUPERFICIE REAL DE CADA LIBRERIA')
print('=' * 96)
print(f"{'libreria':<30}{'MB':>7}{'exports':>9}{'usados':>8}{'din':>6}  como se alcanza")
print('-' * 96)
filas = []
for l in sorted(libs, key=lambda x: -tam[x]):
    estaticos = {s for otra in libs if otra != l for s in (indefinidos[otra] & definidos[l])}
    dinamicos = {s for s in definidos[l] if any(s in textos[o] for o in libs if o != l)}
    por_nombre = [o for o in libs if o != l and soname[l] in textos[o]]
    en_needed = [o for o in libs if soname[l] in needed[o]]
    como = []
    if estaticos: como.append(f"enlace estatico desde {len(set(o for o in libs if o != l and indefinidos[o] & definidos[l]))}")
    if por_nombre: como.append("dlopen por nombre: " + ", ".join(por_nombre))
    if not como and en_needed: como.append(f"solo NEEDED de {', '.join(en_needed)}")
    if not como: como.append("nadie la referencia (la carga el runtime)")
    print(f"{l:<30}{tam[l]/1048576:>7.2f}{len(definidos[l]):>9}{len(estaticos):>8}"
          f"{len(dinamicos):>6}  {'; '.join(como)}")
    filas.append((l, tam[l], definidos[l], estaticos, dinamicos, por_nombre))

print()
print('=' * 96)
print('LAS QUE NO TIENEN NI UN IMPORT ESTATICO: cuanto pesan y por donde entran')
print('=' * 96)
total = 0
for l, t, defs, est, din, nombre in filas:
    if est: continue
    total += t
    print(f"\n{l}  ({t/1048576:.2f} MB, {len(defs)} exports)")
    if nombre:
        print(f"  la nombra por archivo: {', '.join(nombre)}")
        usados = sorted(s for s in defs if any(s in textos[o] for o in nombre))
        print(f"  exports que esa libreria nombra: {len(usados)} de {len(defs)}")
        for s in usados: print(f"      {s}")
        if not usados:
            print("      ninguno -> se carga por su efecto de carga (JNI_OnLoad), no por sus funciones")
    else:
        print("  nadie la nombra: la carga el runtime (System.loadLibrary) o es nuestra")
print(f"\nsuma de las que no tienen imports estaticos: {total/1048576:.2f} MB")
print("""
Leer con cuidado: "sin imports estaticos" NO es "se puede borrar". Una libreria alcanzada
por dlopen+dlsym no deja rastro en el dynsym de nadie, y sacarla rompe el playback en
runtime, no en el enlace. El numero que importa para poder sacarla es la lista de exports
nombrados de arriba: eso es lo que habria que implementar.""")
PY
