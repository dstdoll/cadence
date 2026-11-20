#!/usr/bin/env bash
# ¿Cual es el minimo de librerias nativas que la app necesita?
#
# Parte de los **roots reales**: las que el codigo Kotlin carga con System.loadLibrary, que
# son tres (NativeAuth.kt, NativeDrm.kt, NativeWrapper/NativeAlac/NativeEac3.kt). Desde ahi
# calcula el cierre transitivo por dos tipos de arista:
#
#   NEEDED  - DT_NEEDED. Es **obligatoria al cargar**: bionic exige que todas esten
#             presentes cuando se hace dlopen, use o no un simbolo de ellas. Si falta una,
#             System.loadLibrary falla y la app no arranca.
#   dlopen  - la libreria nombra el archivo de la otra y usa dlopen/dlsym. Solo hace falta
#             en runtime, cuando se pide la funcionalidad.
#
# La diferencia entre los dos cierres es lo que se podria sacar sin impedir el arranque.
#
#   ./native/minimal-libs.sh
set -euo pipefail
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK no encontrado (pone ANDROID_NDK_HOME)" >&2; exit 1; }
exec python3 -I "$(dirname "$0")/minimal-libs.py" \
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" \
    "backend/src/main/jniLibs/arm64-v8a"
