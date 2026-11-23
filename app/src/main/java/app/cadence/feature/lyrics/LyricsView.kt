package app.cadence.feature.lyrics

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.dp
import app.cadence.core.theme.LocalReduceMotion
import app.cadence.backend.lyrics.LyricCursor
import app.cadence.backend.lyrics.LyricTiming
import app.cadence.backend.model.LyricLine
import app.cadence.backend.model.LyricWord
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.Voice
import app.cadence.state.settings.LyricsAlign
import app.cadence.state.settings.UserSettings
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val FOLLOW_RESUME_MS = 3_000L

/** Lines dissolve into the header and controls instead of being cut off at a hard edge. */
private fun Modifier.fadingEdges() = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.07f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
fun LyricsView(
    lyrics: Lyrics,
    position: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    settings: UserSettings,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val pulseEnabled = settings.lyricsPulse && isPlaying && !LocalReduceMotion.current
    val wave = app.cadence.LocalContainer.current.wave
    val pulseScale = if (pulseEnabled) {
        val cur by wave.levels.collectAsStateWithLifecycle()
        1f + (cur.hit.coerceIn(0f, 1f) * 0.03f)
    } else 1f
    val pulsedModifier = if (pulseEnabled) modifier.graphicsLayer { scaleX = pulseScale; scaleY = pulseScale } else modifier
    when (lyrics) {
        is Lyrics.Synced -> SyncedLyrics(lyrics.lines, position, isPlaying, onSeek, settings, contentColor, pulsedModifier)
        is Lyrics.Plain -> PlainLyrics(lyrics.text, settings, contentColor, modifier)
        Lyrics.None -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.lyrics_none),
                style = MaterialTheme.typography.titleLarge,
                color = contentColor.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    position: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    settings: UserSettings,
    contentColor: Color,
    modifier: Modifier,
) {
    // Frame-synced playback clock. Only draw phases and derived state read it, so lines don't recompose per frame.
    val clock = remember { mutableLongStateOf(position()) }
    LaunchedEffect(isPlaying) {
        clock.longValue = position()
        while (isPlaying) withFrameMillis { clock.longValue = position() }
    }
    val cursor by remember(lines) { derivedStateOf { LyricTiming.cursor(lines, clock.longValue) } }
    val activeIndex = when (val c = cursor) { is LyricCursor.Line -> c.index; is LyricCursor.Gap -> c.nextIndex }

    val listState = rememberLazyListState()
    var userScrollAt by remember { mutableLongStateOf(0L) }
    var following by remember { mutableStateOf(true) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) { userScrollAt = SystemClock.uptimeMillis(); following = false }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(userScrollAt) {
        if (userScrollAt == 0L) return@LaunchedEffect
        delay(FOLLOW_RESUME_MS)
        following = true
    }

    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(activeIndex, following) {
        if (!following) return@LaunchedEffect
        val target = activeIndex.coerceAtLeast(0)
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        if (item != null && !reduceMotion) {
            listState.animateScrollBy(item.offset.toFloat(), spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessVeryLow * 1.6f))
        } else {
            listState.scrollToItem(target)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val anchor = maxHeight * settings.lyricsAnchor
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScroll).fadingEdges(),
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 0.6f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines, key = { i, l -> "$i-${l.startMs}" }) { i, line ->
                Column {
                    val gap = cursor as? LyricCursor.Gap
                    AnimatedVisibility(
                        visible = gap != null && gap.nextIndex == i,
                        enter = fadeIn() + expandVertically() + scaleIn(initialScale = 0.6f),
                        exit = fadeOut() + shrinkVertically() + scaleOut(targetScale = 0.6f),
                    ) {
                        GapDots(progress = { (cursor as? LyricCursor.Gap)?.progress ?: 1f }, color = contentColor, alignEnd = line.voice == Voice.Secondary)
                    }
                    LyricLineItem(
                        line = line,
                        distance = i - activeIndex,
                        isActive = cursor is LyricCursor.Line && i == activeIndex,
                        clock = clock,
                        browsing = !following,
                        settings = settings,
                        color = contentColor,
                        onClick = {
                            onSeek(line.startMs)
                            clock.longValue = line.startMs
                            following = true
                        },
                    )
                }
            }
            // Waiting dots after the final line during a long outro (nextIndex points past the last line).
            item(key = "outro") {
                AnimatedVisibility(
                    visible = (cursor as? LyricCursor.Gap)?.let { it.nextIndex >= lines.size } == true,
                    enter = fadeIn() + expandVertically() + scaleIn(initialScale = 0.6f),
                    exit = fadeOut() + shrinkVertically() + scaleOut(targetScale = 0.6f),
                ) {
                    GapDots(progress = { (cursor as? LyricCursor.Gap)?.progress ?: 1f }, color = contentColor, alignEnd = false)
                }
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    distance: Int,
    isActive: Boolean,
    clock: MutableLongState,
    browsing: Boolean,
    settings: UserSettings,
    color: Color,
    onClick: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val springy = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)
    val alpha by animateFloatAsState(
        when { isActive -> 1f; browsing -> 0.7f; else -> 0.32f },
        tween(400), label = "alpha",
    )
    val scale by animateFloatAsState(if (isActive || reduceMotion) 1f else 0.94f, springy, label = "scale")
    val blurDp by animateFloatAsState(
        if (!settings.lyricsBlur || isActive || browsing || reduceMotion) 0f else (abs(distance).coerceAtMost(4) * 1.1f),
        tween(500), label = "blur",
    )

    val end = settings.lyricsAlign == LyricsAlign.Start && line.voice == Voice.Secondary
    val center = settings.lyricsAlign == LyricsAlign.Center
    val align = when { center -> TextAlign.Center; end -> TextAlign.End; else -> TextAlign.Start }
    val origin = when { center -> TransformOrigin(0.5f, 0.5f); end -> TransformOrigin(1f, 0.5f); else -> TransformOrigin(0f, 0.5f) }
    val base = MaterialTheme.typography.headlineMedium
    val style = base.copy(
        fontSize = base.fontSize * settings.lyricsScale,
        lineHeight = base.lineHeight * settings.lyricsScale,
        fontWeight = FontWeight(settings.lyricsWeight.coerceIn(100, 900)),
        textAlign = align,
        lineBreak = LineBreak.Heading,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = origin }
            .then(if (blurDp > 0.05f) Modifier.blur(blurDp.dp) else Modifier),
        horizontalAlignment = when { center -> Alignment.CenterHorizontally; end -> Alignment.End; else -> Alignment.Start },
    ) {
        val karaoke = settings.karaoke && isActive && line.words.isNotEmpty()
        KaraokeText(line.text, line.words, style, color, alpha, karaoke, clock)
        if (line.background.isNotEmpty()) {
            val bgStyle = MaterialTheme.typography.titleLarge.copy(
                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.lyricsScale,
                fontWeight = FontWeight.SemiBold, textAlign = align, lineBreak = LineBreak.Heading,
            )
            KaraokeText(
                line.background.joinToString(" ") { it.text }, line.background, bgStyle, color,
                alpha * 0.8f, settings.karaoke && isActive, clock,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Draws the line dim, then repaints each sung word bright, clipped left-to-right by its progress.
 * Reads the clock only in the draw phase, so a playing line costs a redraw per frame, not a recomposition.
 */
@Composable
private fun KaraokeText(
    text: String,
    words: List<LyricWord>,
    style: TextStyle,
    color: Color,
    alpha: Float,
    karaoke: Boolean,
    clock: MutableLongState,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val ranges = remember(text, words) { wordRanges(text, words) }
    val feather = with(LocalDensity.current) { 14.dp.toPx() }
    Text(
        text = text,
        style = style,
        color = if (karaoke) color.copy(alpha = 0.32f) else color.copy(alpha = alpha),
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            if (!karaoke) return@drawWithContent
            val pos = clock.longValue
            for ((w, range) in words.zip(ranges)) {
                val p = LyricTiming.wordProgress(w, pos)
                if (p <= 0f) break
                val box = wordBox(l, range) ?: continue
                val edge = box.left + box.width * p
                clipRect(box.left - 2f, box.top, if (p >= 1f) box.right + 2f else edge + feather * 0.5f, box.bottom) {
                    drawText(
                        l,
                        brush = if (p >= 1f) Brush.linearGradient(listOf(color, color))
                        else Brush.horizontalGradient(
                            0f to color, 1f to color.copy(alpha = 0.32f),
                            startX = edge - feather * 0.5f, endX = edge + feather * 0.5f,
                        ),
                    )
                }
            }
        },
    )
}

private fun wordRanges(text: String, words: List<LyricWord>): List<IntRange> {
    var from = 0
    return words.map { w ->
        val start = text.indexOf(w.text, from).takeIf { it >= 0 } ?: from
        val end = (start + w.text.length).coerceAtMost(text.length)
        from = end
        start until end
    }
}

private fun wordBox(l: TextLayoutResult, range: IntRange): Rect? {
    if (range.isEmpty() || range.last >= l.layoutInput.text.length) return null
    val first = l.getBoundingBox(range.first)
    val last = l.getBoundingBox(range.last)
    // A word never wraps mid-way; if the boxes disagree on line, fall back to the first glyph's line.
    return if (abs(first.top - last.top) < 1f) Rect(first.left, first.top, last.right, last.bottom) else first
}

/** Three dots that fill one after another across an instrumental break, breathing while they wait. */
@Composable
private fun GapDots(progress: () -> Float, color: Color, alignEnd: Boolean) {
    val breathe by rememberInfiniteTransition(label = "breathe")
        .animateFloat(0.9f, 1.15f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "b")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalArrangement = if (alignEnd) Arrangement.spacedBy(10.dp, Alignment.End) else Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        val p = progress()
                        val fill = ((p * 3f) - i).coerceIn(0f, 1f)
                        scaleX = breathe * (0.85f + 0.15f * fill)
                        scaleY = scaleX
                        this.alpha = 0.3f + 0.7f * fill
                        // Shrink away together right before the next line lands.
                        if (p > 0.94f) { val out = 1f - (p - 0.94f) / 0.06f; scaleX *= out; scaleY *= out }
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

@Composable
private fun PlainLyrics(text: String, settings: UserSettings, color: Color, modifier: Modifier) {
    val base = MaterialTheme.typography.headlineSmall
    Column(modifier.fillMaxSize().fadingEdges().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)) {
        Text(
            stringResource(R.string.lyrics_unsynced),
            style = MaterialTheme.typography.labelLarge,
            color = color.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text,
            style = base.copy(
                fontSize = base.fontSize * settings.lyricsScale,
                lineHeight = base.lineHeight * settings.lyricsScale * 1.15f,
                fontWeight = FontWeight.Bold,
                textAlign = if (settings.lyricsAlign == LyricsAlign.Center) TextAlign.Center else TextAlign.Start,
                lineBreak = LineBreak.Heading,
            ),
            color = color.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth().padding(bottom = 200.dp),
        )
    }
}
