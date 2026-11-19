package app.cadence.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.cadence.state.settings.UpdateChannel
import app.cadence.state.settings.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val prerelease: Boolean,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkSizeBytes: Long,
    val isChannelSwitch: Boolean = false,
    val isDowngrade: Boolean = false,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(
        val release: ReleaseInfo,
        val progress: Float?,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkFile: File,
        val needsInstallPermission: Boolean,
    ) : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Parsed semver with optional prerelease (e.g. `v0.2.0-dev.1`). */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String?,
) : Comparable<SemVer> {
    val isPrerelease: Boolean get() = !prerelease.isNullOrBlank()

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (prerelease == other.prerelease) return 0
        if (prerelease == null) return 1
        if (other.prerelease == null) return -1
        val aParts = prerelease.split('.', '-')
        val bParts = other.prerelease.split('.', '-')
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val a = aParts.getOrNull(i) ?: return -1
            val b = bParts.getOrNull(i) ?: return 1
            val aNum = a.toIntOrNull()
            val bNum = b.toIntOrNull()
            val cmp = when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1
                bNum != null -> 1
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        fun parse(raw: String): SemVer? {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            val dash = cleaned.indexOf('-')
            val core = if (dash >= 0) cleaned.substring(0, dash) else cleaned
            val pre = if (dash >= 0) cleaned.substring(dash + 1).takeIf { it.isNotBlank() } else null
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            return SemVer(
                major = nums.getOrElse(0) { 0 },
                minor = nums.getOrElse(1) { 0 },
                patch = nums.getOrElse(2) { 0 },
                prerelease = pre,
            )
        }
    }
}

