//! The device identity the Apple libraries provision this machine for, and the on-disk record of it.
//!
//! `adi.pb` is never shipped or seeded: StoreServices provisions it on the device through CoreADI. With
//! no `adi.pb`, the first signed request is answered with `X-Apple-AMD-Action` / `X-Apple-AMD-Data`;
//! `ProtocolParser::_setActions` turns those headers into an `AnisetteProtocolAction`
//! (`actionForHeaders`), and `URLRequest::_performActionsForResponse` runs it
//! (`performWithContext` -> `_provisionWithContext`, a `bool`, retried on MediaPlatform network errors).
//! That runs `MachineDataProvisioningRequest`: CoreADI's provisioning start on the server data, a POST
//! to the bag's `md-finish-provisioning` (`amd-` for the newer protocol), CoreADI's provisioning end,
//! which writes `adi.pb` under the base dir. The FairPlay state (`fsi.pdat`, `IC-Info.sids`) is written
//! the same way, by the libraries, on first use.
//!
//! All of it is bound to the id given to `FootHillConfig::config` / `DeviceGUID::configure`, which is
//! this device's own Android ID. `<base_dir>/ANDROID_ID` records the id the files on disk belong to.
//! When it differs (an old seeded identity, a session imported from another phone), those files are
//! set aside, not deleted, and the libraries provision this device again.
//!
//! Pure std on purpose: `rustc --test` runs these tests on the host without the Apple libraries.

use std::fs;
use std::io::Read;
use std::path::Path;

/// Files the Apple libraries bind to the device identity.
pub const PROVISIONING_FILES: [&str; 3] = ["adi.pb", "fsi.pdat", "IC-Info.sids"];

/// Suffix for provisioning files that belong to another identity.
pub const RETIRED_SUFFIX: &str = ".retired";

/// What to do at startup, decided from the device's id and the recorded one.
#[derive(Debug, PartialEq, Eq)]
pub struct Decision {
    /// The id to configure the libraries with.
    pub id: String,
    /// The provisioning files on disk belong to another id (or to none recorded): set them aside.
    pub retire: bool,
    /// Record `id` in `ANDROID_ID`.
    pub record: bool,
}

/// An Android ID as the platform reports it: 1 to 16 hex digits. "unknown" and empty are not ids.
pub fn usable_id(raw: &str) -> Option<String> {
    let id = raw.trim().to_ascii_lowercase();
    let ok = !id.is_empty() && id.len() <= 16 && id.bytes().all(|b| b.is_ascii_hexdigit());
    ok.then_some(id)
}

/// The device's own id always wins. Without one, keep the recorded id; without either, `fresh`.
///
/// Nothing recorded with a device id means files the libraries provisioned for that same id (before
/// the id was recorded, the device's own id was already used whenever no other id was on disk), so
/// they stay. A recorded id that differs means another identity's files: they are set aside.
pub fn decide(device_id: &str, recorded: &str, fresh: impl FnOnce() -> String) -> Decision {
    let recorded = usable_id(recorded);
    match (usable_id(device_id), recorded) {
        (Some(real), Some(rec)) if real == rec => Decision { id: real, retire: false, record: false },
        (Some(real), None) => Decision { id: real, retire: false, record: true },
        (Some(real), Some(_)) => Decision { id: real, retire: true, record: true },
        (None, Some(rec)) => Decision { id: rec, retire: false, record: false },
        (None, None) => Decision { id: fresh(), retire: true, record: true },
    }
}

/// Apply [decide] to `base_dir`: set aside foreign provisioning, record the id, return it.
pub fn resolve(base_dir: &str, device_id: &str, log: &dyn Fn(&str)) -> String {
    let recorded = read_recorded(base_dir);
    let d = decide(device_id, &recorded, random_id);
    if d.retire {
        let moved = retire(base_dir);
        if !moved.is_empty() {
            let from = if recorded.is_empty() { "none recorded" } else { "another id" };
            log(&format!("identity: provisioning for {from} set aside ({}); the libraries provision this device", moved.join(", ")));
        }
    }
    if d.record {
        if let Err(e) = fs::write(Path::new(base_dir).join("ANDROID_ID"), &d.id) {
            log(&format!("identity: could not record ANDROID_ID: {e}"));
        }
    }
    d.id
}

/// The id recorded in `<base_dir>/ANDROID_ID` (first line), or "".
pub fn read_recorded(base_dir: &str) -> String {
    fs::read_to_string(Path::new(base_dir).join("ANDROID_ID"))
        .ok()
        .and_then(|s| s.lines().next().map(|l| l.trim().to_owned()))
        .unwrap_or_default()
}

/// Rename each provisioning file present to `<name>.retired` (replacing an older one). Returns the names moved.
pub fn retire(base_dir: &str) -> Vec<String> {
    let base = Path::new(base_dir);
    PROVISIONING_FILES
        .iter()
        .filter(|name| base.join(name).is_file())
        .filter(|name| fs::rename(base.join(name), base.join(format!("{name}{RETIRED_SUFFIX}"))).is_ok())
        .map(|name| (*name).to_owned())
        .collect()
}

