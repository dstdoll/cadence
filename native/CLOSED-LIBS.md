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

## Cuál es el mínimo de librerías, hoy

`./native/minimal-libs.sh` lo calcula desde los roots reales: lo que el código Kotlin carga
con `System.loadLibrary`, que son tres (`cadence_auth_android`, `cadence_android_drm`,
`cadence-native`).

**El mínimo son las 21, los 70.18 MB completos. Ninguna sale sin cirugía, ni una.**

El motivo es `DT_NEEDED`, no las llamadas. Bionic exige que **todas** las entradas
`DT_NEEDED` estén presentes al hacer `dlopen`, se use o no un símbolo de ellas; si falta una,
`System.loadLibrary` falla y la app no arranca. Y la cadena desde nuestro root no deja nada
afuera:

```
libcadence-native.so  (root, NativeWrapper/NativeAlac/NativeEac3)
  -> libandroidappmusic.so        23 entradas NEEDED, 15 de ellas en el APK
       -> libCoreFP.so  libCoreADI.so  libCoreLSKD.so
       -> libmedialibrarycore.so  libstoreservicescore.so  libdaapkit.so
       -> libCoreFoundation.so  libdispatch.so -> libBlocksRuntime.so
       -> libicuuc  libicui18n  libicudata  libxml2  libcurl -> libstdc++.so
  -> libmediaplatform.so  libstoreservicescore.so  libc++_shared.so
```

El cierre por `dlopen` **no agrega ni una librería** sobre el de `DT_NEEDED` (B − A = 0 MB).
Es decir: las tres cerradas no están ahí "porque alguien las abre en runtime", están ahí
porque son dependencias duras de carga. Mirar sólo la vía `dlopen` subestima el problema.

## Qué se puede sacar hoy

**Nada de las tres, sin reemplazar antes lo que hacen.** Son el núcleo de FairPlay
(CoreFP/CoreLSKD) y el aprovisionamiento (CoreADI). Un stub que exporte los nombres resuelve
el `dlsym` y deja cargar la app, pero la primera clave que se pida falla.

Lo que sí hay es un **único punto de estrangulamiento**: `libandroidappmusic.so` es la única
librería que declara las tres en su `DT_NEEDED`. Para que dejen de ser obligatorias al cargar
hay que parchear ese archivo y nada más: quitar tres entradas de su `DT_NEEDED`. Es seguro
desde el punto de vista del enlace, porque ninguna librería importa un solo símbolo estático
de las tres (verificado normalizando los sufijos `@@VERSION`). Y no deja nada huérfano: las
tres sólo dependen de librerías del sistema (`liblog`, `libdl`, `libc`, `libz`, `libm`) más
`libstdc++.so`, que `libcurl` también necesita.

| escenario | libs | jniLibs | qué se pierde |
|---|---|---|---|
| hoy, sin tocar nada | 21 | 70.18 MB | — |
| parchear el `DT_NEEDED` y sacar sólo CoreADI | 20 | 68.47 MB | aprovisionamiento de `adi.pb` |
| parchear el `DT_NEEDED` y sacar las tres | 18 | 52.73 MB | reproducción: la primera clave falla |

## El experimento, ya corrido

`./native/surgery-drop-closed.sh` quita las tres del `DT_NEEDED` de `libandroidappmusic.so`
con `patchelf` y produce un árbol `jniLibs` sin ellas. El parche es quirúrgico: el archivo
queda del **mismo tamaño** (patchelf corre las entradas en sitio), con la cabecera ELF y los
program headers idénticos, y `.dynamic` en el mismo offset con 48 → 45 entradas.

Se instaló en una tablet Android 10 (API 29) y esto es lo que pasó:

| | con las 3 | sin las 3 |
|---|---|---|
| APK | 152.61 MB | **135.14 MB** (−17.47 MB, −11.4%) |
| arranca | sí | **sí**, sin `UnsatisfiedLinkError` ni fallo del linker |
| sesión / login | sí | **sí** |
| biblioteca, catálogo, letras, carátulas | sí | **sí** (`me/library/songs -> 200`, `syllable-lyrics -> 200`) |
| reproducción | sí | **no**: `Key exchange for e1 failed` |

O sea: las tres librerías compran **exactamente una cosa**, la reproducción, y cuestan
17.47 MB. Todo lo demás de la app funciona sin ellas. El fallo es limpio y localizado — el
intercambio de clave —, no un cuelgue ni un crash.

### Y una por una

Sacarlas juntas no dice cuál pesa. Se repitió con cada una por separado:

| sin… | APK | arranca | reproduce |
|---|---|---|---|
| `libCoreADI.so` | 150.89 MB | sí | **no**: 0 claves, 13 fallos de intercambio |
| `libCoreLSKD.so` | 144.69 MB | sí | **no**: `Key exchange for e1 failed` |
| `libCoreFP.so` | 144.78 MB | **no**: el proceso muere al arrancar | — |

**Ninguna de las tres se puede sacar.** El mínimo sigue siendo las 21 librerías, 70.18 MB.

