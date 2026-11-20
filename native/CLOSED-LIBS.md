# Las librerías cerradas: qué pesan y qué haría falta para sacarlas

Los números de este documento los produce `./native/analyze-closed-libs.sh`. Si cambian las
librerías, se vuelve a correr; no se editan a mano.

## El resumen

De los 70 MB de `jniLibs/arm64-v8a`, tres librerías cerradas suman **17.45 MB** y la app las
alcanza a través de **11 funciones**. No 99.

| librería | tamaño | exports | nombrados | funciones reales |
|---|---|---|---|---|
| `libCoreLSKD.so` | 7.92 MB | 89 | 7 | 6 + `JNI_OnLoad` |
| `libCoreFP.so` | 7.82 MB | 7 | 6 | 5 + `JNI_OnLoad` |
| `libCoreADI.so` | 1.71 MB | 3 | 1 | solo `JNI_OnLoad` |

`libCoreLSKD.so` es el caso más llamativo: 89 exports de los que se nombran 7. Los otros 82
son superficie que nadie usa.

## Cómo se alcanzan (y por qué el dynsym engaña)

Ninguna de las tres deja **un solo import estático** en el dynsym de nadie. Mirando sólo eso,
el veredicto sería "nadie las usa, se pueden borrar" — y es falso: sacarlas rompe la
reproducción en runtime, no en el enlace.

La vía real es dinámica. `libandroidappmusic.so` lleva los nombres de archivo
(`libCoreFP.so`, `libCoreADI.so`, `libCoreLSKD.so`) y los nombres de las funciones como
**strings**, e importa `dlopen`/`dlsym`: abre cada librería por nombre de archivo y resuelve
las funciones por nombre. Eso no produce ninguna entrada de reubicación, así que es invisible
para cualquier análisis que sólo lea las tablas de símbolos.

Dos detalles que conviene no olvidar:

- **`libandroidappmusic.so` también nombra `JNI_OnLoad`**, es decir hace
  `dlsym(h, "JNI_OnLoad")` y la llama a mano. `dlopen` por sí solo no invoca `JNI_OnLoad`:
  eso lo hace el runtime de Java en `System.loadLibrary`, y del lado Kotlin lo único que se
  carga así es `cadence_auth_android`. Toda la cadena de Apple la arrastra
  `libandroidappmusic`.
- **`libCoreADI.so` no tiene ninguna de sus dos funciones nombrada**, sólo `JNI_OnLoad`. Se
  carga por su efecto de inicialización, no porque se le llame nada después. Su `JNI_OnLoad`
  está ofuscado: guarda el `JavaVM*` y despacha a una única llamada indirecta calculada, que
  lleva a `vdfut768ig` (una sola función de 874 KB con MBA y aplanado de control de flujo).
  Es la que aprovisiona el estado de máquina (`adi.pb`).

Los exports llevan sufijo de versión en algunos casos (`@@VERS_1.73.0` en CoreFP,
`@@LIBXML2_…` en libxml2). Comparar nombres sin normalizar ese sufijo hace que la librería
parezca no usarse. El script lo normaliza; cualquier análisis a mano tiene que hacerlo
también.

## Qué se puede sacar hoy

**Nada de las tres, sin reemplazar antes lo que hacen.** Son el núcleo de FairPlay
(CoreFP/CoreLSKD) y el aprovisionamiento (CoreADI). Un stub que exporte los nombres resuelve
el `dlsym` y deja cargar la app, pero la primera clave que se pida falla.

Lo que sí cambió con los ports es que la superficie está **medida y es chica**: 11 funciones.
Antes el número que se citaba era "las 11 librerías propietarias", que no dice nada sobre
qué habría que escribir.

## El orden en que tiene sentido atacarlo

1. **Medir el uso dinámico, no el estático.** De las 11, saber cuáles se llaman de verdad en
   una sesión de streaming. `cadence-hooks` ya hace hooking de PLT; un interposer que cuente
   llamadas durante una reproducción completa da esa lista. Puede que varias de las 6 de
   CoreLSKD sólo entren en el camino offline.
2. **CoreADI primero** (1.71 MB, un solo punto de entrada). Es el objetivo más chico y el de
   superficie más estrecha. Lo que hay que reproducir es el efecto de su `JNI_OnLoad` sobre
   `adi.pb`, no una API.
3. **CoreFP/CoreLSKD al final** (15.74 MB). Son whitebox AES: la clave está horneada en el
   código ofuscado, así que "portarlas" es extraer la clave, no reimplementar un algoritmo.
   Es el trabajo más grande de los tres y el que más conviene dejar quieto mientras el resto
   funcione.

## Dónde está el peso que no es de estas tres

Para que el 17.45 MB quede en perspectiva, en el APK de debug actual (153 MB):

| qué | tamaño | nota |
|---|---|---|
| `classes*.dex` | ~75 MB | build de debug sin R8; un release con minify lo baja mucho |
| `lib/arm64-v8a` | 70 MB | de los cuales 17.45 MB son estas tres |
| `libmedialibrarycore.so` | 13.21 MB | 614 símbolos usados de verdad: no es candidata |
| `libicudata_sv_apple.so` | 9.75 MB | un solo símbolo importado, pero es el blob de datos de ICU; se recorta regenerándolo con filtro, no stubeando |
| `assets/native-data` | 5.07 MB | datos de sesión para pruebas; `.gitignore` los deja fuera del repo y no deben ir en un release |

Es decir: el bulto mayor de un release no son las librerías cerradas, es el DEX. Conviene
medir un `assembleRelease` antes de operar nada nativo.
