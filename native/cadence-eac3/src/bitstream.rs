//! Bitstream reader and ATSC A/52 / ETSI TS 102 366 syncframe header parser.

use crate::Eac3Error;

pub const CHANNELS_BY_ACMOD: [usize; 8] = [2, 1, 2, 3, 3, 4, 4, 5];

pub const BLOCKS_BY_NUMBLKSCOD: [usize; 4] = [1, 2, 3, 6];

/// Zero-allocation MSB-first bit reader over an immutable frame slice.
#[derive(Clone)]
pub struct BitReader<'a> {
    data: &'a [u8],
    bit_pos: usize,
}

impl<'a> BitReader<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, bit_pos: 0 }
    }

    pub fn bits_left(&self) -> usize {
        (self.data.len() * 8).saturating_sub(self.bit_pos)
    }

    pub fn read_bits(&mut self, n: usize) -> Result<u32, Eac3Error> {
        if n == 0 {
            return Ok(0);
        }
        if n > 32 || self.bits_left() < n {
            return Err(Eac3Error::DecodeFailed("unexpected end of bitstream"));
        }
        let mut val = 0u32;
        let mut rem = n;
        while rem > 0 {
            let byte_idx = self.bit_pos >> 3;
            let bit_in_byte = self.bit_pos & 7;
            let take = rem.min(8 - bit_in_byte);
            let shift = 8 - bit_in_byte - take;
            let mask = (1u32 << take) - 1;
            let chunk = ((self.data[byte_idx] as u32) >> shift) & mask;
            val = (val << take) | chunk;
            self.bit_pos += take;
            rem -= take;
        }
        Ok(val)
    }

    pub fn read_bool(&mut self) -> Result<bool, Eac3Error> {
        Ok(self.read_bits(1)? != 0)
    }

    pub fn read_signed(&mut self, n: usize) -> Result<i32, Eac3Error> {
        let raw = self.read_bits(n)?;
        let sign_bit = 1u32 << (n - 1);
        if (raw & sign_bit) != 0 {
            Ok((raw | !((1u32 << n) - 1)) as i32)
        } else {
            Ok(raw as i32)
        }
    }

    pub fn skip(&mut self, n: usize) -> Result<(), Eac3Error> {
        if self.bits_left() < n {
            return Err(Eac3Error::DecodeFailed("unexpected end of bitstream"));
        }
        self.bit_pos += n;
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct FrameHeader {
    pub is_eac3: bool,
    pub frame_bytes: usize,
    pub sample_rate: u32,
    pub fscod: usize,
    #[allow(dead_code)]
    pub bsid: u8,
    pub acmod: usize,
    pub lfeon: bool,
    pub fbw_channels: usize,
    pub num_blocks: usize,
    pub cmixlev: f32,
    pub surmixlev: f32,
}

const CMIXLEV_TAB: [f32; 4] = [0.70710677, 0.59460354, 0.5, 0.70710677];
const SURMIXLEV_TAB: [f32; 4] = [0.70710677, 0.5, 0.0, 0.5];

const SAMPLE_RATES: [u32; 4] = [48_000, 44_100, 32_000, 0];

/// AC-3 frame sizes in 16-bit words indexed by `[fscod][frmsizecod]`.
const AC3_FRAME_SIZE_WORDS: [[usize; 38]; 3] = [
    // 48 kHz
    [
        64, 64, 80, 80, 96, 96, 112, 112, 128, 128, 160, 160, 192, 192, 224, 224, 256, 256, 320,
        320, 384, 384, 448, 448, 512, 512, 640, 640, 768, 768, 896, 896, 1024, 1024, 1152, 1152,
        1280, 1280,
    ],
    // 44.1 kHz
    [
        69, 70, 87, 88, 104, 105, 121, 122, 139, 140, 174, 175, 208, 209, 243, 244, 278, 279, 348,
        349, 417, 418, 487, 488, 557, 558, 696, 697, 835, 836, 975, 976, 1114, 1115, 1253, 1254,
        1393, 1394,
    ],
    // 32 kHz
    [
        96, 96, 120, 120, 144, 144, 168, 168, 192, 192, 240, 240, 288, 288, 336, 336, 384, 384,
        480, 480, 576, 576, 672, 672, 768, 768, 960, 960, 1152, 1152, 1344, 1344, 1536, 1536,
        1728, 1728, 1920, 1920,
    ],
];

pub fn parse_header(r: &mut BitReader<'_>) -> Result<FrameHeader, Eac3Error> {
    let sync = r.read_bits(16)?;
    if sync != 0x0B77 {
        return Err(Eac3Error::DecodeFailed("missing 0x0B77 syncword"));
    }
    // Peek bsid (at bit offset 40..45) by reading the first 48 bits cleanly.
    let w1 = r.read_bits(16)?; // AC-3 crc1 / E-AC-3 strmtyp+substreamid+frmsiz[10:0]
    let bsi_byte = r.read_bits(8)?; // AC-3 fscod+frmsizecod / E-AC-3 fscod+numblkscod+acmod+lfeon
    let bsid_raw = r.read_bits(8)?;
    let bsid = (bsid_raw >> 3) as u8;

    if bsid <= 10 {
        // Standard AC-3
        let fscod = ((bsi_byte >> 6) & 3) as usize;
        let frmsizecod = (bsi_byte & 0x3F) as usize;
        if fscod >= 3 || frmsizecod >= 38 {
            return Err(Eac3Error::DecodeFailed("invalid AC-3 fscod/frmsizecod"));
        }
        let frame_bytes = AC3_FRAME_SIZE_WORDS[fscod][frmsizecod] * 2;
        let _bsmod = bsid_raw & 7;
        let acmod = r.read_bits(3)? as usize;
        let cmixlev = if (acmod & 1) != 0 && acmod != 1 {
            CMIXLEV_TAB[r.read_bits(2)? as usize]
        } else {
            CMIXLEV_TAB[0]
        };
        let surmixlev = if (acmod & 4) != 0 {
            SURMIXLEV_TAB[r.read_bits(2)? as usize]
        } else {
            SURMIXLEV_TAB[0]
        };
        if acmod == 2 {
            r.skip(2)?; // dsurmod
        }
        let lfeon = r.read_bool()?;
        r.skip(5)?; // dialnorm
        if r.read_bool()? {
            r.skip(8)?; // compre + compr
        }
        if r.read_bool()? {
            r.skip(8)?; // langcode + langcod
        }
        if r.read_bool()? {
            r.skip(7)?; // audprodie
        }
        if acmod == 0 {
            r.skip(5)?; // dialnorm2
            if r.read_bool()? {
                r.skip(8)?;
            }
            if r.read_bool()? {
                r.skip(8)?;
            }
            if r.read_bool()? {
                r.skip(7)?;
            }
        }
        let _copyrightb = r.read_bool()?;
        let _origbs = r.read_bool()?;
        if bsid == 6 {
            // Alt bitstream syntax
            let xbsi1e = r.read_bool()?;
            if xbsi1e {
                r.skip(14)?;
            }
            if r.read_bool()? {
                r.skip(14)?;
            }
        } else {
            if r.read_bool()? {
                r.skip(14)?; // timecod1e
            }
            if r.read_bool()? {
                r.skip(14)?; // timecod2e
            }
        }
        if r.read_bool()? {
            let addbsil = r.read_bits(6)? as usize;
            r.skip((addbsil + 1) * 8)?;
        }
        let _ = w1;
        Ok(FrameHeader {
            is_eac3: false,
            frame_bytes,
            sample_rate: SAMPLE_RATES[fscod],
            fscod,
            bsid,
            acmod,
            lfeon,
            fbw_channels: CHANNELS_BY_ACMOD[acmod],
            num_blocks: 6,
            cmixlev,
            surmixlev,
        })
    } else if (11..=16).contains(&bsid) {
        // E-AC-3 (ETSI TS 102 366 Annex E)
        let strmtyp = (w1 >> 14) & 3;
        if strmtyp == 1 {
            return Err(Eac3Error::DecodeFailed("dependent E-AC-3 substream without independent stream"));
        }
        let frmsiz = (w1 & 0x07FF) as usize;
        let frame_bytes = (frmsiz + 1) * 2;
        let fscod = ((bsi_byte >> 6) & 3) as usize;
        let (actual_fscod, num_blocks) = if fscod == 3 {
            let fscod2 = ((bsi_byte >> 4) & 3) as usize;
            if fscod2 >= 3 {
                return Err(Eac3Error::DecodeFailed("invalid E-AC-3 fscod2"));
            }
            (fscod2, 6)
        } else {
            let numblkscod = ((bsi_byte >> 4) & 3) as usize;
            (fscod, BLOCKS_BY_NUMBLKSCOD[numblkscod])
        };
        let acmod = ((bsi_byte >> 1) & 7) as usize;
        let lfeon = (bsi_byte & 1) != 0;
        let _dialnorm = bsid_raw & 7; // top 3 bits of dialnorm; lower 2 bits next
        r.skip(2)?;
        if r.read_bool()? {
            r.skip(8)?; // compre + compr
        }
        if acmod == 0 {
            r.skip(5)?; // dialnorm2
            if r.read_bool()? {
                r.skip(8)?;
            }
        }
        if strmtyp == 1 && r.read_bool()? {
            r.skip(16)?; // chanmap
        }
        if r.read_bool()? {
            // mixmdate
            if acmod > 2 {
                r.skip(2)?; // dmixmod
            }
            if (acmod & 1) != 0 && acmod > 2 {
                r.skip(6)?; // ltrtcmixlev + lorocmixlev
            }
            if (acmod & 4) != 0 {
                r.skip(6)?; // ltrtsurmixlev + lorosurmixlev
            }
            if lfeon && r.read_bool()? {
                r.skip(5)?; // lfemixlevcod
            }
            if strmtyp == 0 {
                if r.read_bool()? {
                    r.skip(6)?; // pgmscle
                }
                if acmod == 0 && r.read_bool()? {
                    r.skip(6)?;
                }
                if r.read_bool()? {
                    r.skip(6)?; // extpgmscle
                }
                let mixdef = r.read_bits(2)?;
                if mixdef == 1 {
                    r.skip(5)?;
                } else if mixdef == 2 {
                    r.skip(12)?;
                } else if mixdef == 3 {
                    let mixdeflen = r.read_bits(5)? as usize;
                    r.skip((mixdeflen + 2) * 8)?;
                }
                if acmod < 2 {
                    if r.read_bool()? {
                        r.skip(14)?; // paninfoe
                    }
                    if acmod == 0 && r.read_bool()? {
                        r.skip(14)?;
                    }
                }
                if r.read_bool()? {
                    // frmmixcfginfoe
                    if num_blocks == 1 {
                        r.skip(5)?;
                    } else {
                        for _ in 0..num_blocks {
                            if r.read_bool()? {
                                r.skip(5)?;
                            }
                        }
                    }
                }
            }
        }
        if r.read_bool()? {
            // infomdate
            r.skip(5)?; // bsmod + copyrightb + origbs
            if acmod == 2 {
                r.skip(4)?; // dsurmod + dheadphonmod
            }
            if acmod >= 6 {
                r.skip(2)?; // dsurexmod
            }
            if r.read_bool()? {
                r.skip(8)?; // audprodie
            }
            if acmod == 0 && r.read_bool()? {
                r.skip(8)?;
            }
            if fscod < 3 {
                r.skip(1)?; // sourcefscod
            }
        }
        if strmtyp == 0 && num_blocks != 6 {
            r.skip(1)?; // convsync
        }
        if strmtyp == 2 {
            let blkid = if num_blocks == 6 { true } else { r.read_bool()? };
            if blkid {
                r.skip(6)?; // frmsizecod
            }
        }
        if r.read_bool()? {
            let addbsil = r.read_bits(6)? as usize;
            r.skip((addbsil + 1) * 8)?;
        }

        let sr = if fscod == 3 { SAMPLE_RATES[actual_fscod] / 2 } else { SAMPLE_RATES[actual_fscod] };
        Ok(FrameHeader {
            is_eac3: true,
            frame_bytes,
            sample_rate: sr,
            fscod: actual_fscod,
            bsid,
            acmod,
            lfeon,
            fbw_channels: CHANNELS_BY_ACMOD[acmod],
            num_blocks,
            cmixlev: CMIXLEV_TAB[1],
            surmixlev: SURMIXLEV_TAB[1],
        })
    } else {
        Err(Eac3Error::DecodeFailed("unsupported AC-3/E-AC-3 bsid"))
    }
}
