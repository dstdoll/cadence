//! Lean, pure-Rust AC-3 (ATSC A/52) and E-AC-3 (ETSI TS 102 366) audio decoder.
//!
//! Decodes independent AC-3 and E-AC-3 syncframes into stereo PCM (`f32` or `i16`)
//! using ITU-R BS.775 downmixing for multi-channel bitstreams.

mod alloc;
mod bitstream;
mod imdct;
mod tables;

use alloc::{compute_bap, decode_exponents, BitAllocParams, MantissaDecoder, EXP_REUSE};
use bitstream::{parse_header, BitReader, FrameHeader};
use imdct::{apply_rematrixing, downmix_to_stereo, ImdctState};

const MAX_CHANNELS: usize = 6;
const BLOCK_SAMPLES: usize = 256;

#[derive(Debug)]
pub enum Eac3Error {
    InvalidConfig(&'static str),
    DecodeFailed(&'static str),
}

impl std::fmt::Display for Eac3Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Eac3Error::InvalidConfig(msg) => write!(f, "eac3 config error: {msg}"),
            Eac3Error::DecodeFailed(msg) => write!(f, "eac3 decode error: {msg}"),
        }
    }
}

impl std::error::Error for Eac3Error {}

#[derive(Clone)]
struct ChannelState {
    blksw: bool,
    dithflag: bool,
    end_bin: usize,
    exp_strategy: u8,
    exps: [u8; 256],
    bap: [u8; 256],
    fgain: i16,
    snroffset: i16,
}

impl Default for ChannelState {
    fn default() -> Self {
        Self {
            blksw: false,
            dithflag: true,
            end_bin: 253,
            exp_strategy: EXP_REUSE,
            exps: [0; 256],
            bap: [0; 256],
            fgain: 0,
            snroffset: 0,
        }
    }
}

struct BlockState {
    channels: [ChannelState; MAX_CHANNELS],
    cplinu: bool,
    chincpl: [bool; MAX_CHANNELS],
    phsflginu: bool,
    phsflg: [bool; 18],
    cplbegf: usize,
    cplendf: usize,
    cpl_start_bin: usize,
    cpl_end_bin: usize,
    ncplbnd: usize,
    cpl_bnd_bins: [usize; 19],
    cpl_exp_strategy: u8,
    cpl_exps: [u8; 256],
    cpl_bap: [u8; 256],
    cpl_fgain: i16,
    cpl_snroffset: i16,
    cpl_fastleak: i16,
    cpl_slowleak: i16,
    cplco: [[f32; 18]; MAX_CHANNELS],
    lfe_exp_strategy: u8,
    lfe_exps: [u8; 7],
    lfe_bap: [u8; 7],
    lfe_fgain: i16,
    lfe_snroffset: i16,
    rematflg: [bool; 4],
    ba: BitAllocParams,
    csnroffst: i16,
    cmixlev: f32,
    surmixlev: f32,
}

impl Default for BlockState {
    fn default() -> Self {
        Self {
            channels: std::array::from_fn(|_| ChannelState::default()),
            cplinu: false,
            chincpl: [false; MAX_CHANNELS],
            phsflginu: false,
            phsflg: [false; 18],
            cplbegf: 0,
            cplendf: 0,
            cpl_start_bin: 37,
            cpl_end_bin: 37,
            ncplbnd: 0,
            cpl_bnd_bins: [37; 19],
            cpl_exp_strategy: EXP_REUSE,
            cpl_exps: [0; 256],
            cpl_bap: [0; 256],
            cpl_fgain: 0,
            cpl_snroffset: 0,
            cpl_fastleak: 768,
            cpl_slowleak: 768,
            cplco: [[1.0; 18]; MAX_CHANNELS],
            lfe_exp_strategy: EXP_REUSE,
            lfe_exps: [0; 7],
            lfe_bap: [0; 7],
            lfe_fgain: 0,
            lfe_snroffset: 0,
            rematflg: [false; 4],
            ba: BitAllocParams::from_codes(2, 2, 2, 2, 7),
            csnroffst: 0,
            cmixlev: std::f32::consts::FRAC_1_SQRT_2,
            surmixlev: std::f32::consts::FRAC_1_SQRT_2,
        }
    }
}

/// Stateful AC-3 / E-AC-3 decoder session.
pub struct Eac3Session {
    sample_rate: u32,
    #[allow(dead_code)]
    channels: usize,
    imdct: ImdctState,
    mant: MantissaDecoder,
    state: BlockState,
    cached_coeffs: [[f32; 256]; MAX_CHANNELS],
}

