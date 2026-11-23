package app.cadence.state.settings

import androidx.annotation.StringRes
import app.cadence.R
import kotlinx.serialization.Serializable

enum class ColorSource { Wallpaper, Artwork, Custom, Monochrome }
enum class DarkMode { System, Light, Dark }
enum class ContrastLevel { Standard, Medium, High }
enum class LyricsAlign { Start, Center }
/** How the seek bar in Now Playing is drawn. [Material] is the stock wavy indicator; the rest draw the track's real waveform. */
enum class WaveStyle(@StringRes val labelRes: Int) {
    Material(R.string.set_wave_material), Bars(R.string.set_wave_bars), Mirror(R.string.set_wave_mirror),
    Line(R.string.set_wave_line), Neon(R.string.set_wave_neon),
}
enum class AlbumDateStyle(@StringRes val labelRes: Int) {
    FullDate(R.string.set_date_full), YearOnly(R.string.set_date_year), None(R.string.set_none),
}
enum class UpdateChannel(@StringRes val labelRes: Int) {
    Release(R.string.set_update_channel_release), Dev(R.string.set_update_channel_dev),
}

@Serializable
data class UserSettings(
    // Appearance
    val colorSource: ColorSource = ColorSource.Wallpaper,
    val customSeed: Long = 0xFF6750A4,
    val darkMode: DarkMode = DarkMode.System,
    val pureBlack: Boolean = false,
    val contrast: ContrastLevel = ContrastLevel.Standard,
    val grayscaleArtwork: Boolean = false,
    val reduceMotion: Boolean = false,
    val motionCovers: Boolean = true,
    val showQuality: Boolean = true,
    // Downloads
    /** Default SAF tree; null = Music/Cadence. */
    val downloadTreeUri: String? = null,
    /** Per-collection overrides; null falls back to [downloadTreeUri]. */
    val albumTreeUri: String? = null,
    val playlistTreeUri: String? = null,
    val singleTreeUri: String? = null,
    /** Folder + file templates. Tokens: {artist} {album} {albumartist} {title}
     *  {track}{track:02d} {disc}{disc:02d} {year} {date} {genre} {playlist}. */
    val albumFolderTemplate: String = "{artist}/{album}",
    val trackFileTemplate: String = "{track:02d} {title}",
    val playlistFolderTemplate: String = "{playlist}",
    val playlistTrackFileTemplate: String = "{track:02d} {artist} - {title}",
    /** Container written after decryption: "m4a" (ALAC in MP4) today. */
    val downloadFormat: String = "m4a",
    /** Write title/artist/album/track into the file. */
    val writeMetadata: Boolean = true,
    /** `©day`: full release date, year only, or omitted. */
    val albumDateTag: AlbumDateStyle = AlbumDateStyle.FullDate,
    /** Preferred ALAC bit depth (24 or 16); used when Apple offers both. */
    val bitDepth: Int = 24,
    /** Highest accepted ALAC sample rate in Hz; 0 = Auto (no ceiling). */
    val maxSampleRate: Int = 0,
    val embedCover: Boolean = true,
    val coverSize: String = "1400x1400",
    val coverFormat: String = "jpg",
    val embedLyrics: Boolean = true,
    val saveLyricsFile: Boolean = false,
    val lyricsFormat: String = "lrc",
    val dropEpSingleSuffix: Boolean = false,
    val checkAlacOnDownload: Boolean = false,

    // Lyrics
    val lyricsScale: Float = 1f,
    val lyricsAlign: LyricsAlign = LyricsAlign.Start,
    val lyricsBlur: Boolean = true,
    val karaoke: Boolean = true,
    val lyricsAnchor: Float = 0.3f,
    val amllLyricsFallback: Boolean = false,
    // Playback
    val autoplay: Boolean = true,
    val skipSilence: Boolean = false,
    val waveStyle: WaveStyle = WaveStyle.Material,
    /** The custom DSP equalizer (biquad peaking bands), applied at the sink so it works in float
     *  output too. Independent of the system [eqEnabled]/[eqBands] above. */
    val dspEq: EqConfig = EqConfig(),
    /** Bass/treble tone controls (shelf filters), applied before [dspEq] at the sink. */
    val dspTone: ToneConfig = ToneConfig(),
    /** Replay-gain / preamp volume levelling, the first sink stage (before tone and eq). */
    val dspReplayGain: ReplayGainConfig = ReplayGainConfig(),
    /** Stereo balance + width, applied after [dspEq] at the sink (before dither). */
    val dspChannel: ChannelConfig = ChannelConfig(),
    /** Dither for the 16-bit output path (the last stage, before quantization). */
    val dspDither: DitherConfig = DitherConfig(),
    /** Seconds of the next song decrypted ahead of time; 0 = off, -1 = the whole song. */
    val streamPreloadNextSec: Int = 30,
    /** Seconds of the previous song kept ready so "back" starts at once; 0 = off. */
    val streamPreloadPreviousSec: Int = 15,
    /** Disk budget for decrypted streaming audio, in MB. */
    val streamCacheMb: Int = 2048,
    /** Whole songs kept ready after and before the playing one, in MB; 0 = only the preload above. */
    val streamAheadMb: Int = 500,
    val streamBehindMb: Int = 500,
    /** Whether that window may also be filled over a metered connection. */
    val streamWindowOnMobile: Boolean = false,
    /** Decode to 32-bit float so 24-bit masters are not reduced to 16-bit on output. */
    val hiResOutput: Boolean = true,
    /** Hand USB DACs the samples untouched through a bit-perfect mixer (Android 14+). */
    val bitPerfectUsb: Boolean = false,
    /** "alac" (lossless) or "aac" (256 kbps) for playback and for downloaded files. */
    val streamCodec: String = "alac",
    /** Dolby Atmos for playback: "off", "spatial" (binaural AAC for headphones) or "atmos" (E-AC-3 JOC). */
    val streamAtmos: String = "off",
    val downloadCodec: String = "alac",
    /**
     * Whether a download asks which quality to use ("ask", the default) or just uses [downloadQuality] ("default").
     * The picker says it can be changed here.
     */
    val downloadQualityMode: String = "ask",
    /**
     * The default download quality as a [app.cadence.backend.engine.media.RenditionSpec] string (`alac:24:0` is
     * "highest available", `aac:128`, ...). Null reads the older format/bit depth/sample rate settings, so an
     * upgrade keeps what the person had chosen.
     */
    val downloadQuality: String? = null,
    /** Dolby Atmos for downloaded files: "off", "spatial" (binaural AAC) or "atmos" (E-AC-3 JOC). */
    val downloadAtmos: String = "off",
    /**
     * Reuse the playback cache when downloading, so a track you just played downloads almost
     * instantly (and a download warms the cache for later plays). Off keeps a separate download
     * cache, isolated from playback. Only applies when download and streaming quality match.
     */
    val shareDownloadCache: Boolean = true,
    // Library
    val addFavoritesToLibrary: Boolean = true,
    val addPlaylistSongsToLibrary: Boolean = true,
    val listeningHistory: Boolean = true,
    val resumeOnLaunch: Boolean = true,
    // Feel
    val haptics: Boolean = true,
    // Updates
    val updateChannel: UpdateChannel = UpdateChannel.Release,
    val autoCheckUpdates: Boolean = true,
    val skippedUpdateTag: String? = null,
    val lastUpdateCheckMs: Long = 0L,
)

