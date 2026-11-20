//! Primitivas del ABI de libc++ (`__ndk1`) necesarias para llamar a las librerías C++
//! de Apple desde Rust.
//!
//! Nada de esto lo hace Rust más seguro que el C que reemplaza: son punteros crudos a
//! librerías cerradas, resueltos por símbolo manglado, con layouts copiados a mano. Lo
//! que sí hace es **localizar** el `unsafe` en un solo módulo.
//!
//! Los layouts están fijados a la libc++ del NDK con la que Apple compiló estas libs;
//! un cambio de toolchain podría moverlos. Los `const _: () = assert!(...)` del final
//! son el seguro: fallan en compilación, no en el dispositivo.

use core::ffi::{c_char, c_void};

/// `std::__ndk1::shared_ptr<T>`: el puntero al objeto y el bloque de control.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct SharedPtr {
    pub obj: *mut c_void,
    pub ctrl: *mut c_void,
}

impl SharedPtr {
    pub const NULL: Self = Self { obj: core::ptr::null_mut(), ctrl: core::ptr::null_mut() };

    pub fn is_null(&self) -> bool {
        self.obj.is_null()
    }
}

impl Default for SharedPtr {
    fn default() -> Self {
        Self::NULL
    }
}

/// `std::__ndk1::vector<T>`: begin, end, end_of_capacity.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct StdVector {
    pub begin: *mut c_void,
    pub end: *mut c_void,
    pub end_cap: *mut c_void,
}

/// Un `std::string` **no propietario** para pasar como argumento.
///
/// libc++ guarda las cadenas en dos modos. En *short mode* los bytes viven inline y el
/// primer byte lleva `len << 1` (bit bajo en 0). En *long mode* el bit bajo de la
/// capacidad está en 1 y la estructura es `{cap, size, data}`.
///
/// Para pasar una cadena prestada se arma un long mode con capacidad `1`: el bit bajo
/// queda en 1, así que la librería lee `data`, y nunca intenta liberar porque sólo
/// recibe una referencia const.
///
/// Los bytes prestados tienen que vivir más que la llamada.
#[repr(C)]
pub struct StdString {
    cap: usize,
    size: usize,
    data: *const u8,
}

impl StdString {
    /// Presta un `&str` como `std::string const&`.
    ///
    /// No hace falta que termine en NUL: libc++ usa `size`, no `strlen`.
    pub fn borrow(s: &str) -> Self {
        Self { cap: 1, size: s.len(), data: s.as_ptr() }
    }

    /// Presta un slice de bytes.
    pub fn borrow_bytes(b: &[u8]) -> Self {
        Self { cap: 1, size: b.len(), data: b.as_ptr() }
    }

    /// Lee un `std::string` devuelto por la librería, en cualquiera de los dos modos.
    ///
    /// # Safety
    /// `p` tiene que apuntar a un `std::string` válido y vivo.
    /// Presta un `CStr` ya existente, sin copiar.
    ///
    /// Es [`borrow`](Self::borrow) para quien ya tiene un `CString` en la mano, que es
    /// como arma las cadenas la capa JNI: ahorra reconstruir un `&str` solo para volver
    /// a prestarlo. El `CStr` tiene que seguir vivo mientras se use el `StdString`.
    pub fn borrow_cstr(s: &core::ffi::CStr) -> Self {
        Self::borrow_bytes(s.to_bytes())
    }

    /// Puntero crudo a los bytes de un `std::string`, resolviendo short/long mode.
    ///
    /// Devuelve el puntero sin copiar ni validar UTF-8; para leer el contenido es mejor
    /// [`read`](Self::read) o [`read_utf8`](Self::read_utf8). Existe porque la capa nativa
    /// pasa el puntero directo a funciones que esperan `const char*`.
    ///
    /// # Safety
    /// `p` tiene que apuntar a un `std::string` valido de libc++.
    pub unsafe fn data(p: *const StdString) -> *const core::ffi::c_char {
        // Bit 0 del primer byte: 0 = short mode (los bytes van en sitio, despues del byte
        // de largo), 1 = long mode (el puntero `data` es el bueno).
        let first = unsafe { *(p as *const u8) };
        if first & 1 == 0 {
            unsafe { (p as *const u8).add(1) as *const core::ffi::c_char }
        } else {
            unsafe { (*p).data as *const core::ffi::c_char }
        }
    }

