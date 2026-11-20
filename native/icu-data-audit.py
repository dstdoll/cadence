#!/usr/bin/env python3
"""Mide que hay dentro de libicudata_sv_apple.so y cuanto costaria recortarlo.

Son 9.75 MB, el segundo archivo mas grande del APK despues de libmedialibrarycore.so, y
solo exporta un simbolo: `icudt__sv_apple57_dat`, el paquete de datos de ICU 57. Un paquete
de ICU es una tabla de contenidos seguida de los items; el formato es estandar, asi que se
puede listar sin herramientas de ICU y saber exactamente que se paga por cada cosa.

No modifica nada: solo informa.

    python3 native/icu-data-audit.py [ruta-al-.so]
"""
import struct
import sys
from collections import defaultdict

RUTA = sys.argv[1] if len(sys.argv) > 1 else "backend/src/main/jniLibs/arm64-v8a/libicudata_sv_apple.so"
# Lo que la app necesita de verdad: el idioma que declara (en-US) y la tienda (cl).
LOCALES_UTILES = {"root", "en", "en_US", "es", "es_419"}


def paquete(raw: bytes) -> tuple[int, int]:
    """Offset del paquete y de su tabla de contenidos. La firma de UDataInfo es da 27."""
    i = raw.find(b"\xda\x27")
    if i < 0:
        sys.exit("no encontre la firma de un paquete de datos de ICU")
    off = i - 2
    tam_cabecera = struct.unpack_from("<H", raw, off)[0]
    return off, off + tam_cabecera


def items(raw: bytes, base: int) -> list[tuple[str, int]]:
    """(nombre, tamano) de cada item. El tamano sale de la distancia al siguiente."""
    cuantos = struct.unpack_from("<I", raw, base)[0]
    toc = [struct.unpack_from("<II", raw, base + 4 + i * 8) for i in range(cuantos)]
    fin_total = len(raw) - base

    def nombre(off: int) -> str:
        ini = base + off
        return raw[ini : raw.index(b"\0", ini)].decode("ascii", "replace")

    salida = []
    for i, (n_off, d_off) in enumerate(toc):
        fin = toc[i + 1][1] if i + 1 < cuantos else fin_total
        salida.append((nombre(n_off), max(0, fin - d_off)))
    return salida


def locale_de(nombre: str) -> str:
    return nombre.rsplit("/", 1)[-1].rsplit(".", 1)[0]


def main() -> None:
    raw = open(RUTA, "rb").read()
    _, base = paquete(raw)
    conjunto = items(raw, base)
    total = sum(t for _, t in conjunto)
    print(f"{RUTA}: {len(raw)/1048576:.2f} MB, {len(conjunto)} items, {total/1048576:.2f} MB de datos\n")

    por_tipo = defaultdict(lambda: [0, 0])
    for n, t in conjunto:
        ext = n.rsplit(".", 1)[-1] if "." in n else "(sin)"
        por_tipo[ext][0] += 1
        por_tipo[ext][1] += t
    print(f"{'tipo':<10}{'items':>7}{'MB':>9}   que es")
    QUE_ES = {
        "res": "recursos por locale: formatos, nombres, colacion",
        "dict": "diccionarios de corte de palabra (CJK, khmer, birmano, tailandes)",
        "brk": "reglas de corte de linea y palabra",
        "icu": "datos base de colacion",
        "nrm": "normalizacion Unicode",
        "cfu": "confusables (seguridad de IDN)",
    }
    for ext, (c, t) in sorted(por_tipo.items(), key=lambda kv: -kv[1][1]):
        print(f"  {ext:<8}{c:>7}{t/1048576:>9.2f}   {QUE_ES.get(ext, '')}")

    print("\nlos 10 items mas caros:")
    for n, t in sorted(conjunto, key=lambda x: -x[1])[:10]:
        print(f"  {n:<44}{t/1024:>9.1f} KB")

    print("\nescenarios de recorte (ninguno aplicado):")
    dicts = sum(t for n, t in conjunto if n.endswith(".dict"))
    cjk_res = sum(t for n, t in conjunto if n.endswith(".res") and locale_de(n) in {"zh", "ko", "ja"})
    solo_utiles = sum(
        t for n, t in conjunto if not n.endswith(".res") or locale_de(n) in LOCALES_UTILES
    )
    print(f"  sacar los .dict (CJK/khmer/birmano) : -{dicts/1048576:.2f} MB")
    print(f"  sacar colacion de zh/ko/ja          : -{cjk_res/1048576:.2f} MB")
    print(f"  dejar solo {'/'.join(sorted(LOCALES_UTILES))}")
    print(f"    en los .res                       : -{(total-solo_utiles)/1048576:.2f} MB")

    print(
        "\nCuidado: recortar esto no rompe el enlace, rompe el texto. Los .dict son lo que\n"
        "parte palabras en chino, japones, khmer y birmano, y los .res llevan la colacion con\n"
        "la que se ordenan los titulos. Apple Music tiene catalogo en esos idiomas, asi que el\n"
        "fallo no seria hipotetico: aparece el dia que suene un tema con titulo en japones.\n"
        "Cualquier recorte necesita una lista explicita de lo que se conserva y pruebas con\n"
        "contenido en esos idiomas, no solo que la app arranque."
    )


if __name__ == "__main__":
    main()
