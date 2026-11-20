//! The fixed-size records the hooks push: plain bytes, no pointers to be freed, so a hook never allocates.

pub const TAG_MAX: usize = 32;
pub const MSG_MAX: usize = 224;
pub const FRAMES: usize = 8;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    /// A liblog message (tag + message + priority).
    Log,
    /// A C++ throw: the type name is in `msg`, the call stack in `frames`.
    Throw,
}

#[derive(Clone, Copy)]
pub struct Record {
    pub kind: Kind,
    pub prio: u8,
    pub tid: u32,
    /// For `Throw`: how many times this (type, call site) was thrown when this record was made (1-based).
    pub count: u32,
    tag_len: u8,
    msg_len: u8,
    tag: [u8; TAG_MAX],
    msg: [u8; MSG_MAX],
    pub nframes: u8,
    pub frames: [usize; FRAMES],
}

fn copy(dst: &mut [u8], src: &[u8]) -> u8 {
    let n = src.len().min(dst.len());
    dst[..n].copy_from_slice(&src[..n]);
    n as u8
}

impl Record {
    pub fn log(prio: u8, tid: u32, tag: &[u8], msg: &[u8]) -> Record {
        let mut r = Record { kind: Kind::Log, prio, tid, count: 0, tag_len: 0, msg_len: 0, tag: [0; TAG_MAX], msg: [0; MSG_MAX], nframes: 0, frames: [0; FRAMES] };
        r.tag_len = copy(&mut r.tag, tag);
        r.msg_len = copy(&mut r.msg, msg);
        r
    }

    pub fn throw(tid: u32, count: u32, type_name: &[u8], frames: &[usize]) -> Record {
        let mut r = Record { kind: Kind::Throw, prio: 0, tid, count, tag_len: 0, msg_len: 0, tag: [0; TAG_MAX], msg: [0; MSG_MAX], nframes: 0, frames: [0; FRAMES] };
        r.msg_len = copy(&mut r.msg, type_name);
        let n = frames.len().min(FRAMES);
        r.frames[..n].copy_from_slice(&frames[..n]);
        r.nframes = n as u8;
        r
    }

    pub fn tag(&self) -> String {
        String::from_utf8_lossy(&self.tag[..self.tag_len as usize]).into_owned()
    }

    pub fn msg(&self) -> String {
        String::from_utf8_lossy(&self.msg[..self.msg_len as usize]).into_owned()
    }

    pub fn frames(&self) -> &[usize] {
        &self.frames[..self.nframes as usize]
    }
}

/// Android log priority -> the letter logcat prints.
pub fn prio_letter(prio: u8) -> char {
    match prio {
        2 => 'V',
        3 => 'D',
        4 => 'I',
        5 => 'W',
        6 => 'E',
        7 => 'F',
        _ => '?',
    }
}
