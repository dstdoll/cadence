//! Acceso a `vectors.json` para los tests de los ports.
//!
//! `vectors.json` es la captura de referencia tomada del dispositivo: contra ella se
//! valida que cada port reproduzca byte a byte lo que hacen las librerias cerradas.
//!
//! El punto de este crate es que **un test no pase en verde por no haber mirado nada**.
//! Antes cada `tests/vectors.rs` hacia `let Some(x) = cargar() else { return }`, asi que
//! un `vectors.json` ausente, roto o sin la seccion que el test necesita daba verde. Las
//! tres situaciones son distintas y aca se tratan distinto:
//!
//! | situacion                                   | resultado        |
//! |---------------------------------------------|------------------|
//! | ninguno de los dos archivos existe          | se salta (aviso) |
//! | no existen y se exigio por entorno          | falla            |
//! | existe pero no parsea                       | falla            |
//! | existe y falta la seccion pedida            | falla            |
//!
//! Se busca primero `vectors.json` (la captura real, local y gitignorada) y si no esta se
//! usa `vectors.sample.json` (la muestra sintetica, versionada). Asi el PR trae tests que
//! validan de verdad sin llevarse el ANDROID_ID del dispositivo.
//!
//! Exigir el archivo: `CADENCE_VECTORS_REQUIRED=1 cargo test`. Es lo que corre CI, para
//! que un checkout sin la captura no se lea como "todo bien".

use std::path::{Path, PathBuf};

/// Variable que vuelve obligatoria la captura (CI la pone en 1).
pub const ENV_REQUIRED: &str = "CADENCE_VECTORS_REQUIRED";

/// Variable que apunta a la captura directamente, sin deducirla del crate.
///
/// La ruta normal sale de `CARGO_MANIFEST_DIR`, que se fija **al compilar** y es una ruta de la
/// maquina que compilo. Un binario de tests cruzado para Android y corrido en el dispositivo no
/// la tiene, asi que ahi la captura se le indica por esta variable. Si esta puesta manda sobre
/// todo lo demas, y si apunta a algo que no existe es un fallo: quien la puso queria ese archivo.
pub const ENV_FILE: &str = "CADENCE_VECTORS_FILE";

/// La captura real del dispositivo. Gitignorada: trae su ANDROID_ID y su GUID.
const REAL: &str = "../vectors.json";
/// La muestra sintetica que sí va al repositorio. La genera
/// `cargo run --example muestra-vectores` en `native/device-guid`; es consistente con su
/// propio ANDROID_ID inventado, asi que los tests validan el algoritmo igual.
const MUESTRA: &str = "../vectors.sample.json";

fn required() -> bool {
    std::env::var(ENV_REQUIRED).map(|v| v != "0" && !v.is_empty()).unwrap_or(false)
}

/// La captura completa, o `None` si no esta (y no se exigio).
///
/// # Panics
/// Si el archivo existe y no parsea como JSON, o si no existe y `CADENCE_VECTORS_REQUIRED`
/// esta puesta. Un archivo corrupto es un fallo, no un test que se salta.
pub fn load(manifest_dir: &str) -> Option<serde_json::Value> {
    load_exigiendo(manifest_dir, required())
}

/// Igual que [`load`] pero con el "¿es obligatoria la captura?" explicito, en vez de leerlo
/// del entorno.
///
/// Existe para que los propios tests de este crate puedan comprobar las dos ramas sin
/// depender de si `CADENCE_VECTORS_REQUIRED` esta puesta en la shell que los corre (que es
/// justo lo que hace `test-ports.sh`).
pub fn load_exigiendo(manifest_dir: &str, exigir: bool) -> Option<serde_json::Value> {
    let forzada = std::env::var_os(ENV_FILE).map(PathBuf::from);
    load_desde(manifest_dir, exigir, forzada)
}

/// Que archivo se va a leer: el indicado a mano si lo hay, si no la captura real, si no la muestra.
pub fn ruta(manifest_dir: &str, forzada: Option<PathBuf>) -> PathBuf {
    if let Some(p) = forzada {
        return p;
    }
    let real = Path::new(manifest_dir).join(REAL);
    if real.exists() { real } else { Path::new(manifest_dir).join(MUESTRA) }
}

