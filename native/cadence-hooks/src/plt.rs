//! Finds and patches the PLT slots (`JUMP_SLOT` relocations) that bind a symbol, in a library image
//! that is either a file on disk (tests read the real shipped libraries) or loaded memory (the hook).
//! One walker serves both, so what the tests prove is what runs on the device.

use std::ffi::c_void;

pub const R_AARCH64_JUMP_SLOT: u32 = 1026;
pub const R_X86_64_JUMP_SLOT: u32 = 7;

#[cfg(target_arch = "aarch64")]
pub const JUMP_SLOT: u32 = R_AARCH64_JUMP_SLOT;
#[cfg(not(target_arch = "aarch64"))]
pub const JUMP_SLOT: u32 = R_X86_64_JUMP_SLOT;

const PT_LOAD: u32 = 1;
const PT_DYNAMIC: u32 = 2;
const PT_GNU_RELRO: u32 = 0x6474_e552;
const DT_NULL: u64 = 0;
const DT_PLTRELSZ: u64 = 2;
const DT_STRTAB: u64 = 5;
const DT_SYMTAB: u64 = 6;
const DT_JMPREL: u64 = 23;

/// A readable library image addressed by virtual address (relative to the load base).
pub trait Image {
    fn read(&self, vaddr: u64, len: usize) -> Option<&[u8]>;
    /// Dynamic-section pointers are virtual addresses in a file; in memory some loaders rewrite them to
    /// absolute addresses. Returns the base-relative virtual address either way.
    fn norm(&self, ptr: u64) -> u64 {
        ptr
    }
}

fn u32_at(b: &[u8], o: usize) -> Option<u32> {
    Some(u32::from_le_bytes(b.get(o..o + 4)?.try_into().ok()?))
}
fn u64_at(b: &[u8], o: usize) -> Option<u64> {
    Some(u64::from_le_bytes(b.get(o..o + 8)?.try_into().ok()?))
}
fn u16_at(b: &[u8], o: usize) -> Option<u16> {
    Some(u16::from_le_bytes(b.get(o..o + 2)?.try_into().ok()?))
}

fn cstr(img: &dyn Image, vaddr: u64) -> Option<String> {
    let mut out = Vec::new();
    for i in 0..256u64 {
        let b = *img.read(vaddr + i, 1)?.first()?;
        if b == 0 {
            return String::from_utf8(out).ok();
        }
        out.push(b);
    }
    None
}

/// The base-relative addresses of the PLT slots in `[dynamic_vaddr, +size)`'s library that bind one of `wanted`.
pub fn jump_slots(img: &dyn Image, dynamic_vaddr: u64, dynamic_size: u64, jump_slot_type: u32, wanted: &[&str]) -> Vec<(String, u64)> {
    let (mut jmprel, mut pltrelsz, mut symtab, mut strtab) = (0u64, 0u64, 0u64, 0u64);
    let mut off = 0u64;
    while off + 16 <= dynamic_size {
        let Some(e) = img.read(dynamic_vaddr + off, 16) else { break };
        let (tag, val) = (u64_at(e, 0).unwrap_or(0), u64_at(e, 8).unwrap_or(0));
        match tag {
            DT_NULL => break,
            DT_JMPREL => jmprel = img.norm(val),
            DT_PLTRELSZ => pltrelsz = val,
            DT_SYMTAB => symtab = img.norm(val),
            DT_STRTAB => strtab = img.norm(val),
            _ => {}
        }
        off += 16;
    }
    let mut out = Vec::new();
    if jmprel == 0 || symtab == 0 || strtab == 0 {
        return out;
    }
    for i in 0..pltrelsz / 24 {
        let Some(rela) = img.read(jmprel + i * 24, 24) else { break };
        let (r_offset, r_info) = (u64_at(rela, 0).unwrap_or(0), u64_at(rela, 8).unwrap_or(0));
        if (r_info & 0xffff_ffff) as u32 != jump_slot_type {
            continue;
        }
        let sym = r_info >> 32;
        let Some(s) = img.read(symtab + sym * 24, 24) else { continue };
        let Some(name) = cstr(img, strtab + u32_at(s, 0).unwrap_or(0) as u64) else { continue };
        if wanted.contains(&name.as_str()) {
            out.push((name, r_offset));
        }
    }
    out
}

