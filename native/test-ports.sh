#!/usr/bin/env bash
# Corre los tests de los ports y comprueba que no puedan pasar en verde sin validar nada.
#
# Dos pasadas:
#   1. la suite normal, exigiendo la captura (CADENCE_VECTORS_REQUIRED=1);
#   2. una matriz adversarial: se rompe la captura a proposito y se verifica que los tests
#      FALLEN. Esa segunda pasada es el punto: antes cada tests/vectors.rs hacia
#      `let Some(x) = cargar() else { return }`, asi que sin captura daba verde.
#
# Usa native/vectors.json si esta (la captura real del dispositivo, gitignorada) y si no
# native/vectors.sample.json (la muestra sintetica versionada). Las dos tienen que pasar.
#
#   ./native/test-ports.sh
set -uo pipefail
cd "$(dirname "$0")"
PORTS=(apple-ss-sys device-guid fairplay-proto store-config vectors-fixture)
ok=0; mal=0

# Corre una suite y exige un resultado. Un error de COMPILACION tambien es un fallo: si solo
# se mira "FAILED" en la salida, un crate que no compila se lee como que paso.
corre() { # etiqueta, esperado(pasa|falla), directorio, [env...]
    local etiqueta="$1" esperado="$2" dir="$3"; shift 3
    local salida rc
    salida=$(cd "$dir" && env "$@" cargo test --quiet 2>&1); rc=$?
    local got; [ $rc -eq 0 ] && got=pasa || got=falla
    if [ "$got" = "$esperado" ]; then
        local n; n=$(printf '%s' "$salida" | grep -oE '[0-9]+ passed' | awk '{s+=$1} END {print s+0}')
        if [ "$esperado" = pasa ] && [ "$n" -eq 0 ]; then
            printf '  MAL  %-52s paso pero no corrio ningun test\n' "$etiqueta"; mal=$((mal+1)); return
        fi
        printf '  OK   %-52s %s%s\n' "$etiqueta" "$got" \
            "$([ "$esperado" = pasa ] && echo " ($n tests)")"
        ok=$((ok+1))
    else
        printf '  MAL  %-52s %s, se esperaba %s\n' "$etiqueta" "$got" "$esperado"
        printf '%s\n' "$salida" | grep -E 'error|panicked' | head -4 | sed 's/^/         /'
        mal=$((mal+1))
    fi
}

captura() { [ -f vectors.json ] && echo vectors.json || echo vectors.sample.json; }
echo "=== 1) suite de los ports (captura: $(captura)) ==="
for c in "${PORTS[@]}"; do corre "$c" pasa "$c" CADENCE_VECTORS_REQUIRED=1; done

echo
echo "=== 2) matriz adversarial: la captura rota tiene que hacer FALLAR ==="
GUARDA=$(mktemp -d); trap 'rm -rf "$GUARDA"' EXIT
for f in vectors.json vectors.sample.json; do [ -f "$f" ] && mv "$f" "$GUARDA/"; done

corre "sin ninguna captura + REQUIRED=1" falla device-guid CADENCE_VECTORS_REQUIRED=1
corre "sin ninguna captura, sin REQUIRED (se salta)" pasa device-guid CADENCE_VECTORS_REQUIRED=

echo '{ no soy json' > vectors.sample.json
corre "captura que no parsea" falla device-guid CADENCE_VECTORS_REQUIRED=1

echo '{"userAgent":"Music/5.0.2 Android/10 model/Pixel 8 build/7663314 (dt:66)"}' > vectors.sample.json
corre "falta ckcRequests -> fairplay" falla fairplay-proto CADENCE_VECTORS_REQUIRED=1
corre "falta deviceGuid -> device-guid" falla device-guid CADENCE_VECTORS_REQUIRED=1

echo '{"ckcRequests":[],"deviceGuid":{},"deviceGuidInputs":{},"userAgent":"x"}' > vectors.sample.json
corre "ckcRequests vacio (no hay nada que validar)" falla fairplay-proto CADENCE_VECTORS_REQUIRED=1

rm -f vectors.sample.json
for f in "$GUARDA"/*; do [ -e "$f" ] && mv "$f" .; done
echo
echo "=== 3) la captura restaurada vuelve a pasar ==="
corre "device-guid con $(captura)" pasa device-guid CADENCE_VECTORS_REQUIRED=1

echo
echo "RESULTADO: $ok correctas, $mal incorrectas"
[ "$mal" -eq 0 ]