/// [`load_exigiendo`] con la ruta forzada explicita, para poder probarla sin tocar el entorno
/// del proceso (que comparten todos los tests que corren en paralelo).
pub fn load_desde(manifest_dir: &str, exigir: bool, forzada: Option<PathBuf>) -> Option<serde_json::Value> {
    // Un archivo indicado a mano se exige siempre: si no esta, es un fallo y no un salto.
    let exigir = exigir || forzada.is_some();
    let path = ruta(manifest_dir, forzada);
    match std::fs::read_to_string(&path) {
        Ok(texto) => Some(serde_json::from_str(&texto).unwrap_or_else(|e| {
            panic!("vectors.json existe en {} pero no parsea: {e}", path.display())
        })),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            assert!(
                !exigir,
                "{ENV_REQUIRED} esta puesta y no hay ni {REAL} ni {MUESTRA} junto a {}",
                manifest_dir
            );
            eprintln!("aviso: sin {} -> test salteado", path.display());
            None
        }
        Err(e) => panic!("no se pudo leer {}: {e}", path.display()),
    }
}

/// Una seccion de la captura. Si el archivo esta, la seccion **tiene** que estar.
///
/// # Panics
/// Si la captura existe y no trae `clave`: es justo el caso que antes daba verde.
pub fn section(manifest_dir: &str, clave: &str) -> Option<serde_json::Value> {
    let v = load(manifest_dir)?;
    let s = v.get(clave).unwrap_or_else(|| {
        panic!(
            "vectors.json no trae la seccion {clave:?} (tiene: {})",
            v.as_object()
                .map(|o| o.keys().cloned().collect::<Vec<_>>().join(", "))
                .unwrap_or_else(|| "no es un objeto".into())
        )
    });
    assert!(!s.is_null(), "vectors.json trae {clave:?} en null");
    Some(s.clone())
}