/// An ELF file read into memory, addressed by virtual address through its PT_LOAD segments.
pub struct FileImage<'a> {
    bytes: &'a [u8],
    loads: Vec<(u64, u64, u64)>, // vaddr, file offset, file size
    pub dynamic: Option<(u64, u64)>,
}

impl<'a> FileImage<'a> {
    pub fn parse(bytes: &'a [u8]) -> Option<FileImage<'a>> {
        if bytes.get(0..4)? != b"\x7fELF" || *bytes.get(4)? != 2 || *bytes.get(5)? != 1 {
            return None;
        }
        let (phoff, phentsize, phnum) = (u64_at(bytes, 0x20)? as usize, u16_at(bytes, 0x36)? as usize, u16_at(bytes, 0x38)? as usize);
        let (mut loads, mut dynamic) = (Vec::new(), None);
        for i in 0..phnum {
            let o = phoff + i * phentsize;
            let (ty, offset, vaddr, filesz, memsz) = (u32_at(bytes, o)?, u64_at(bytes, o + 8)?, u64_at(bytes, o + 16)?, u64_at(bytes, o + 32)?, u64_at(bytes, o + 40)?);
            match ty {
                PT_LOAD => loads.push((vaddr, offset, filesz)),
                PT_DYNAMIC => dynamic = Some((vaddr, memsz)),
                _ => {}
            }
        }
        Some(FileImage { bytes, loads, dynamic })
    }
}

impl Image for FileImage<'_> {
    fn read(&self, vaddr: u64, len: usize) -> Option<&[u8]> {
        let (v, o, sz) = *self.loads.iter().find(|(v, _, sz)| vaddr >= *v && vaddr < v + sz)?;
        let start = (o + (vaddr - v)) as usize;
        if vaddr - v + len as u64 > sz {
            return None;
        }
        self.bytes.get(start..start + len)
    }
}

/// A library mapped in this process (`base` is its load bias).
pub struct MemImage {
    pub base: usize,
}

impl Image for MemImage {
    fn read(&self, vaddr: u64, len: usize) -> Option<&[u8]> {
        // SAFETY: only called with addresses inside the library's own dynamic tables, which are mapped for the library's lifetime.
        Some(unsafe { std::slice::from_raw_parts((self.base as u64 + vaddr) as *const u8, len) })
    }
    fn norm(&self, ptr: u64) -> u64 {
        if ptr >= self.base as u64 { ptr - self.base as u64 } else { ptr }
    }
}

#[derive(Default, Debug)]
pub struct PatchReport {
    /// (library, symbol, slot address, original target)
    pub patched: Vec<(String, String, usize, usize)>,
    pub already: usize,
    /// (library, reason)
    pub skipped: Vec<(String, String)>,
    /// the library basenames that were not loaded at all
    pub missing: Vec<String>,
}

struct Walk<'a> {
    libs: &'a [&'a str],
    symbols: &'a [&'a str],
    replacement: usize,
    slot_type: u32,
    report: PatchReport,
    seen: Vec<String>,
}

