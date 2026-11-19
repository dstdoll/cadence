//! Minimal ISO-BMFF box walking over a byte slice.

/// One box inside a parent slice: `body` excludes the 8- or 16-byte header.
#[derive(Clone, Copy)]
pub struct BoxRef<'a> {
    pub kind: [u8; 4],
    /// Offset of the box header from the start of the slice that was walked.
    pub start: usize,
    pub body: &'a [u8],
    /// Offset of `body` from the start of the slice that was walked.
    pub body_start: usize,
}

/// Iterates the boxes laid end to end in `data`. Stops at the first box whose size does not
/// fit, so a truncated tail is ignored instead of read out of bounds.
pub fn children(data: &[u8]) -> Vec<BoxRef<'_>> {
    let mut out = Vec::new();
    let mut at = 0usize;
    while at + 8 <= data.len() {
        let size32 = u32::from_be_bytes([data[at], data[at + 1], data[at + 2], data[at + 3]]) as usize;
        let mut kind = [0u8; 4];
        kind.copy_from_slice(&data[at + 4..at + 8]);
        let (header, total) = match size32 {
            1 => {
                if at + 16 > data.len() {
                    break;
                }
                let mut wide = [0u8; 8];
                wide.copy_from_slice(&data[at + 8..at + 16]);
                (16usize, usize::try_from(u64::from_be_bytes(wide)).unwrap_or(usize::MAX))
            }
            0 => (8usize, data.len() - at),
            n => (8usize, n),
        };
        // Compared against the room left, not as `at + total`: a 64-bit size near u64::MAX would
        // overflow that sum, wrap past the check and slice out of bounds.
        if total < header || total > data.len() - at {
            break;
        }
        out.push(BoxRef { kind, start: at, body: &data[at + header..at + total], body_start: at + header });
        at += total;
    }
    out
}

pub fn child<'a>(data: &'a [u8], kind: &[u8; 4]) -> Option<BoxRef<'a>> {
    children(data).into_iter().find(|b| &b.kind == kind)
}

/// Follows `path` through nested container boxes and returns the last one's body.
pub fn descend<'a>(mut data: &'a [u8], path: &[&[u8; 4]]) -> Option<&'a [u8]> {
    for kind in path {
        data = child(data, kind)?.body;
    }
    Some(data)
}

/// Serialises one box: 32-bit size, type, body.
pub fn boxed(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + 8);
    out.extend(((body.len() + 8) as u32).to_be_bytes());
    out.extend(kind);
    out.extend(body);
    out
}

pub fn u16_at(data: &[u8], at: usize) -> Option<u16> {
    data.get(at..at + 2).map(|b| u16::from_be_bytes([b[0], b[1]]))
}

pub fn u32_at(data: &[u8], at: usize) -> Option<u32> {
    data.get(at..at + 4).map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}