`libCoreFP.so` es el caso más duro: no es que falle la reproducción, es que la app no
arranca. `libstoreservicescore.so` también nombra ese archivo, no sólo
`libandroidappmusic.so`, así que lo abre durante la inicialización y el fallo es fatal.

`libCoreADI.so` merece una nota porque casi se cuela como removible. En la primera pasada
pareció reproducir: el detector vio `state=PlaybackState {state=3}` y lo dio por bueno. Pero
estaba reproduciendo con una **clave que quedó en caché** de la sesión anterior, y se cortaba a
mitad de tema. Repetido desde un arranque limpio: 0 claves obtenidas y 13 fallos. O sea que
CoreADI no sólo aprovisiona `adi.pb` la primera vez — participa en cada intercambio de clave,
que es coherente con que aporte los datos de máquina que autentican la petición al key
server.

**La trampa de medición, para no repetirla:** que empiece a sonar justo después de instalar
no prueba nada. Hay que arrancar limpio, reproducir, y **saltar de tema** para forzar una
clave nueva sin caché posible. Medir `state=3` a secas es la versión de reproducción del
mismo falso-verde que tenían los tests de vectores.

Dos consecuencias prácticas:

1. **El parche de `DT_NEEDED` está validado.** No hay que adivinar si bionic acepta el
   binario operado: acepta, y la app arranca sin CoreADI y sin CoreLSKD. Lo que falta para
   poder sacarlas de verdad es reemplazar el intercambio de clave, no resolver un problema
   de enlace. Con CoreFP ni siquiera eso: habría que cubrir además lo que
   `libstoreservicescore` le pide al inicializar.
2. **Hay espacio para un modo degradado** (explorar la biblioteca sin reproducir) sin
   embarcar 17.47 MB, si alguna vez hiciera falta. Es lo único que la medición habilita hoy.

### Reproducir el experimento

```sh
./native/surgery-drop-closed.sh /tmp/jnilibs-sin-cerradas
cp /tmp/jnilibs-sin-cerradas/*.so backend/src/main/jniLibs/arm64-v8a/   # sobre una copia
rm -f app/build/outputs/apk/debug/app-debug.apk   # si no, AGP reempaqueta dejando huecos
./gradlew :app:assembleDebug
git checkout -- backend/src/main/jniLibs/arm64-v8a                      # restaurar
```

El `rm` del APK no es opcional: el empaquetado incremental de AGP reescribe el zip dejando
los bytes de las entradas borradas como relleno no referenciado, y el archivo sale del mismo
tamaño aunque el contenido sea 17 MB menor. Medido: 160 562 499 contra 160 562 280 bytes,
219 bytes de diferencia por 17.45 MB de librerías sacadas.

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
| `libicudata_sv_apple.so` | 9.75 MB | el blob de datos de ICU 57; se recorta, no se stubea — ver abajo |
| `assets/native-data` | 5.07 MB | datos de sesión para pruebas; `.gitignore` los deja fuera del repo y no deben ir en un release |

Es decir: el bulto mayor de un release no son las librerías cerradas, es el DEX. Conviene
medir un `assembleRelease` antes de operar nada nativo.

## Lo otro que pesa: los datos de ICU

`./native/icu-data-audit.py` lista lo que hay dentro de `libicudata_sv_apple.so` sin
modificar nada. Es el segundo archivo más grande del APK y exporta un solo símbolo,
`icudt__sv_apple57_dat`: un paquete de datos de ICU 57, cuyo formato es estándar y se puede
listar sin las herramientas de ICU.

9.73 MB de datos en 883 items:

| tipo | items | MB | qué es |
|---|---|---|---|
| `.res` | 858 | 4.78 | recursos por locale: formatos, nombres, colación |
| `.dict` | 5 | 2.75 | diccionarios de corte de palabra (CJK, khmer, birmano, tailandés) |
| `.brk` | 14 | 1.46 | reglas de corte de línea y palabra |
| `.icu` | 2 | 0.52 | datos base de colación |
| `.nrm` | 3 | 0.16 | normalización Unicode |

Un solo item, `brkitr/cjdict.dict`, pesa **1.96 MB**. Los tres escenarios de recorte que
mide la herramienta: sacar los `.dict` ahorra 2.75 MB, sacar la colación de zh/ko/ja ahorra
1.25 MB, y dejar sólo `root/en/en_US/es/es_419` en los `.res` ahorra 3.81 MB.

**Por qué no está aplicado.** A diferencia de las librerías cerradas, recortar esto no
rompe el enlace: rompe el texto, y tarde. Los `.dict` son lo que parte palabras en chino y
japonés, y los `.res` llevan la colación con la que se ordenan los títulos. Apple Music
tiene catálogo en esos idiomas, así que el fallo no es hipotético — aparece el día que
suene un tema con título en japonés, no el día del cambio. Hacerlo bien pide una lista
explícita de lo que se conserva y pruebas con contenido en esos idiomas, no sólo comprobar
que la app arranca.

Es, eso sí, el recorte **más viable** de los que hay: no depende de reimplementar
criptografía, sólo de decidir qué idiomas se soportan.