    /// Los bytes de un `std::string` de libc++, en short o long mode, copiados a un `Vec`.
    ///
    /// Devuelve `None` para un puntero nulo o un short mode con un largo imposible.
    ///
    /// # Safety
    /// `p` tiene que ser nulo o apuntar a un `std::string` valido de libc++ que siga vivo durante
    /// la llamada; en long mode, su puntero de datos tiene que cubrir los bytes que declara.
    pub unsafe fn read(p: *const StdString) -> Option<Vec<u8>> {
        if p.is_null() {
            return None;
        }
        unsafe {
            let first = *(p as *const u8);
            if first & 1 == 0 {
                // short mode: los bytes van justo después del byte de longitud
                let len = (first >> 1) as usize;
                if len > 22 {
                    return None;
                }
                Some(core::slice::from_raw_parts((p as *const u8).add(1), len).to_vec())
            } else {
                let size = (*p).size;
                let data = (*p).data;
                if data.is_null() || size > (1 << 24) {
                    return None;
                }
                Some(core::slice::from_raw_parts(data, size).to_vec())
            }
        }
    }

    /// Como [`read`](Self::read), pero validando UTF-8.
    ///
    /// # Safety
    /// Igual que [`read`](Self::read).
    pub unsafe fn read_utf8(p: *const StdString) -> Option<String> {
        unsafe { Self::read(p) }.and_then(|v| String::from_utf8(v).ok())
    }
}

// ---- retornos indirectos (sret) -----------------------------------------------------
//
// En AArch64 una función C++ que devuelve un tipo no trivial lo escribe a través de x8,
// no en x0. Rust usa la misma convención para cualquier `repr(C)` de más de 16 bytes:
// declarando el tipo de retorno con ese tamaño, las convenciones coinciden y el callee
// escribe donde corresponde. Por eso los carriers llevan padding explícito.

/// Carrier de 24 bytes para un `shared_ptr` devuelto por valor.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SretShared {
    pub sp: SharedPtr,
    _pad: usize,
}

impl SretShared {
    pub fn zeroed() -> Self {
        Self { sp: SharedPtr::NULL, _pad: 0 }
    }
}

/// Carrier de 24 bytes para un `mediaplatform::Data` (`{bytes, size}`) devuelto por valor.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SretData {
    pub bytes: *mut c_char,
    pub size: usize,
    _pad: usize,
}

impl SretData {
    pub fn zeroed() -> Self {
        Self { bytes: core::ptr::null_mut(), size: 0, _pad: 0 }
    }

    /// Copia los bytes a un `Vec`.
    ///
    /// # Safety
    /// El `Data` tiene que seguir vivo.
    pub unsafe fn to_vec(&self) -> Option<Vec<u8>> {
        if self.bytes.is_null() || self.size == 0 || self.size > (1 << 24) {
            return None;
        }
        unsafe { Some(core::slice::from_raw_parts(self.bytes as *const u8, self.size).to_vec()) }
    }
}

/// `RequestContext::init` devuelve 88 bytes por valor que sólo se usan por su efecto
/// colateral.
#[repr(C)]
pub struct ReqCtxInit88 {
    _b: [u8; 88],
}

impl Default for ReqCtxInit88 {
    fn default() -> Self {
        Self { _b: [0; 88] }
    }
}

/// `DeviceGUID::configure` devuelve 256 bytes por valor, también descartables.
#[repr(C)]
pub struct DevGuidConf256 {
    _b: [u8; 256],
}