/// Whether CoreADI has provisioned this machine, for the logs: "adi.pb N bytes" or "not provisioned".
pub fn adi_state(base_dir: &str) -> String {
    match fs::metadata(Path::new(base_dir).join("adi.pb")) {
        Ok(m) if m.is_file() && m.len() > 0 => format!("adi.pb {} bytes", m.len()),
        _ => "not provisioned".to_owned(),
    }
}

/// A random 16-hex id, for a device that reports none.
fn random_id() -> String {
    let mut b = [0u8; 8];
    let read = fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut b));
    if read.is_err() {
        // Practically unreachable on Android; still distinct per install and per boot.
        let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        b = ((t as u64) ^ ((std::process::id() as u64) << 32)).to_le_bytes();
    }
    b.iter().map(|x| format!("{x:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("cadence-identity-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    fn never() -> String {
        panic!("no fresh id expected")
    }

    #[test]
    fn usable_ids() {
        assert_eq!(usable_id(" 0A1b2C3d4E5f6789\n"), Some("0a1b2c3d4e5f6789".to_owned()));
        assert_eq!(usable_id("abc"), Some("abc".to_owned()));
        for bad in ["", "  ", "unknown", "0a1b2c3d4e5f67890", "xyz", "12 34"] {
            assert_eq!(usable_id(bad), None, "{bad:?}");
        }
    }

    #[test]
    fn the_device_id_always_wins() {
        let same = decide("aaaa", "aaaa", never);
        assert_eq!(same, Decision { id: "aaaa".into(), retire: false, record: false });
        let other = decide("aaaa", "bbbb", never);
        assert_eq!(other, Decision { id: "aaaa".into(), retire: true, record: true });
        let unrecorded = decide("aaaa", "", never);
        assert_eq!(unrecorded, Decision { id: "aaaa".into(), retire: false, record: true });
    }

    #[test]
    fn unrecorded_provisioning_for_this_device_is_kept_and_recorded() {
        let dir = tmp("unrecorded");
        fs::write(format!("{dir}/adi.pb"), [3u8; 512]).unwrap();
        let id = resolve(&dir, "aaaa", &|m| panic!("unexpected log {m}"));
        assert_eq!(id, "aaaa");
        assert_eq!(read_recorded(&dir), "aaaa");
        assert_eq!(adi_state(&dir), "adi.pb 512 bytes");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn without_a_device_id_the_recorded_one_stays_or_a_fresh_one_is_made() {
        assert_eq!(decide("unknown", "bbbb", never), Decision { id: "bbbb".into(), retire: false, record: false });
        let fresh = decide("", "", || "cccc".into());
        assert_eq!(fresh, Decision { id: "cccc".into(), retire: true, record: true });
    }

    #[test]
    fn foreign_provisioning_is_set_aside_and_the_id_recorded() {
        let dir = tmp("foreign");
        fs::write(format!("{dir}/ANDROID_ID"), "bbbb\n").unwrap();
        fs::write(format!("{dir}/adi.pb"), [1u8; 512]).unwrap();
        fs::write(format!("{dir}/fsi.pdat"), [2u8; 81]).unwrap();
        fs::write(format!("{dir}/token_cache.json"), "{}").unwrap();
        let logs = std::cell::RefCell::new(Vec::new());
        let id = resolve(&dir, "AAAA", &|m| logs.borrow_mut().push(m.to_owned()));
        assert_eq!(id, "aaaa");
        assert_eq!(read_recorded(&dir), "aaaa");
        assert!(!Path::new(&format!("{dir}/adi.pb")).exists());
        assert_eq!(fs::read(format!("{dir}/adi.pb.retired")).unwrap(), vec![1u8; 512]);
        assert!(Path::new(&format!("{dir}/fsi.pdat.retired")).exists());
        assert!(Path::new(&format!("{dir}/token_cache.json")).exists(), "only device-bound files move");
        assert_eq!(adi_state(&dir), "not provisioned");
        assert!(logs.borrow()[0].contains("adi.pb, fsi.pdat"));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn this_devices_provisioning_is_kept() {
        let dir = tmp("own");
        fs::write(format!("{dir}/ANDROID_ID"), "aaaa").unwrap();
        fs::write(format!("{dir}/adi.pb"), [7u8; 512]).unwrap();
        let id = resolve(&dir, "aaaa", &|m| panic!("unexpected log {m}"));
        assert_eq!(id, "aaaa");
        assert_eq!(adi_state(&dir), "adi.pb 512 bytes");
        assert!(!Path::new(&format!("{dir}/adi.pb.retired")).exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_fresh_id_is_16_hex_and_differs() {
        let (a, b) = (random_id(), random_id());
        assert_eq!(a.len(), 16);
        assert!(usable_id(&a).is_some());
        assert_ne!(a, b);
    }
}
