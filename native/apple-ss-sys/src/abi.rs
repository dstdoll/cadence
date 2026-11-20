//! libc++ (`__ndk1`) ABI primitives needed to call Apple's C++ libraries from Rust.
//!
//! None of this makes Rust safer than the C it replaced: these are raw pointers into closed
//! libraries, resolved by mangled symbol, with hand-copied layouts. What it does achieve is
//! **isolating** the `unsafe` surface in a single module.
//!
//! Layouts are pinned to the NDK libc++ Apple built these libraries against; a toolchain change
//! could shift them. The `const _: () = assert!(...)` checks at the end are the safety net: they
//! fail at compile time, not on the device.

use core::ffi::{c_char, c_void};

/// `std::__ndk1::shared_ptr<T>`: pointer to the object and control block.
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

/// Non-owning `std::string` for passing arguments.
///
/// libc++ stores strings in two modes. In *short mode* the bytes live inline and the first byte
/// holds `len << 1` (low bit 0). In *long mode* the low bit of capacity is 1 and the layout is
/// `{cap, size, data}`.
///
/// To pass a borrowed string we construct a long-mode struct with capacity `1`: the low bit is 1,
/// so the library reads `data` and never attempts to free it because it only receives a const ref.
///
/// The borrowed bytes must outlive the call.
#[repr(C)]
pub struct StdString {
    cap: usize,
    size: usize,
    data: *const u8,
}

impl StdString {
    /// Borrows a `&str` as `std::string const&`.
    ///
    /// Does not need to be NUL-terminated: libc++ uses `size`, not `strlen`.
    pub fn borrow(s: &str) -> Self {
        Self { cap: 1, size: s.len(), data: s.as_ptr() }
    }

    /// Borrows a byte slice.
    pub fn borrow_bytes(b: &[u8]) -> Self {
        Self { cap: 1, size: b.len(), data: b.as_ptr() }
    }

    /// Borrows an existing `CStr` without copying.
    ///
    /// Like [`borrow`](Self::borrow) for callers that already hold a `CString` (such as the JNI
    /// layer): avoids rebuilding a `&str` just to borrow it again. The `CStr` must outlive the
    /// `StdString`.
    pub fn borrow_cstr(s: &core::ffi::CStr) -> Self {
        Self::borrow_bytes(s.to_bytes())
    }

    /// Raw pointer to the bytes of a `std::string`, handling short/long mode.
    ///
    /// Returns the pointer without copying or validating UTF-8; to read the content, prefer
    /// [`read`](Self::read) or [`read_utf8`](Self::read_utf8). Exists because the native layer
    /// passes the pointer directly to functions expecting `const char*`.
    ///
    /// # Safety
    /// `p` must point to a valid, live libc++ `std::string`.
    pub unsafe fn data(p: *const StdString) -> *const core::ffi::c_char {
        // Bit 0 of the first byte: 0 = short mode (bytes are inline after the length byte),
        // 1 = long mode (`data` pointer is active).
        let first = unsafe { *(p as *const u8) };
        if first & 1 == 0 {
            unsafe { (p as *const u8).add(1) as *const core::ffi::c_char }
        } else {
            unsafe { (*p).data as *const core::ffi::c_char }
        }
    }

    /// Copies the bytes of a libc++ `std::string` (short or long mode) into a `Vec`.
    ///
    /// Returns `None` for a null pointer or a short mode with an impossible length.
    ///
    /// # Safety
    /// `p` must be null or point to a valid, live libc++ `std::string` during the call; in long
    /// mode, its data pointer must cover the declared byte count.
    pub unsafe fn read(p: *const StdString) -> Option<Vec<u8>> {
        if p.is_null() {
            return None;
        }
        unsafe {
            let first = *(p as *const u8);
            if first & 1 == 0 {
                // short mode: bytes follow immediately after the length byte
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

    /// Like [`read`](Self::read), but validates UTF-8.
    ///
    /// # Safety
    /// Same as [`read`](Self::read).
    pub unsafe fn read_utf8(p: *const StdString) -> Option<String> {
        unsafe { Self::read(p) }.and_then(|v| String::from_utf8(v).ok())
    }
}

// ---- indirect returns (sret) --------------------------------------------------------
//
// On AArch64, a C++ function returning a non-trivial type writes through x8 rather than x0.
// Rust uses the same convention for any `repr(C)` larger than 16 bytes: declaring the return
// type with that size aligns both conventions and the callee writes to the expected slot. That
// is why the carriers carry explicit padding.

/// 24-byte carrier for a `shared_ptr` returned by value.
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

/// 24-byte carrier for a `mediaplatform::Data` (`{bytes, size}`) returned by value.
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

    /// Copies the bytes into a `Vec`.
    ///
    /// # Safety
    /// The `Data` must still be alive.
    pub unsafe fn to_vec(&self) -> Option<Vec<u8>> {
        if self.bytes.is_null() || self.size == 0 || self.size > (1 << 24) {
            return None;
        }
        unsafe { Some(core::slice::from_raw_parts(self.bytes as *const u8, self.size).to_vec()) }
    }
}

/// `RequestContext::init` returns 88 bytes by value used only for its side effect.
#[repr(C)]
pub struct ReqCtxInit88 {
    _b: [u8; 88],
}

impl Default for ReqCtxInit88 {
    fn default() -> Self {
        Self { _b: [0; 88] }
    }
}

/// `DeviceGUID::configure` returns 256 bytes by value, also discardable.
#[repr(C)]
pub struct DevGuidConf256 {
    _b: [u8; 256],
}

impl Default for DevGuidConf256 {
    fn default() -> Self {
        Self { _b: [0; 256] }
    }
}

// ---- layout guards -------------------------------------------------------------------
const _: () = assert!(size_of::<SharedPtr>() == 16);
const _: () = assert!(size_of::<StdString>() == 24);
const _: () = assert!(size_of::<StdVector>() == 24);
// > 16 bytes forces indirect return via x8
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
        // hand-crafted short mode: first byte = len << 1, followed by the bytes
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