impl Default for DevGuidConf256 {
    fn default() -> Self {
        Self { _b: [0; 256] }
    }
}

// ---- seguros de layout ---------------------------------------------------------------
const _: () = assert!(size_of::<SharedPtr>() == 16);
const _: () = assert!(size_of::<StdString>() == 24);
const _: () = assert!(size_of::<StdVector>() == 24);
// > 16 bytes fuerza el retorno indirecto por x8
const _: () = assert!(size_of::<SretShared>() == 24);
const _: () = assert!(size_of::<SretData>() == 24);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn una_cadena_prestada_queda_en_long_mode() {
        let s = StdString::borrow("hola");
        assert_eq!(s.cap & 1, 1, "el bit bajo marca long mode");
        assert_eq!(s.size, 4);
    }

    #[test]
    fn lee_long_mode() {
        let bytes = b"contenido largo que no entra inline";
        let s = StdString::borrow_bytes(bytes);
        let got = unsafe { StdString::read(&s as *const _) }.unwrap();
        assert_eq!(got, bytes);
    }

    #[test]
    fn lee_short_mode() {
        // short mode armado a mano: primer byte = len << 1, después los bytes
        let mut buf = [0u8; 24];
        buf[0] = 5 << 1;
        buf[1..6].copy_from_slice(b"corto");
        let got = unsafe { StdString::read(buf.as_ptr() as *const StdString) }.unwrap();
        assert_eq!(got, b"corto");
    }

    #[test]
    fn un_short_mode_imposible_se_rechaza() {
        // 30 bytes no caben en el buffer inline de 22
        let mut buf = [0u8; 24];
        buf[0] = 30 << 1;
        assert!(unsafe { StdString::read(buf.as_ptr() as *const StdString) }.is_none());
    }

    #[test]
    fn un_puntero_nulo_no_explota() {
        assert!(unsafe { StdString::read(core::ptr::null()) }.is_none());
    }

    #[test]
    fn el_shared_ptr_nulo_se_reconoce() {
        assert!(SharedPtr::NULL.is_null());
    }

    /// `borrow_cstr` tiene que dar exactamente lo mismo que `borrow` para el mismo texto:
    /// es el camino que usa la capa JNI, que ya trae `CString`.
    #[test]
    fn borrow_cstr_equivale_a_borrow() {
        let c = c"Music";
        let a = StdString::borrow_cstr(c);
        let b = StdString::borrow("Music");
        assert_eq!(a.size, b.size, "el largo no incluye el NUL");
        assert_eq!(a.cap, b.cap);
        assert_eq!(unsafe { core::slice::from_raw_parts(a.data, a.size) }, b"Music");
    }

    /// `data()` sobre un prestado (long mode) devuelve el puntero original.
    #[test]
    fn data_en_long_mode_devuelve_el_puntero() {
        let c = c"Pixel 8";
        let s = StdString::borrow_cstr(c);
        let p = unsafe { StdString::data(&s as *const StdString) };
        assert_eq!(p, c.as_ptr(), "en long mode sale el puntero prestado tal cual");
        assert_eq!(unsafe { core::ffi::CStr::from_ptr(p) }.to_bytes(), b"Pixel 8");
    }

    /// `data()` sobre un short mode apunta al byte siguiente al de largo.
    #[test]
    fn data_en_short_mode_apunta_en_sitio() {
        // short mode: primer byte = largo<<1 (bit 0 en cero), luego los bytes.
        let mut raw = [0u8; 24];
        raw[0] = 3 << 1;
        raw[1..4].copy_from_slice(b"abc");
        let p = raw.as_ptr() as *const StdString;
        let d = unsafe { StdString::data(p) };
        assert_eq!(d, unsafe { raw.as_ptr().add(1) } as *const core::ffi::c_char);
        assert_eq!(unsafe { core::slice::from_raw_parts(d as *const u8, 3) }, b"abc");
    }
}