/** Which loudness reference replay gain levels to, when a track carries the matching tag. */
enum class RgMode { Track, Album }

/**
 * Dither type for 16-bit quantization. [Rectangular] (RPDF) and [Triangular] (TPDF) are the standard
 * statistical dithers; [Shaped] adds first-order error-feedback noise shaping on top of TPDF, pushing
 * the quantization noise up out of the most audible band.
 */
enum class DitherType(@StringRes val labelRes: Int) {
    None(R.string.set_none), Rectangular(R.string.set_dither_rect), Triangular(R.string.set_dither_tri), Shaped(R.string.set_dither_shaped),
}

/**
 * Dither configuration, persisted with [UserSettings]. Dither only matters where audio is reduced to
 * 16 bits (hi-res output off, or a 16-bit DAC); on a float output path there is no quantization to
 * dither, so the stage stays idle. [Triangular] is the safe default when enabled.
 */
@Serializable
data class DitherConfig(
    val enabled: Boolean = false,
    val type: DitherType = DitherType.Triangular,
)

/**
 * Replay-gain / preamp configuration, persisted with [UserSettings]. Replay gain levels every track to
 * a common loudness so volume doesn't jump between songs. It needs a per-track loudness value: Apple's
 * stream carries none, so until a loudness-analysis pass measures them, [noInfoDb] (the "preamp when
 * info is missing") is the gain that actually applies. [preampDb] is an extra trim on top, in every
 * mode. [preventClip] hard-limits the stage output to full scale so a boost can't clip.
 */