unsafe extern "C" fn each_object(info: *mut libc::dl_phdr_info, _size: usize, data: *mut c_void) -> libc::c_int {
    let walk = &mut *(data as *mut Walk);
    let info = &*info;
    if info.dlpi_name.is_null() {
        return 0;
    }
    let name = std::ffi::CStr::from_ptr(info.dlpi_name).to_string_lossy().into_owned();
    let base_name = name.rsplit('/').next().unwrap_or("").to_string();
    if !walk.libs.contains(&base_name.as_str()) {
        return 0;
    }
    walk.seen.push(base_name.clone());
    let phdrs = std::slice::from_raw_parts(info.dlpi_phdr, info.dlpi_phnum as usize);
    let dynamic = phdrs.iter().find(|p| p.p_type == PT_DYNAMIC);
    let relro = phdrs.iter().find(|p| p.p_type == PT_GNU_RELRO).map(|p| (info.dlpi_addr as usize + p.p_vaddr as usize, p.p_memsz as usize));
    let Some(dynamic) = dynamic else {
        walk.report.skipped.push((base_name, "no PT_DYNAMIC".into()));
        return 0;
    };
    let img = MemImage { base: info.dlpi_addr as usize };
    let slots = jump_slots(&img, dynamic.p_vaddr as u64, dynamic.p_memsz as u64, walk.slot_type, walk.symbols);
    if slots.is_empty() {
        walk.report.skipped.push((base_name, "no matching PLT slot".into()));
        return 0;
    }
    let page = libc::sysconf(libc::_SC_PAGESIZE) as usize;
    for (sym, off) in slots {
        let slot = info.dlpi_addr as usize + off as usize;
        let cell = &*(slot as *const std::sync::atomic::AtomicUsize);
        let original = cell.load(std::sync::atomic::Ordering::Relaxed);
        if original == walk.replacement {
            walk.report.already += 1;
            continue;
        }
        let page_start = slot & !(page - 1);
        let in_relro = relro.map_or(false, |(s, n)| slot >= s && slot < s + n);
        if libc::mprotect(page_start as *mut c_void, page, libc::PROT_READ | libc::PROT_WRITE) != 0 {
            walk.report.skipped.push((base_name.clone(), format!("mprotect RW failed (errno {})", errno())));
            continue;
        }
        cell.store(walk.replacement, std::sync::atomic::Ordering::SeqCst);
        if in_relro {
            libc::mprotect(page_start as *mut c_void, page, libc::PROT_READ);
        }
        walk.report.patched.push((base_name.clone(), sym, slot, original));
    }
    0
}

/// Points every PLT slot binding one of `symbols` in the loaded libraries named in `libs` (basenames)
/// at `replacement`. Idempotent. Failures are reported, never fatal, and leave other libraries patched or untouched individually.
pub fn patch_loaded(symbols: &[&str], replacement: usize, libs: &[&str], slot_type: u32) -> PatchReport {
    let mut walk = Walk { libs, symbols, replacement, slot_type, report: PatchReport::default(), seen: Vec::new() };
    unsafe { libc::dl_iterate_phdr(Some(each_object), &mut walk as *mut Walk as *mut c_void) };
    for l in libs {
        if !walk.seen.iter().any(|s| s == l) {
            walk.report.missing.push((*l).to_string());
        }
    }
    walk.report
}

fn errno() -> i32 {
    #[cfg(target_os = "android")]
    unsafe { *libc::__errno() }
    #[cfg(not(target_os = "android"))]
    unsafe { *libc::__errno_location() }
}

/// The slots [`patch_loaded`] would patch, without touching anything: (library, symbol, slot address, current target).
pub fn patch_loaded_dry(symbols: &[&str], libs: &[&str], slot_type: u32) -> Vec<(String, String, usize, usize)> {
    struct Dry<'a> {
        libs: &'a [&'a str],
        symbols: &'a [&'a str],
        slot_type: u32,
        out: Vec<(String, String, usize, usize)>,
    }
    unsafe extern "C" fn each(info: *mut libc::dl_phdr_info, _s: usize, data: *mut c_void) -> libc::c_int {
        let d = &mut *(data as *mut Dry);
        let info = &*info;
        if info.dlpi_name.is_null() {
            return 0;
        }
        let name = std::ffi::CStr::from_ptr(info.dlpi_name).to_string_lossy().into_owned();
        let base_name = name.rsplit('/').next().unwrap_or("").to_string();
        if !d.libs.contains(&base_name.as_str()) {
            return 0;
        }
        let phdrs = std::slice::from_raw_parts(info.dlpi_phdr, info.dlpi_phnum as usize);
        let Some(dynamic) = phdrs.iter().find(|p| p.p_type == PT_DYNAMIC) else { return 0 };
        let img = MemImage { base: info.dlpi_addr as usize };
        for (sym, off) in jump_slots(&img, dynamic.p_vaddr as u64, dynamic.p_memsz as u64, d.slot_type, d.symbols) {
            let slot = info.dlpi_addr as usize + off as usize;
            d.out.push((base_name.clone(), sym, slot, *(slot as *const usize)));
        }
        0
    }
    let mut d = Dry { libs, symbols, slot_type, out: Vec::new() };
    unsafe { libc::dl_iterate_phdr(Some(each), &mut d as *mut Dry as *mut c_void) };
    d.out
}
