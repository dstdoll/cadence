package app.cadence.core.design

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import app.cadence.LocalContainer
import app.cadence.R
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_LINES = 5

/** Pick up to five lines, then share them as a designed card image (or copy them as text). */
@Composable
fun ShareLyricsSheet(info: TrackInfo, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipLabel = stringResource(R.string.player_lyrics)
    val scope = rememberCoroutineScope()
    val lines by produceState(emptyList<String>(), info.id) {
        value = when (val l = c.repository.lyrics(info)) {
            is Lyrics.Synced -> l.lines.map { it.text }
            is Lyrics.Plain -> l.text.lines().filter { it.isNotBlank() }
            Lyrics.None -> emptyList()
        }.distinct()
    }
    val picked = remember { mutableStateListOf<Int>() }
    val chosen = picked.sorted().mapNotNull(lines::getOrNull)
    val rawTtml by produceState<String?>(null, info.id) { value = c.repository.rawTtml(info.id) }
    val exportTtmlLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/ttml+xml"),
    ) { uri ->
        val xml = rawTtml ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            android.widget.Toast.makeText(context, context.getString(R.string.lyrics_exported_ttml), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(info.album.id, info.album.colors, Modifier.size(48.dp), RoundedCornerShape(10.dp), coverUrl = info.album.cover)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        if (picked.isEmpty()) stringResource(R.string.share_no_lines) else pluralStringResource(R.plurals.lines_selected, picked.size, picked.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (picked.size >= MAX_LINES) stringResource(R.string.share_max_lines) else stringResource(R.string.share_tap_lines),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!rawTtml.isNullOrBlank()) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val safe = info.track.title.replace(Regex("""[\\/:*?"<>|]+"""), "_").ifBlank { info.id }
                            exportTtmlLauncher.launch("$safe.ttml")
                        },
                    ) { Text(stringResource(R.string.lyrics_export_ttml)) }
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                itemsIndexed(lines) { i, line ->
                    val on = i in picked
                    Text(
                        line,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(2.dp, if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(20.dp))
                            .clickable(enabled = on || picked.size < MAX_LINES) { if (on) picked.remove(i) else picked.add(i) }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = {
                        val text = chosen.joinToString("\n") + "\n— ${info.track.title}, ${info.artist.name}"
                        scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(clipLabel, text))) }
                        onDismiss()
                    },
                    enabled = chosen.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp),
                ) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.action_copy)) }
                Button(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.Default) { LyricCard.render(context, info, chosen) }
                            LyricCard.share(context, file, info)
                            onDismiss()
                        }
                    },
                    enabled = chosen.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp),
                ) { Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.share_card)) }
            }
        }
    }
}

/** Draws the shareable 4:5 lyric card with plain Android graphics (works off the UI thread). */
object LyricCard {
    private const val W = 1080
    private const val H = 1350

    fun render(context: Context, info: TrackInfo, lines: List<String>): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val (c0, c1, _) = ArtworkRenderer.palette(info.album.colors)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), c0, c1, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
        paint.color = AColor.argb(110, 0, 0, 0) // keeps white text legible on bright covers
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)

        val base = ResourcesCompat.getFont(context, R.font.roboto_flex) ?: Typeface.DEFAULT
        val heavy = Typeface.create(base, 800, false)
        val medium = Typeface.create(base, 500, false)

        // Lyrics, as large as fits.
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; typeface = heavy }
        val body = lines.joinToString("\n")
        val maxW = W - 2 * 96
        var size = 92f
        var layout: StaticLayout
        do {
            text.textSize = size
            layout = StaticLayout.Builder.obtain(body, 0, body.length, text, maxW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).build()
            size -= 4f
        } while (layout.height > H - 520 && size > 40f)
        canvas.save()
        canvas.translate(96f, 180f + ((H - 520) - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()

        // Footer: cover, title, artist, app mark.
        val art = ArtworkRenderer.bitmap(info.album.id, info.album.colors)
        val artRect = RectF(96f, H - 240f, 96f + 144f, H - 96f)
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(artRect, 24f, 24f, android.graphics.Path.Direction.CW) })
        canvas.drawBitmap(art, null, artRect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; typeface = heavy; textSize = 44f }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(210, 255, 255, 255); typeface = medium; textSize = 36f }
        canvas.drawText(ellipsize(info.track.title, title, W - 400f), 272f, H - 176f, title)
        canvas.drawText(ellipsize(info.artist.name, sub, W - 400f), 272f, H - 120f, sub)
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(170, 255, 255, 255); typeface = heavy; textSize = 30f; textAlign = Paint.Align.RIGHT }
        canvas.drawText("CADENCE", W - 96f, 120f, mark)

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        return File(dir, "lyrics.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun ellipsize(s: String, p: TextPaint, w: Float) =
        android.text.TextUtils.ellipsize(s, p, w, android.text.TextUtils.TruncateAt.END).toString()

    fun share(context: Context, file: File, info: TrackInfo) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "“${info.track.title}” by ${info.artist.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, null))
    }
}