impl Eac3Session {
    pub fn new(sample_rate: u32, channels: usize) -> Result<Self, Eac3Error> {
        if sample_rate == 0 {
            return Err(Eac3Error::InvalidConfig("sample_rate must be non-zero"));
        }
        Ok(Self {
            sample_rate,
            channels: channels.clamp(1, 2),
            imdct: ImdctState::new(MAX_CHANNELS),
            mant: MantissaDecoder::new(),
            state: BlockState::default(),
            cached_coeffs: [[0.0; 256]; MAX_CHANNELS],
        })
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn output_channels(&self) -> usize {
        2
    }

    pub fn max_output_bytes(&self) -> usize {
        // Max 6 blocks * 256 samples * 2ch * 4 bytes (f32)
        6 * BLOCK_SAMPLES * 2 * 4
    }

    pub fn reset(&mut self) {
        self.imdct.reset();
        self.state = BlockState::default();
    }

    /// Decodes a single AC-3 / E-AC-3 syncframe into interleaved stereo `f32` samples.
    pub fn decode_to_f32(&mut self, packet: &[u8], out: &mut [f32]) -> Result<usize, Eac3Error> {
        if packet.len() < 8 {
            return Err(Eac3Error::DecodeFailed("packet too short"));
        }
        let mut r = BitReader::new(packet);
        let hdr = parse_header(&mut r)?;
        self.sample_rate = hdr.sample_rate;
        self.state.cmixlev = hdr.cmixlev;
        self.state.surmixlev = hdr.surmixlev;
        self.imdct.ensure_channels(hdr.fbw_channels);

        let needed = hdr.num_blocks * BLOCK_SAMPLES * 2;
        if out.len() < needed {
            return Err(Eac3Error::DecodeFailed("output buffer too small"));
        }

        if hdr.is_eac3 {
            self.decode_eac3_blocks(&mut r, &hdr, out)?;
        } else {
            self.decode_ac3_blocks(&mut r, &hdr, out)?;
        }

        Ok(needed)
    }

    /// Decodes a single AC-3 / E-AC-3 syncframe into interleaved stereo `i16` PCM.
    pub fn decode_to_pcm16(&mut self, packet: &[u8], out: &mut [u8]) -> Result<usize, Eac3Error> {
        let mut f32_buf = [0.0f32; 6 * BLOCK_SAMPLES * 2];
        let count = self.decode_to_f32(packet, &mut f32_buf)?;
        let out_bytes = count * 2;
        if out.len() < out_bytes {
            return Err(Eac3Error::DecodeFailed("pcm16 output buffer too small"));
        }
        for i in 0..count {
            let s = (f32_buf[i].clamp(-1.0, 1.0) * 32767.0).round() as i16;
            let bytes = s.to_le_bytes();
            out[2 * i] = bytes[0];
            out[2 * i + 1] = bytes[1];
        }
        Ok(out_bytes)
    }

    fn decode_ac3_blocks(
        &mut self,
        r: &mut BitReader<'_>,
        hdr: &FrameHeader,
        out: &mut [f32],
    ) -> Result<(), Eac3Error> {
        for blk in 0..hdr.num_blocks {
            self.decode_ac3_block(r, hdr, blk)?;
            let mut time_chs = [[0.0f32; 256]; MAX_CHANNELS];
            self.synthesize_block(hdr, &mut time_chs)?;
            let slice = &mut out[blk * BLOCK_SAMPLES * 2..(blk + 1) * BLOCK_SAMPLES * 2];
            downmix_to_stereo(
                hdr.acmod,
                &time_chs[..hdr.fbw_channels],
                self.state.cmixlev,
                self.state.surmixlev,
                slice,
            );
        }
        Ok(())
    }

    fn decode_ac3_block(
        &mut self,
        r: &mut BitReader<'_>,
        hdr: &FrameHeader,
        blk: usize,
    ) -> Result<(), Eac3Error> {
        let nch = hdr.fbw_channels;
        for ch in 0..nch {
            self.state.channels[ch].blksw = r.read_bool()?;
        }
        for ch in 0..nch {
            self.state.channels[ch].dithflag = r.read_bool()?;
        }
        let mut dynrnge = r.read_bool()?;
        if dynrnge {
            r.skip(8)?;
        }
        if hdr.acmod == 0 {
            dynrnge = r.read_bool()?;
            if dynrnge {
                r.skip(8)?;
            }
        }
        if r.read_bool()? {
            // cplstre
            self.state.cplinu = r.read_bool()?;
            if self.state.cplinu {
                for ch in 0..nch {
                    self.state.chincpl[ch] = r.read_bool()?;
                }
                if hdr.acmod == 2 {
                    self.state.phsflginu = r.read_bool()?;
                }
                self.state.cplbegf = r.read_bits(4)? as usize;
                self.state.cplendf = r.read_bits(4)? as usize;
                let ncplsubnd = (3 + self.state.cplendf).saturating_sub(self.state.cplbegf);
                self.state.cpl_start_bin = self.state.cplbegf * 12 + 37;
                self.state.cpl_end_bin = self.state.cpl_start_bin + ncplsubnd * 12;
                let mut bnd_idx = 0usize;
                self.state.cpl_bnd_bins[0] = self.state.cpl_start_bin;
                for sub in 0..ncplsubnd.min(18) {
                    let combined = if sub > 0 { r.read_bool()? } else { false };
                    if !combined {
                        bnd_idx += 1;
                    }
                    self.state.cpl_bnd_bins[bnd_idx] = self.state.cpl_start_bin + (sub + 1) * 12;
                }
                self.state.ncplbnd = bnd_idx;
            }
        }

        if self.state.cplinu {
            let mut cplcoe_any = false;
            let ncplbnd = self.state.ncplbnd.min(18);
            for ch in 0..nch {
                if self.state.chincpl[ch] && r.read_bool()? {
                    cplcoe_any = true;
                    let mstrcplco = r.read_bits(2)?;
                    for bnd in 0..ncplbnd {
                        let exp = r.read_bits(4)?;
                        let mant = r.read_bits(4)?;
                        let scale = if exp == 15 {
                            (mant as f32) / 16.0
                        } else {
                            ((mant as f32) + 16.0) / 32.0 * 2.0f32.powi(-(exp as i32))
                        };
                        let mstr = 2.0f32.powi(-3 * (mstrcplco as i32));
                        self.state.cplco[ch][bnd] = scale * mstr * 8.0;
                    }
                }
            }
            if hdr.acmod == 2 && self.state.phsflginu && cplcoe_any {
                for bnd in 0..ncplbnd {
                    self.state.phsflg[bnd] = r.read_bool()?;
                }
            }
        }

        if hdr.acmod == 2 && r.read_bool()? {
            let nb = if self.state.cplinu && self.state.cpl_start_bin <= 61 {
                if self.state.cpl_start_bin <= 37 {
                    2
                } else {
                    3
                }
            } else {
                4
            };
            for b in 0..nb {
                self.state.rematflg[b] = r.read_bool()?;
            }
        }

        if self.state.cplinu {
            self.state.cpl_exp_strategy = r.read_bits(2)? as u8;
        }
        for ch in 0..nch {
            self.state.channels[ch].exp_strategy = r.read_bits(2)? as u8;
        }
        if hdr.lfeon {
            self.state.lfe_exp_strategy = r.read_bits(1)? as u8;
        }

        for ch in 0..nch {
            if self.state.channels[ch].exp_strategy != EXP_REUSE {
                if self.state.cplinu && self.state.chincpl[ch] {
                    self.state.channels[ch].end_bin = self.state.cpl_start_bin;
                } else {
                    let chbwcod = r.read_bits(6)? as usize;
                    self.state.channels[ch].end_bin = ((chbwcod + 12) * 3 + 37).min(253);
                }
            }
        }

        if self.state.cplinu && self.state.cpl_exp_strategy != EXP_REUSE {
            let cplabsexp = r.read_bits(4)? as u8;
            let ncplbins = self.state.cpl_end_bin.saturating_sub(self.state.cpl_start_bin);
            let grp_sz = match self.state.cpl_exp_strategy {
                1 => 3,
                2 => 6,
                _ => 12,
            };
            let ngrps = (ncplbins + grp_sz - 1) / grp_sz;
            decode_exponents(
                r,
                self.state.cpl_exp_strategy,
                ngrps,
                cplabsexp << 1,
                &mut self.state.cpl_exps[self.state.cpl_start_bin..self.state.cpl_end_bin],
            )?;
        }

        for ch in 0..nch {
            let strat = self.state.channels[ch].exp_strategy;
            if strat != EXP_REUSE {
                let first_exp = r.read_bits(4)? as u8;
                let end_bin = self.state.channels[ch].end_bin;
                let nbins = end_bin.saturating_sub(1);
                let grp_sz = match strat {
                    1 => 3,
                    2 => 6,
                    _ => 12,
                };
                let ngrps = (nbins + grp_sz - 1) / grp_sz;
                self.state.channels[ch].exps[0] = first_exp;
                decode_exponents(
                    r,
                    strat,
                    ngrps,
                    first_exp,
                    &mut self.state.channels[ch].exps[1..end_bin],
                )?;
                r.skip(2)?; // gainrng
            }
        }

        if hdr.lfeon && self.state.lfe_exp_strategy != EXP_REUSE {
            let first = r.read_bits(4)? as u8;
            self.state.lfe_exps[0] = first;
            decode_exponents(r, 1, 2, first, &mut self.state.lfe_exps[1..7])?;
        }

        let baie = r.read_bool()?;
        if baie {
            let sd = r.read_bits(2)? as usize;
            let fd = r.read_bits(2)? as usize;
            let sg = r.read_bits(2)? as usize;
            let db = r.read_bits(2)? as usize;
            let fl = r.read_bits(3)? as usize;
            self.state.ba = BitAllocParams::from_codes(sd, fd, sg, db, fl);
        }

        if r.read_bool()? {
            self.state.csnroffst = r.read_bits(6)? as i16;
            if self.state.cplinu {
                let fsnroffst = r.read_bits(4)? as i16;
                self.state.cpl_snroffset =
                    (((self.state.csnroffst - 15) << 4) + fsnroffst) << 2;
                let fgaincod = r.read_bits(3)? as i16;
                self.state.cpl_fgain = (fgaincod << 7) + 128;
            }
            for ch in 0..nch {
                let fsnroffst = r.read_bits(4)? as i16;
                self.state.channels[ch].snroffset =
                    (((self.state.csnroffst - 15) << 4) + fsnroffst) << 2;
                let fgaincod = r.read_bits(3)? as i16;
                self.state.channels[ch].fgain = (fgaincod << 7) + 128;
            }
            if hdr.lfeon {
                let fsnroffst = r.read_bits(4)? as i16;
                self.state.lfe_snroffset =
                    (((self.state.csnroffst - 15) << 4) + fsnroffst) << 2;
                let fgaincod = r.read_bits(3)? as i16;
                self.state.lfe_fgain = (fgaincod << 7) + 128;
            }
        }

        if self.state.cplinu && r.read_bool()? {
            let fleak = r.read_bits(3)? as i16;
            let sleak = r.read_bits(3)? as i16;
            self.state.cpl_fastleak = (fleak << 8) + 768;
            self.state.cpl_slowleak = (sleak << 8) + 768;
        }
        if r.read_bool()? {
            // dba
            if self.state.cplinu {
                let deltbae = r.read_bits(2)?;
                if deltbae == 1 {
                    let n = r.read_bits(3)? as usize + 1;
                    r.skip(n * 12)?;
                }
            }
            for _ch in 0..nch {
                let deltbae = r.read_bits(2)?;
                if deltbae == 1 {
                    let n = r.read_bits(3)? as usize + 1;
                    r.skip(n * 12)?;
                }
            }
        }
        if r.read_bool()? {
            let skipl = r.read_bits(9)? as usize;
            r.skip(skipl * 8)?;
        }

        let _ = blk;
        if hdr.frame_bytes == 3072 {
            for ch in 0..hdr.fbw_channels {
                self.state.channels[ch].snroffset -= 32;
            }
            self.state.lfe_snroffset -= 32;
        }
        self.run_bit_allocation(hdr);
        self.decode_mantissas(r, hdr)
    }

    fn decode_eac3_blocks(
        &mut self,
        r: &mut BitReader<'_>,
        hdr: &FrameHeader,
        out: &mut [f32],
    ) -> Result<(), Eac3Error> {
        // ETSI TS 102 366 Table E2.14: 6-block frame exponent strategy table (`ff_eac3_frm_expstr[32][6]`).
        const FRM_EXPSTR_BLOCKS: [[u8; 6]; 32] = [
            [1, 0, 0, 0, 0, 0], [1, 0, 0, 0, 0, 3], [1, 0, 0, 0, 2, 0], [1, 0, 0, 0, 3, 3],
            [2, 0, 0, 2, 0, 0], [2, 0, 0, 2, 0, 3], [2, 0, 0, 3, 2, 0], [2, 0, 0, 3, 3, 3],
            [2, 0, 1, 0, 0, 0], [2, 0, 2, 0, 0, 3], [2, 0, 2, 0, 2, 0], [2, 0, 2, 0, 3, 3],
            [2, 0, 3, 2, 0, 0], [2, 0, 3, 2, 0, 3], [2, 0, 3, 3, 2, 0], [2, 0, 3, 3, 3, 3],
            [3, 1, 0, 0, 0, 0], [3, 1, 0, 0, 0, 3], [3, 2, 0, 0, 2, 0], [3, 2, 0, 0, 3, 3],
            [3, 2, 0, 2, 0, 0], [3, 2, 0, 2, 0, 3], [3, 2, 0, 3, 2, 0], [3, 2, 0, 3, 3, 3],
            [3, 3, 1, 0, 0, 0], [3, 3, 2, 0, 0, 3], [3, 3, 2, 0, 2, 0], [3, 3, 2, 0, 3, 3],
            [3, 3, 3, 2, 0, 0], [3, 3, 3, 2, 0, 3], [3, 3, 3, 3, 2, 0], [3, 3, 3, 3, 3, 3],
        ];
        let decode_frm_expstr = |code: usize| -> [u8; 6] {
            FRM_EXPSTR_BLOCKS[code & 31]
        };

        let nch = hdr.fbw_channels;
        let num_blocks = hdr.num_blocks;

        // audfrm() header (FFmpeg libavcodec/eac3dec.c ff_eac3_parse_header order)
        let (ac3_exp_strat, ahte) = if num_blocks == 6 {
            (r.read_bool()?, r.read_bool()?)
        } else {
            (true, false)
        };
        let snroffststr = r.read_bits(2)?;
        let transproce = r.read_bool()?;
        let blkswe = r.read_bool()?;
        let dithflage = r.read_bool()?;
        let bamode = r.read_bool()?;
        let frmfgaincode = r.read_bool()?;
        let dbaflde = r.read_bool()?;
        let skipflde = r.read_bool()?;
        let spxattene = r.read_bool()?;

        let mut cplstre = [true; 6];
        let mut cplinu_blk = [false; 6];
        if nch > 1 {
            cplstre[0] = true;
            cplinu_blk[0] = r.read_bool()?;
            for blk in 1..num_blocks {
                cplstre[blk] = r.read_bool()?;
                cplinu_blk[blk] = if cplstre[blk] {
                    r.read_bool()?
                } else {
                    cplinu_blk[blk - 1]
                };
            }
        }

        let mut exp_strats = [[EXP_REUSE; MAX_CHANNELS]; 6];
        let mut cpl_strats = [EXP_REUSE; 6];
        let mut lfe_strats = [EXP_REUSE; 6];

        if ac3_exp_strat {
            for blk in 0..num_blocks {
                if cplinu_blk[blk] {
                    cpl_strats[blk] = r.read_bits(2)? as u8;
                }
                for ch in 0..nch {
                    exp_strats[blk][ch] = r.read_bits(2)? as u8;
                }
            }
        } else {
            if cplinu_blk.iter().any(|&c| c) {
                let code = r.read_bits(5)? as usize;
                cpl_strats = decode_frm_expstr(code);
            }
            for ch in 0..nch {
                let code = r.read_bits(5)? as usize;
                let seq = decode_frm_expstr(code);
                for blk in 0..6 {
                    exp_strats[blk][ch] = seq[blk];
                }
            }
        }
        if hdr.lfeon {
            for blk in 0..num_blocks {
                lfe_strats[blk] = r.read_bits(1)? as u8;
            }
        }

        if num_blocks == 6 || r.read_bool()? {
            // converter channel exponent strategy (ETSI TS 102 366 Section E.1.2.2)
            r.skip(5 * nch)?;
        }

        if ahte {
            return Err(Eac3Error::DecodeFailed("E-AC-3 AHT not supported"));
        }

        if snroffststr == 0 {
            let csnr = r.read_bits(6)? as i16;
            let fsnr = r.read_bits(4)? as i16;
            let snr = (((csnr - 15) << 4) + fsnr) << 2;
            self.state.csnroffst = csnr;
            self.state.cpl_snroffset = snr;
            self.state.lfe_snroffset = snr;
            for ch in 0..nch {
                self.state.channels[ch].snroffset = snr;
            }
        }

        if transproce {
            for _ in 0..nch {
                if r.read_bool()? {
                    r.skip(18)?;
                }
            }
        }
        if spxattene {
            for _ in 0..nch {
                if r.read_bool()? {
                    r.skip(5)?;
                }
            }
        }
        if num_blocks > 1 && r.read_bool()? {
            let frame_words = (hdr.frame_bytes / 2).max(1) as u32;
            let nbits = 4 + (32 - (frame_words - 1).leading_zeros()) as usize;
            r.skip((num_blocks - 1) * nbits)?;
        }
        if !bamode {
            self.state.ba = BitAllocParams::from_codes(2, 1, 1, 2, 7);
        }
        if !frmfgaincode {
            self.state.cpl_fgain = 640;
            self.state.lfe_fgain = 640;
            for ch in 0..nch {
                self.state.channels[ch].fgain = 640;
            }
        }
        if nch == 5 {
            // 5.1 audfrm parsed
        }
        const DEFAULT_CPL_BAND_STRUCT: [bool; 18] = [
            false, false, false, false, false, false, false, false, true,
            false, true, true, false, true, true, true, true, true,
        ];
        let mut first_cpl_coords = [true; MAX_CHANNELS];
        let mut first_cpl_leak = true;
        for blk in 0..num_blocks {
            if blkswe {
                for ch in 0..nch {
                    self.state.channels[ch].blksw = r.read_bool()?;
                }
            } else {
                for ch in 0..nch {
                    self.state.channels[ch].blksw = false;
                }
            }
            if dithflage {
                for ch in 0..nch {
                    self.state.channels[ch].dithflag = r.read_bool()?;
                }
            } else {
                for ch in 0..nch {
                    self.state.channels[ch].dithflag = true;
                }
            }
            self.state.cplinu = cplinu_blk[blk];
            for ch in 0..nch {
                self.state.channels[ch].exp_strategy = exp_strats[blk][ch];
            }
            self.state.cpl_exp_strategy = cpl_strats[blk];
            self.state.lfe_exp_strategy = lfe_strats[blk];

            if r.read_bool()? {
                // dynrnge
                r.skip(8)?;
            }
            if hdr.acmod == 0 && r.read_bool()? {
                r.skip(8)?;
            }

            let spxstre = if blk == 0 { true } else { r.read_bool()? };
            if spxstre {
                let spxinu = r.read_bool()?;
                if spxinu {
                    return Err(Eac3Error::DecodeFailed("E-AC-3 SPX not supported"));
                }
            }

            if self.state.cplinu && cplstre[blk] {
                if r.read_bool()? {
                    return Err(Eac3Error::DecodeFailed("E-AC-3 ecpl not supported"));
                }
                if hdr.acmod == 2 {
                    self.state.chincpl[0] = true;
                    self.state.chincpl[1] = true;
                } else {
                    for ch in 0..nch {
                        self.state.chincpl[ch] = r.read_bool()?;
                    }
                }
                if hdr.acmod == 2 {
                    self.state.phsflginu = r.read_bool()?;
                }
                self.state.cplbegf = r.read_bits(4)? as usize;
                self.state.cplendf = r.read_bits(4)? as usize;
                let ncplsubnd = (3 + self.state.cplendf).saturating_sub(self.state.cplbegf);
                self.state.cpl_start_bin = self.state.cplbegf * 12 + 37;
                self.state.cpl_end_bin = self.state.cpl_start_bin + ncplsubnd * 12;
                let cplbndstrce = r.read_bool()?;
                let mut bnd_idx = 0usize;
                self.state.cpl_bnd_bins[0] = self.state.cpl_start_bin;
                for sub in 0..ncplsubnd.min(18) {
                    let combined = if sub > 0 {
                        if cplbndstrce {
                            r.read_bool()?
                        } else {
                            DEFAULT_CPL_BAND_STRUCT[(self.state.cplbegf + sub).min(17)]
                        }
                    } else {
                        false
                    };
                    if !combined {
                        bnd_idx += 1;
                    }
                    self.state.cpl_bnd_bins[bnd_idx] = self.state.cpl_start_bin + (sub + 1) * 12;
                }
                self.state.ncplbnd = bnd_idx;
            } else if !self.state.cplinu {
                for ch in 0..nch {
                    self.state.chincpl[ch] = false;
                    first_cpl_coords[ch] = true;
                }
                first_cpl_leak = true;
                self.state.phsflginu = false;
            }

            if self.state.cplinu {
                let mut any = false;
                let ncplbnd = self.state.ncplbnd.min(18);
                for ch in 0..nch {
                    if self.state.chincpl[ch] {
                        if first_cpl_coords[ch] || r.read_bool()? {
                            first_cpl_coords[ch] = false;
                            any = true;
                            let mstr = r.read_bits(2)?;
                            for bnd in 0..ncplbnd {
                                let exp = r.read_bits(4)?;
                                let mant = r.read_bits(4)?;
                                let scale = if exp == 15 {
                                    (mant as f32) / 16.0
                                } else {
                                    ((mant as f32) + 16.0) / 32.0 * 2.0f32.powi(-(exp as i32))
                                };
                                self.state.cplco[ch][bnd] =
                                    scale * 2.0f32.powi(-3 * (mstr as i32)) * 8.0;
                            }
                        }
                    } else {
                        first_cpl_coords[ch] = true;
                    }
                }
                if hdr.acmod == 2 && self.state.phsflginu && any {
                    for bnd in 0..ncplbnd {
                        self.state.phsflg[bnd] = r.read_bool()?;
                    }
                }
            }

            if hdr.acmod == 2 && (blk == 0 || r.read_bool()?) {
                let nb = if self.state.cplinu && self.state.cpl_start_bin <= 61 {
                    if self.state.cpl_start_bin <= 37 { 2 } else { 3 }
                } else {
                    4
                };
                for b in 0..nb {
                    self.state.rematflg[b] = r.read_bool()?;
                }
            }

            for ch in 0..nch {
                if self.state.channels[ch].exp_strategy != EXP_REUSE {
                    if self.state.cplinu && self.state.chincpl[ch] {
                        self.state.channels[ch].end_bin = self.state.cpl_start_bin;
                    } else {
                        let chbwcod = r.read_bits(6)? as usize;
                        self.state.channels[ch].end_bin = ((chbwcod + 12) * 3 + 37).min(253);
                    }
                }
            }

            if self.state.cplinu && self.state.cpl_exp_strategy != EXP_REUSE {
                let cplabsexp = r.read_bits(4)? as u8;
                let ncplbins = self.state.cpl_end_bin.saturating_sub(self.state.cpl_start_bin);
                let grp_sz = match self.state.cpl_exp_strategy {
                    1 => 3,
                    2 => 6,
                    _ => 12,
                };
                let ngrps = (ncplbins + grp_sz - 1) / grp_sz;
                decode_exponents(
                    r,
                    self.state.cpl_exp_strategy,
                    ngrps,
                    cplabsexp << 1,
                    &mut self.state.cpl_exps[self.state.cpl_start_bin..self.state.cpl_end_bin],
                )?;
            }

            for ch in 0..nch {
                let strat = self.state.channels[ch].exp_strategy;
                if strat != EXP_REUSE {
                    let first_exp = r.read_bits(4)? as u8;
                    let end_bin = self.state.channels[ch].end_bin;
                    let nbins = end_bin.saturating_sub(1);
                    let grp_sz = match strat {
                        1 => 3,
                        2 => 6,
                        _ => 12,
                    };
                    let ngrps = (nbins + grp_sz - 1) / grp_sz;
                    self.state.channels[ch].exps[0] = first_exp;
                    decode_exponents(
                        r,
                        strat,
                        ngrps,
                        first_exp,
                        &mut self.state.channels[ch].exps[1..end_bin],
                    )?;
                    r.skip(2)?; // gainrng
                }
            }

            if hdr.lfeon && self.state.lfe_exp_strategy != EXP_REUSE {
                let first = r.read_bits(4)? as u8;
                self.state.lfe_exps[0] = first;
                decode_exponents(r, 1, 2, first, &mut self.state.lfe_exps[1..7])?;
            }

            if bamode && r.read_bool()? {
                let sd = r.read_bits(2)? as usize;
                let fd = r.read_bits(2)? as usize;
                let sg = r.read_bits(2)? as usize;
                let db = r.read_bits(2)? as usize;
                let fl = r.read_bits(3)? as usize;
                self.state.ba = BitAllocParams::from_codes(sd, fd, sg, db, fl);
            }
            if snroffststr != 0 && r.read_bool()? {
                self.state.csnroffst = r.read_bits(6)? as i16;
                if snroffststr == 1 {
                    let fsnr = r.read_bits(4)? as i16;
                    let snr = (((self.state.csnroffst - 15) << 4) + fsnr) << 2;
                    self.state.cpl_snroffset = snr;
                    self.state.lfe_snroffset = snr;
                    for ch in 0..nch {
                        self.state.channels[ch].snroffset = snr;
                    }
                } else {
                    if self.state.cplinu {
                        let fsnroffst = r.read_bits(4)? as i16;
                        self.state.cpl_snroffset =
                            (((self.state.csnroffst - 15) << 4) + fsnroffst) << 2;
                    }
                    for ch in 0..nch {
                        let fsnroffst = r.read_bits(4)? as i16;
                        self.state.channels[ch].snroffset =
                            (((self.state.csnroffst - 15) << 4) + fsnroffst) << 2;
                    }
                    if hdr.lfeon {
                        let fsnroffst = r.read_bits(4)? as i16;
                        self.state.lfe_snroffset =
                            (((self.state.csnroffst - 15) << 4) + fsnroffst) << 2;
                    }
                }
            }
            if frmfgaincode && r.read_bool()? {
                if self.state.cplinu {
                    self.state.cpl_fgain = ((r.read_bits(3)? as i16) << 7) + 128;
                }
                for ch in 0..nch {
                    self.state.channels[ch].fgain = ((r.read_bits(3)? as i16) << 7) + 128;
                }
                if hdr.lfeon {
                    self.state.lfe_fgain = ((r.read_bits(3)? as i16) << 7) + 128;
                }
            }
            if self.state.cplinu {
                if first_cpl_leak || r.read_bool()? {
                    let fleak = r.read_bits(3)? as i16;
                    let sleak = r.read_bits(3)? as i16;
                    self.state.cpl_fastleak = (fleak << 8) + 768;
                    self.state.cpl_slowleak = (sleak << 8) + 768;
                }
                first_cpl_leak = false;
            }
            if hdr.is_eac3 && snroffststr == 0 && r.read_bool()? {
                r.skip(10)?; // convsnroffst
            }
            if dbaflde && r.read_bool()? {
                if self.state.cplinu && r.read_bits(2)? == 1 {
                    let n = r.read_bits(3)? as usize + 1;
                    r.skip(n * 12)?;
                }
                for _ in 0..nch {
                    if r.read_bits(2)? == 1 {
                        let n = r.read_bits(3)? as usize + 1;
                        r.skip(n * 12)?;
                    }
                }
            }
            if skipflde && r.read_bool()? {
                let skipl = r.read_bits(9)? as usize;
                r.skip(skipl * 8)?;
            }

            self.run_bit_allocation(hdr);
            self.decode_mantissas(r, hdr)?;

            let mut time_chs = [[0.0f32; 256]; MAX_CHANNELS];
            self.synthesize_block(hdr, &mut time_chs)?;
            let slice = &mut out[blk * BLOCK_SAMPLES * 2..(blk + 1) * BLOCK_SAMPLES * 2];
            downmix_to_stereo(
                hdr.acmod,
                &time_chs[..nch],
                self.state.cmixlev,
                self.state.surmixlev,
                slice,
            );
        }
        Ok(())
    }

    fn run_bit_allocation(&mut self, hdr: &FrameHeader) {
        let ba = &self.state.ba;
        for ch in 0..hdr.fbw_channels {
            let c = &mut self.state.channels[ch];
            compute_bap(
                hdr.fscod,
                0,
                c.end_bin,
                &c.exps,
                ba.sdecay,
                ba.fdecay,
                ba.sgain,
                ba.dbknee,
                ba.floor,
                c.fgain,
                c.snroffset,
                None,
                None,
                &mut c.bap,
            );
        }
        if self.state.cplinu {
            compute_bap(
                hdr.fscod,
                self.state.cpl_start_bin,
                self.state.cpl_end_bin,
                &self.state.cpl_exps,
                ba.sdecay,
                ba.fdecay,
                ba.sgain,
                ba.dbknee,
                ba.floor,
                self.state.cpl_fgain,
                self.state.cpl_snroffset,
                Some(self.state.cpl_fastleak),
                Some(self.state.cpl_slowleak),
                &mut self.state.cpl_bap,
            );
        }
        if hdr.lfeon {
            compute_bap(
                hdr.fscod,
                0,
                7,
                &self.state.lfe_exps,
                ba.sdecay,
                ba.fdecay,
                ba.sgain,
                ba.dbknee,
                ba.floor,
                self.state.lfe_fgain,
                self.state.lfe_snroffset,
                None,
                None,
                &mut self.state.lfe_bap,
            );
        }
    }

    fn decode_mantissas(&mut self, r: &mut BitReader<'_>, hdr: &FrameHeader) -> Result<(), Eac3Error> {
        self.mant.reset_groups();
        let mut ch_coeffs = [[0.0f32; 256]; MAX_CHANNELS];
        let mut cpl_coeffs = [0.0f32; 256];
        let mut got_cpl = false;

        for ch in 0..hdr.fbw_channels {
            let c = &self.state.channels[ch];
            self.mant.decode_channel(
                r,
                0,
                c.end_bin,
                &c.exps,
                &c.bap,
                c.dithflag,
                &mut ch_coeffs[ch],
            )?;
            if self.state.cplinu && self.state.chincpl[ch] && !got_cpl {
                self.mant.decode_channel(
                    r,
                    self.state.cpl_start_bin,
                    self.state.cpl_end_bin,
                    &self.state.cpl_exps,
                    &self.state.cpl_bap,
                    false,
                    &mut cpl_coeffs,
                )?;
                got_cpl = true;
            }
        }

        if hdr.lfeon {
            let mut lfe_coeffs = [0.0f32; 7];
            self.mant.decode_channel(
                r,
                0,
                7,
                &self.state.lfe_exps,
                &self.state.lfe_bap,
                false,
                &mut lfe_coeffs,
            )?;
        }

        if self.state.cplinu {
            let ncplbnd = self.state.ncplbnd.min(18);
            for ch in 0..hdr.fbw_channels {
                if !self.state.chincpl[ch] {
                    continue;
                }
                for bnd in 0..ncplbnd {
                    let start = self.state.cpl_bnd_bins[bnd];
                    let end = self.state.cpl_bnd_bins[bnd + 1].min(self.state.cpl_end_bin);
                    let mut scale = self.state.cplco[ch][bnd];
                    if ch == 1 && self.state.phsflginu && self.state.phsflg[bnd] {
                        scale = -scale;
                    }
                    for bin in start..end {
                        ch_coeffs[ch][bin] = cpl_coeffs[bin] * scale;
                    }
                }
            }
        }

        if hdr.acmod == 2 {
            let end_bin = self.state.channels[0].end_bin.min(self.state.channels[1].end_bin);
            let (left_slice, right_slice) = ch_coeffs.split_at_mut(1);
            apply_rematrixing(&mut left_slice[0], &mut right_slice[0], &self.state.rematflg, end_bin);
        }

        // Store into block synthesis via imdct in synthesize_block by storing on self?
        // Actually synthesize_block can run right here or we can return ch_coeffs.
        self.cached_coeffs = ch_coeffs;
        Ok(())
    }

    fn synthesize_block(
        &mut self,
        hdr: &FrameHeader,
        time_chs: &mut [[f32; 256]; MAX_CHANNELS],
    ) -> Result<(), Eac3Error> {
        for ch in 0..hdr.fbw_channels {
            self.imdct.process_channel(
                ch,
                self.state.channels[ch].blksw,
                &self.cached_coeffs[ch],
                &mut time_chs[ch],
            );
        }
        Ok(())
    }
}