/// Una seccion que es string (el caso de `userAgent`).
pub fn string_section(manifest_dir: &str, clave: &str) -> Option<String> {
    let s = section(manifest_dir, clave)?;
    Some(
        s.as_str()
            .unwrap_or_else(|| panic!("vectors.json: {clave:?} deberia ser string, es {s}"))
            .to_owned(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    /// Un directorio temporal con el archivo de captura que se le pida, y el
    /// `manifest_dir` que hay que pasarle a [`load`] (la captura se busca en `..`).
    struct Caso {
        raiz: PathBuf,
    }

    impl Caso {
        fn nuevo(nombre: &str, contenido: Option<(&str, &str)>) -> Self {
            static N: AtomicU32 = AtomicU32::new(0);
            let raiz = std::env::temp_dir()
                .join(format!("vectores-{nombre}-{}", N.fetch_add(1, Ordering::Relaxed)));
            let _ = std::fs::remove_dir_all(&raiz);
            std::fs::create_dir_all(raiz.join("crate_falso")).unwrap();
            if let Some((archivo, texto)) = contenido {
                std::fs::write(raiz.join(archivo), texto).unwrap();
            }
            Self { raiz }
        }
        fn manifest(&self) -> String {
            self.raiz.join("crate_falso").to_string_lossy().into_owned()
        }
    }

    impl Drop for Caso {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.raiz);
        }
    }

    const BUENO: &str = r#"{"userAgent":"Music/5.0.2","ckcRequests":[1],"nulo":null}"#;

    #[test]
    fn lee_la_captura_real_cuando_esta() {
        let c = Caso::nuevo("real", Some(("vectors.json", BUENO)));
        let v = load(&c.manifest()).expect("tiene que encontrarla");
        assert_eq!(v["userAgent"], "Music/5.0.2");
    }

    /// Sin la real cae a la muestra versionada.
    #[test]
    fn cae_a_la_muestra() {
        let c = Caso::nuevo("muestra", Some(("vectors.sample.json", BUENO)));
        assert_eq!(load(&c.manifest()).expect("la muestra")["userAgent"], "Music/5.0.2");
    }

    /// Con las dos, manda la real: es la que corresponde al dispositivo que se esta probando.
    #[test]
    fn la_real_le_gana_a_la_muestra() {
        let c = Caso::nuevo("ambas", Some(("vectors.sample.json", r#"{"userAgent":"muestra"}"#)));
        std::fs::write(c.raiz.join("vectors.json"), r#"{"userAgent":"real"}"#).unwrap();
        assert_eq!(load(&c.manifest()).expect("la real")["userAgent"], "real");
    }

    /// Sin ninguna de las dos se salta, que es el unico caso en que un test puede no validar.
    #[test]
    fn sin_captura_devuelve_none() {
        let c = Caso::nuevo("ninguna", None);
        // explicito: sin exigir se salta, y es el unico caso en que un test no valida
        assert!(load_exigiendo(&c.manifest(), false).is_none());
    }

    /// Una captura corrupta es un fallo, no un test que se salta: es justo el agujero que
    /// este crate existe para tapar.
    #[test]
    #[should_panic(expected = "no parsea")]
    fn una_captura_corrupta_revienta() {
        let c = Caso::nuevo("corrupta", Some(("vectors.json", "{ no soy json")));
        let _ = load(&c.manifest());
    }

    /// Si la captura esta, la seccion que el test pide **tiene** que estar.
    #[test]
    #[should_panic(expected = "no trae la seccion")]
    fn una_seccion_que_falta_revienta() {
        let c = Caso::nuevo("sin-seccion", Some(("vectors.json", BUENO)));
        let _ = section(&c.manifest(), "deviceGuid");
    }

    /// Una seccion en null tampoco sirve para validar.
    #[test]
    #[should_panic(expected = "en null")]
    fn una_seccion_en_null_revienta() {
        let c = Caso::nuevo("null", Some(("vectors.json", BUENO)));
        let _ = section(&c.manifest(), "nulo");
    }

    /// Y si se pide como string, tiene que serlo.
    #[test]
    #[should_panic(expected = "deberia ser string")]
    fn una_seccion_que_no_es_string_revienta() {
        let c = Caso::nuevo("no-string", Some(("vectors.json", BUENO)));
        let _ = string_section(&c.manifest(), "ckcRequests");
    }

    /// Con la captura exigida, que falte es un fallo y no un salto.
    #[test]
    #[should_panic(expected = "esta puesta y no hay")]
    fn sin_captura_pero_exigida_revienta() {
        let c = Caso::nuevo("exigida", None);
        let _ = load_exigiendo(&c.manifest(), true);
    }

    /// La ruta indicada a mano manda sobre la captura del crate.
    #[test]
    fn la_ruta_forzada_le_gana_a_todo() {
        let c = Caso::nuevo("forzada", Some(("vectors.json", r#"{"userAgent":"del crate"}"#)));
        let otra = c.raiz.join("otra.json");
        std::fs::write(&otra, r#"{"userAgent":"forzada"}"#).unwrap();
        let v = load_desde(&c.manifest(), false, Some(otra)).expect("la forzada");
        assert_eq!(v["userAgent"], "forzada");
    }

    /// Y si apunta a algo que no existe es un fallo aunque no se haya exigido la captura: quien
    /// la indico queria ese archivo, no "el que haya".
    #[test]
    #[should_panic(expected = "esta puesta y no hay")]
    fn una_ruta_forzada_que_no_existe_revienta() {
        let c = Caso::nuevo("forzada-falta", Some(("vectors.json", BUENO)));
        let _ = load_desde(&c.manifest(), false, Some(c.raiz.join("no-existe.json")));
    }

    #[test]
    fn una_seccion_string_se_lee() {
        let c = Caso::nuevo("string", Some(("vectors.json", BUENO)));
        assert_eq!(string_section(&c.manifest(), "userAgent").unwrap(), "Music/5.0.2");
    }

    /// `section` sobre una captura ausente se salta igual que `load`: no hay nada que exigir.
    #[test]
    fn seccion_sin_captura_devuelve_none() {
        let c = Caso::nuevo("sec-ninguna", None);
        assert!(load_exigiendo(&c.manifest(), false).is_none());
    }
}
