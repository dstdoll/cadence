//! The walker against the REAL shipped arm64 libraries: what readelf showed on 2026-11-20 must be what the code finds.
use cadence_hooks::plt::{jump_slots, FileImage, R_AARCH64_JUMP_SLOT};
use std::path::PathBuf;

fn libs_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../backend/src/main/jniLibs/arm64-v8a")
}

fn slots(lib: &str, wanted: &[&str]) -> Vec<(String, u64)> {
    let bytes = std::fs::read(libs_dir().join(lib)).unwrap_or_else(|e| panic!("{lib}: {e}"));
    let img = FileImage::parse(&bytes).unwrap_or_else(|| panic!("{lib}: not an ELF64 LE file"));
    let (vaddr, size) = img.dynamic.unwrap_or_else(|| panic!("{lib}: no PT_DYNAMIC"));
    jump_slots(&img, vaddr, size, R_AARCH64_JUMP_SLOT, wanted)
}

#[test]
fn exactly_the_six_libraries_that_import_cxa_throw_have_a_slot() {
    let all: Vec<String> = std::fs::read_dir(libs_dir()).unwrap().filter_map(|e| e.ok()).map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".so")).collect();
    assert!(all.len() >= 15, "expected the shipped libraries, found {all:?}");
    let mut hooked: Vec<String> = all.iter().filter(|l| !slots(l, &["__cxa_throw"]).is_empty()).cloned().collect();
    hooked.sort();
    assert_eq!(hooked, vec!["libandroidappmusic.so", "libc++_shared.so", "libdaapkit.so", "libmedialibrarycore.so", "libmediaplatform.so", "libstoreservicescore.so"]);
    for lib in &hooked {
        assert_eq!(slots(lib, &["__cxa_throw"]).len(), 1, "{lib}: exactly one slot");
    }
}

#[test]
fn the_liblog_callers_are_the_ones_readelf_listed() {
    let wanted = ["__android_log_print", "__android_log_vprint", "__android_log_write"];
    let found = |lib: &str| { let mut s: Vec<String> = slots(lib, &wanted).into_iter().map(|(n, _)| n).collect(); s.sort(); s };
    assert_eq!(found("libandroidappmusic.so"), vec!["__android_log_print", "__android_log_vprint"]);
    assert_eq!(found("libmediaplatform.so"), vec!["__android_log_write"]);
    assert_eq!(found("libCoreFoundation.so"), vec!["__android_log_print"]);
    assert!(found("libCoreFP.so").is_empty() && found("libCoreADI.so").is_empty() && found("libCoreLSKD.so").is_empty(), "the opaque libraries do not log through liblog");
}

#[test]
fn a_slot_address_matches_readelfs_relocation_offset() {
    // `readelf -r libstoreservicescore.so` printed offset 0x269bd0 for __cxa_throw
    assert_eq!(slots("libstoreservicescore.so", &["__cxa_throw"]), vec![("__cxa_throw".to_string(), 0x269bd0)]);
}

#[test]
fn garbage_and_truncated_files_are_rejected_not_crashed_on() {
    assert!(FileImage::parse(b"not an elf").is_none());
    assert!(FileImage::parse(&[]).is_none());
    let mut bytes = std::fs::read(libs_dir().join("libstoreservicescore.so")).unwrap();
    bytes.truncate(300);
    let _ = FileImage::parse(&bytes).map(|i| i.dynamic);
}
