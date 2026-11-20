package app.cadence.backend.engine.media

/** A master playlist with the given renditions (the same attribute shapes as the real fixture), for tests. */
internal class MasterBuilder(private val lines: MutableList<String> = mutableListOf("#EXTM3U", "#EXT-X-VERSION:7")) {
    private var n = 0
    fun lossless(depth: Int, rate: Int) = add("audio-alac-stereo-$rate-$depth", "alac", 1_500_000L + rate / 100, "2", ",SAMPLE-RATE=$rate,BIT-DEPTH=$depth")
    fun aac(kbps: Int) = add("audio-stereo-$kbps", "mp4a.40.2", kbps * 1090L, "2")
    fun he(kbps: Int = 64) = add("audio-HE-stereo-$kbps", "mp4a.40.5", kbps * 1150L, "2")
    fun binaural(kbps: Int) = add("audio-stereo-$kbps-binaural", "mp4a.40.2", kbps * 1090L, "2/-/BINAURAL")
    fun atmos(kbps: Int) = add("audio-atmos-2$kbps", "ec-3", kbps * 1003L, "16/JOC")
    private fun add(id: String, codecs: String, bw: Long, ch: String, extra: String = ""): MasterBuilder {
        lines.add(lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }.let { if (it < 0) lines.size else it },
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"$id\",AUTOSELECT=YES,CHANNELS=\"$ch\",NAME=\"songEnhanced\"$extra")
        lines.add("#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=${bw - 1000},BANDWIDTH=$bw,CODECS=\"$codecs\",STABLE-VARIANT-ID=\"v${++n}\",AUDIO=\"$id\"")
        lines.add("$id.m3u8")
        return this
    }
    fun text() = lines.joinToString("\n")
    fun parse() = HlsVariants.parse(text(), "https://cdn.example.com/assets/P1/master.m3u8")
}