class UpdateChecker(
    private val context: Context,
    private val settings: JsonStore<UserSettings>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val updatesDir = File(context.cacheDir, "updates")
    private var downloadJob: Job? = null

    val currentVersionName: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("0.0.0").ifBlank { "0.0.0" }

    init {
        scope.launch(Dispatchers.IO) { cleanUpdatesDir() }
    }

    fun checkIfDue() {
        val s = settings.state.value
        if (!s.autoCheckUpdates) return
        val now = System.currentTimeMillis()
        if (now - s.lastUpdateCheckMs < AUTO_CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    fun check(manual: Boolean, channelOverride: UpdateChannel? = null) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val channel = channelOverride ?: settings.state.value.updateChannel
        _state.value = UpdateState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatest(channel) }
            settings.update { it.copy(lastUpdateCheckMs = System.currentTimeMillis()) }
            result.fold(
                onSuccess = { release ->
                    if (release == null) {
                        _state.value = if (manual) UpdateState.UpToDate(currentVersionName) else UpdateState.Idle
                    } else {
                        val skipped = settings.state.value.skippedUpdateTag
                        if (!manual && !release.isChannelSwitch && skipped == release.tag) {
                            _state.value = UpdateState.Idle
                        } else {
                            _state.value = UpdateState.Available(release)
                        }
                    }
                },
                onFailure = { err ->
                    _state.value = if (manual) {
                        UpdateState.Error(err.message ?: "Failed to check updates")
                    } else {
                        UpdateState.Idle
                    }
                },
            )
        }
    }

    fun startDownload(release: ReleaseInfo) {
        val url = release.apkUrl ?: return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(release, progress = null, downloadedBytes = 0L, totalBytes = release.apkSizeBytes)
            val result = withContext(Dispatchers.IO) {
                cleanUpdatesDir()
                updatesDir.mkdirs()
                val safeTag = release.tag.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val partFile = File(updatesDir, "$safeTag.apk.part")
                val finalFile = File(updatesDir, "$safeTag.apk")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Cadence-Android/$currentVersionName")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) error("HTTP $code")
                    val total = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
                    var downloaded = 0L
                    var lastEmit = 0L
                    conn.inputStream.use { input ->
                        partFile.outputStream().use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (isActive) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 120L || (total > 0 && downloaded >= total)) {
                                    lastEmit = now
                                    val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                                    _state.value = UpdateState.Downloading(release, frac, downloaded, total)
                                }
                            }
                        }
                    }
                    if (!isActive) throw CancellationException()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }
                    finalFile
                } catch (t: Throwable) {
                    partFile.delete()
                    throw t
                } finally {
                    conn.disconnect()
                }
            }
            val needsPerm = !context.packageManager.canRequestPackageInstalls()
            _state.value = UpdateState.ReadyToInstall(release, result, needsPerm)
            if (!needsPerm) installApk(result)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        scope.launch(Dispatchers.IO) { cleanUpdatesDir() }
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    fun skipVersion(tag: String) {
        settings.update { it.copy(skippedUpdateTag = tag) }
        _state.value = UpdateState.Idle
    }

    fun installOrRequestPermission(apkFile: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        installApk(apkFile)
    }

    fun refreshInstallPermission(state: UpdateState.ReadyToInstall) {
        val needsPerm = !context.packageManager.canRequestPackageInstalls()
        if (needsPerm != state.needsInstallPermission) {
            _state.value = state.copy(needsInstallPermission = needsPerm)
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun cleanUpdatesDir() {
        if (!updatesDir.isDirectory) return
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    private fun fetchLatest(channel: UpdateChannel): Result<ReleaseInfo?> = runCatching {
        val url = when (channel) {
            UpdateChannel.Release -> "$RELEASES_API/latest"
            UpdateChannel.Dev -> "$RELEASES_API?per_page=10"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Cadence-Android/$currentVersionName")
        }
        try {
            val code = conn.responseCode
            if (code == 404 && channel == UpdateChannel.Release) return@runCatching null
            if (code !in 200..299) error("GitHub HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            evaluateReleasePayload(body, channel, currentVersionName)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val RELEASES_API = "https://api.github.com/repos/dstdoll/cadence/releases"
        private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

        internal fun evaluateReleasePayload(
            body: String,
            channel: UpdateChannel,
            currentVersion: String,
        ): ReleaseInfo? {
            val root = Json.parseToJsonElement(body)
            val json = when (channel) {
                UpdateChannel.Release -> root.jsonObject
                UpdateChannel.Dev -> {
                    root.jsonArray
                        .map { it.jsonObject }
                        .firstOrNull { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
                        ?: return null
                }
            }
            if (json["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
            val tag = json["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (tag.isBlank()) return null

            val installedVer = SemVer.parse(currentVersion)
            val remoteVer = SemVer.parse(tag)
            val isChannelSwitch = channel == UpdateChannel.Release &&
                installedVer?.isPrerelease == true &&
                remoteVer?.isPrerelease == false

            val isNewer = if (installedVer != null && remoteVer != null) {
                remoteVer > installedVer
            } else {
                tag.removePrefix("v") != currentVersion.removePrefix("v")
            }

            if (!isNewer && !isChannelSwitch) return null

            val isDowngrade = installedVer != null && remoteVer != null && remoteVer < installedVer
            val assets = json["assets"]?.jsonArray
            var apkUrl: String? = null
            var apkSize = 0L
            if (assets != null) {
                for (elem in assets) {
                    val a = elem.jsonObject
                    val name = a["name"]?.jsonPrimitive?.content.orEmpty()
                    val type = a["content_type"]?.jsonPrimitive?.content.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true) ||
                        type == "application/vnd.android.package-archive"
                    ) {
                        apkUrl = a["browser_download_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        apkSize = a["size"]?.jsonPrimitive?.longOrNull ?: 0L
                        break
                    }
                }
            }
            return ReleaseInfo(
                tag = tag,
                title = json["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: tag,
                notes = json["body"]?.jsonPrimitive?.content?.trim().orEmpty(),
                prerelease = json["prerelease"]?.jsonPrimitive?.booleanOrNull == true,
                htmlUrl = json["html_url"]?.jsonPrimitive?.content.orEmpty(),
                apkUrl = apkUrl,
                apkSizeBytes = apkSize,
                isChannelSwitch = isChannelSwitch,
                isDowngrade = isDowngrade,
            )
        }
    }
}