@Serializable
data class ReplayGainConfig(
    val enabled: Boolean = false,
    val mode: RgMode = RgMode.Track,
    /** Master trim applied on top of the levelling gain, in dB. */
    val preampDb: Float = 0f,
    /** Gain for tracks with no replay-gain info (all Apple tracks today), in dB. */
    val noInfoDb: Float = 0f,
    /** Clamp the stage output to [-1, 1] so a positive gain can't clip. */
    val preventClip: Boolean = true,
)

/**
 * Stereo balance + width, persisted with [UserSettings]. Applies only to stereo audio. Centred
 * ([balance] 0) with normal [width] 1 is a no-op, so the stage stays idle until something is moved.
 */
@Serializable
data class ChannelConfig(
    val enabled: Boolean = false,
    /** -1 full left … 0 centre … +1 full right. */
    val balance: Float = 0f,
    /** 0 mono … 1 normal stereo … 2 maximally wide. */
    val width: Float = 1f,
)

/** The bass/treble tone controls, persisted with [UserSettings]. Flat (0 dB) is a no-op. */
@Serializable
data class ToneConfig(
    val enabled: Boolean = false,
    /** Low-shelf gain below 100 Hz, in dB. */
    val bassDb: Float = 0f,
    /** High-shelf gain above 10 kHz, in dB. */
    val trebleDb: Float = 0f,
)

/** The filter shapes of the equalizer: the RBJ cookbook peaking, low-shelf and high-shelf. */
@Serializable
enum class EqFilterType { Peaking, LowShelf, HighShelf }

/**
 * One band of the custom DSP equalizer: a filter of [type] at [freq] Hz, [gainDb] boost/cut, width or
 * shelf slope [q]. The graphic bands are all peaking; a headphone profile also uses shelves.
 */
@Serializable
data class EqBandSetting(val freq: Int, val gainDb: Float = 0f, val q: Float = 1f, val type: EqFilterType = EqFilterType.Peaking)

/**
 * A headphone correction loaded from an AutoEQ `ParametricEQ.txt`: up to [MAX_FILTERS] filters and the
 * file's preamp. It runs before the graphic bands, so the bands stay free for taste on top of it.
 */
@Serializable
data class HeadphoneProfile(
    /** What to show: the file name without its "ParametricEQ" suffix, e.g. "Beyerdynamic DT 770 Pro (80 Ohm)". */
    val name: String,
    /** The file's own preamp (negative), used when auto headroom is off. */
    val preampDb: Float,
    val filters: List<EqBandSetting>,
) {
    companion object {
        const val MAX_FILTERS = 32
    }
}

/** The custom DSP equalizer configuration, persisted with [UserSettings]. */
@Serializable
data class EqConfig(
    val enabled: Boolean = false,
    /** Output trim applied before the bands, to claw back headroom after boosts (dB). */
    val preampDb: Float = 0f,
    val bands: List<EqBandSetting> = defaultBands,
    /** Lowers the level by the largest boost of the equalizer, tone and width, so they cannot clip. */
    val autoHeadroom: Boolean = true,
    /** A loaded AutoEQ headphone correction, or null. */
    val headphone: HeadphoneProfile? = null,
) {
    companion object {
        /** A standard 10-band graphic layout (ISO octave centres), all flat. */
        val defaultBands: List<EqBandSetting> =
            listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000).map { EqBandSetting(it) }
    }
}
