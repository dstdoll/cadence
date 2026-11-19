//! Fragmented-MP4 parsing and progressive M4A muxing for Apple's ALAC HLS packages.
//!
//! The package is `ftyp moov (moof mdat)*`. Two properties of Apple's packaging decide whether
//! a parser produces a playable file:
//!
//! * a `moof` carries **several `trun` boxes** in one `traf` (ten runs of 16 samples plus a
//!   tail), and every run must be read, in order;
//! * the runs carry sizes only (`flags = 0x201`), so the sample duration comes from the `tfhd`
//!   default or, failing that, from `moov/mvex/trex`.
//!
//! Reading only the first run, or leaving the duration at zero, yields a file that holds a
//! sliver of each segment with a broken time table. Both cases are errors here, never defaults.

mod boxes;
mod clear;
mod mux;
mod parse;

pub use clear::{build_sidx, clear_fragment, clear_init, clear_segment, decrypt_samples, ClearError};
pub use mux::{Mp4Muxer, TrackMetadata, TrackSpec};
pub use parse::{parse_fragment, parse_init, Fragment, InitSegment, ParseError, Sample};
