package app.cadence.backend.engine.media

object NativeDrm {
    init { NativeLibrary.loadOrThrow("cadence_android_drm") }
    external fun decryptFile(templateJson: String, inputPath: String, outputPath: String): Int
    external fun decryptFileWithKd(kd: Long, inputPath: String, outputPath: String): Int
    external fun decryptFileWithKdPlan(kds: LongArray, inputPath: String, outputPath: String): Int
    external fun decryptFileWithKdPlanMeta(
        kds: LongArray,
        inputPath: String,
        outputPath: String,
        metaJson: String,
        cover: ByteArray?,
        coverMime: String?,
        workers: Int,
    ): Int

    /**
     * Muxes an already-clear concatenated fragmented MP4 (clear init + every clear segment, the
     * same bytes [clearSegment] produces for playback) into a tagged, faststarted `.m4a`. No key
     * plan: the per-segment FairPlay decrypt already happened as each segment was fetched. Output
     * is identical to a full [decryptFileWithKdPlanMeta] of the same track. Returns 0 on success.
     */
    external fun muxClearWithMeta(
        inputPath: String,
        outputPath: String,
        metaJson: String,
        cover: ByteArray?,
        coverMime: String?,
    ): Int

    /**
     * Decrypts a non-fragmented `.m4p` file using an `SVSampleDecryptor` context from
     * [NativeWrapper.m4pAsset] and writes a tagged, faststarted `.m4a`. Returns 0 on success.
     */
    external fun decryptM4pWithMeta(
        ctx: Long,
        inputPath: String,
        outputPath: String,
        metaJson: String,
        cover: ByteArray?,
        coverMime: String?,
    ): Int

    /** The package's init segment with its protected sample entries rewritten as clear ALAC. */
    external fun clearInit(init: ByteArray): ByteArray

    /**
     * One HLS segment decrypted with the FairPlay context [kd]: same length and layout as the
     * input, readable as plain fragmented MP4. [init] is the package's original init segment.
     * Throws IOException when the segment cannot be parsed or a sample fails to decrypt.
     */
    external fun clearSegment(kd: Long, init: ByteArray, segment: ByteArray): ByteArray
}
