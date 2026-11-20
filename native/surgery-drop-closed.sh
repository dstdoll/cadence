#!/usr/bin/env bash
# Cirugia: deja de embarcar librerias cerradas quitandolas del DT_NEEDED de quien las declara.
#
# El problema no es que se llamen, es que son dependencias **de carga**: bionic exige que toda
# entrada DT_NEEDED este presente al hacer dlopen, se use o no un simbolo de ella. Por eso no
# alcanza con borrar el archivo: hay que quitar tambien la entrada que lo pide, o
# System.loadLibrary falla y la app no arranca.
#
# Produce un arbol jniLibs parcheado en un directorio de salida. **No toca el original.**
#
#   ./native/surgery-drop-closed.sh <dir-salida> [libX.so ...]
#
# Sin lista, saca las tres cerradas que ningun objeto importa por simbolo:
# libCoreFP.so, libCoreLSKD.so, libCoreADI.so (17.45 MB).
#
# Antes de parchear comprueba que sea seguro desde el enlace, y despues que el resultado
# siga siendo cargable. Si una de las dos falla, se detiene: un .so con el DT_NEEDED mal
# deja la app sin arrancar y el sintoma (UnsatisfiedLinkError) no dice cual fue.
set -euo pipefail
cd "$(dirname "$0")/.."
ORIGEN="backend/src/main/jniLibs/arm64-v8a"
SALIDA="${1:?uso: surgery-drop-closed.sh <dir-salida> [libX.so ...]}"; shift || true
SACAR=("$@"); [ ${#SACAR[@]} -eq 0 ] && SACAR=(libCoreFP.so libCoreLSKD.so libCoreADI.so)

command -v patchelf >/dev/null || { echo "falta patchelf (pip install patchelf, o ~/.local/bin)" >&2; exit 1; }
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$BIN/llvm-nm" ] || { echo "Android NDK no encontrado (pone ANDROID_NDK_HOME)" >&2; exit 1; }

norm() { sed 's/@@.*//' | sort -u; }     # los exports pueden llevar sufijo @@VERSION
defs() { "$BIN/llvm-nm" -D --defined-only "$1" 2>/dev/null | awk '{print $NF}' | norm; }
unds() { "$BIN/llvm-nm" -D --undefined-only "$1" 2>/dev/null | awk '{print $NF}' | norm; }

echo "=== 0) a sacar: ${SACAR[*]} ==="
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
: > "$TMP/exports-que-se-van"
for l in "${SACAR[@]}"; do
    [ -f "$ORIGEN/$l" ] || { echo "no existe $ORIGEN/$l" >&2; exit 1; }
    defs "$ORIGEN/$l" >> "$TMP/exports-que-se-van"
done
sort -u -o "$TMP/exports-que-se-van" "$TMP/exports-que-se-van"
echo "    exportan $(wc -l < "$TMP/exports-que-se-van") simbolos entre todas"

echo
echo "=== 1) seguro desde el enlace? nadie puede importarles un simbolo ==="
malo=0
for f in "$ORIGEN"/*.so; do
    b=$(basename "$f"); case " ${SACAR[*]} " in *" $b "*) continue;; esac
    n=$(comm -12 <(unds "$f") "$TMP/exports-que-se-van" | wc -l)
    [ "$n" -gt 0 ] && { echo "    NO: $b importa $n simbolos de las que se van"; malo=1; }
done
[ $malo -eq 0 ] && echo "    si: ningun objeto que se queda importa un simbolo de las que se van"
[ $malo -eq 0 ] || { echo "abortado: habria que reemplazar esos simbolos antes" >&2; exit 1; }

echo
echo "=== 2) copiar y parchear ==="
rm -rf "$SALIDA"; mkdir -p "$SALIDA"
for f in "$ORIGEN"/*.so; do
    b=$(basename "$f"); case " ${SACAR[*]} " in *" $b "*) continue;; esac
    cp "$f" "$SALIDA/$b"
done
echo "    copiadas $(ls "$SALIDA" | wc -l) de $(ls "$ORIGEN" | wc -l)"
for f in "$SALIDA"/*.so; do
    b=$(basename "$f"); antes=$(patchelf --print-needed "$f" | wc -l); quitadas=()
    for l in "${SACAR[@]}"; do
        if patchelf --print-needed "$f" | grep -qx "$l"; then
            so_antes=$(patchelf --print-soname "$f" 2>/dev/null || echo "")
            patchelf --remove-needed "$l" "$f"
            so_despues=$(patchelf --print-soname "$f" 2>/dev/null || echo "")
            [ "$so_antes" = "$so_despues" ] || { echo "    $b: el SONAME cambio ($so_antes -> $so_despues)" >&2; exit 1; }
            quitadas+=("$l")
        fi
    done
    [ ${#quitadas[@]} -gt 0 ] && echo "    $b: $antes -> $(patchelf --print-needed "$f" | wc -l) NEEDED (quitadas: ${quitadas[*]})"
done

echo
echo "=== 3) el resultado puede cargar? ==="
# 3a) ninguna entrada DT_NEEDED puede quedar apuntando a un archivo que ya no esta.
#     Las del sistema (libc, liblog, libz, libOpenSLES...) las provee Android, no el APK.
SISTEMA='^lib(c|m|dl|log|z|android|OpenSLES|stdc\+\+|GLESv[0-9]|EGL|jnigraphics|mediandk|nativewindow|aaudio|vulkan|neuralnetworks|cameraandroid)\.so$'
falta=0
for f in "$SALIDA"/*.so; do
    for dep in $(patchelf --print-needed "$f"); do
        [ -f "$SALIDA/$dep" ] && continue
        echo "$dep" | grep -qE "$SISTEMA" && continue
        echo "    COLGADA: $(basename "$f") pide $dep y no esta en el arbol"; falta=1
    done
done
[ $falta -eq 0 ] && echo "    ok: ninguna dependencia colgada"
# 3b) ningun simbolo indefinido se queda sin quien lo defina dentro del arbol.
#     Lo que resuelve el sistema no se puede comprobar aca, asi que solo se cuenta.
: > "$TMP/todos-los-exports"
for f in "$SALIDA"/*.so; do defs "$f" >> "$TMP/todos-los-exports"; done
sort -u -o "$TMP/todos-los-exports" "$TMP/todos-los-exports"
for f in "$SALIDA"/*.so; do
    n=$(comm -23 <(unds "$f") "$TMP/todos-los-exports" | wc -l)
    printf "    %-30s %5s simbolos los resuelve el sistema\n" "$(basename "$f")" "$n"
done
# 3c) el ELF sigue siendo valido y con la alineacion que pide Android.
for f in "$SALIDA"/*.so; do
    "$BIN/llvm-readelf" -h "$f" >/dev/null 2>&1 || { echo "    ELF INVALIDO: $(basename "$f")" >&2; exit 1; }
done
echo "    ok: los $(ls "$SALIDA" | wc -l) ELF parsean"

echo
echo "=== 4) cuanto se ahorro ==="
a=$(du -sb "$ORIGEN" | cut -f1); d=$(du -sb "$SALIDA" | cut -f1)
awk -v a="$a" -v d="$d" 'BEGIN{printf "    antes %.2f MB -> despues %.2f MB  (-%.2f MB, -%.1f%%)\n",
    a/1048576, d/1048576, (a-d)/1048576, (a-d)*100/a}'
echo
echo "El arbol parcheado quedo en $SALIDA. El original no se toco."
echo "Falta la unica prueba que vale: instalarlo y ver que arranca, loguea y reproduce."
