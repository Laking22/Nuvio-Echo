package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.seekpreview.SeekPreviewHosts
import com.nuvio.tv.core.player.seekpreview.SeekThumbnail
import com.nuvio.tv.core.player.seekpreview.SeekThumbnailEngine
import com.nuvio.tv.core.player.seekpreview.SeekThumbnailSource
import com.nuvio.tv.core.streams.StreamQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.EventListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The index is read at the latest this long after a stream starts, once it's playing. A stream that starts slowly
 * (its buffer not yet healthy) gets this long to settle first: playback starting comes before thumbnails.
 */
private const val INDEX_PREFETCH_DELAY_MS = 30_000L
/** The index is read no sooner than this after a stream starts. */
private const val INDEX_PREFETCH_MIN_DELAY_MS = 2_000L
/** Buffered ahead of the playhead for playback to count as settled. */
private const val HEALTHY_BUFFER_MS = 20_000L
/** How long the index prefetch waits for the title's source search when previews come from another source. */
private const val SOURCE_SEARCH_WAIT_MS = 15_000L
/** Frames shown either side of the scrub position in the filmstrip. */
private const val FILMSTRIP_SIDE = 2
/** A thumbnail this far from the scrub position is dimmed until the right one arrives. */
private const val STALE_MS = 12_000L

/**
 * The thumbnail engine while "Seek thumbnails" is on; while it's off nothing is created at all. [budgetMb] is the
 * Seek preview data setting (0: no limit); [filmstrip] shows frames either side of the scrub position.
 */
@Composable
internal fun rememberSeekThumbnailEngine(enabled: Boolean, budgetMb: Int, filmstrip: Boolean): SeekThumbnailEngine? {
    if (!enabled) return null
    val context = LocalContext.current
    val engine = remember {
        SeekPreviewHosts.init(context)
        SeekThumbnailEngine(
            PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
            diskDir = java.io.File(context.cacheDir, "seek-preview"),
        ) {
            // Playback's client: shared connections, DNS and certificates, but its own timeouts and
            // no connection events (those feed the player's own stats).
            PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                .eventListener(EventListener.NONE)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
    SideEffect {
        engine.budgetBytes = if (budgetMb <= 0) Long.MAX_VALUE else budgetMb * 1024L * 1024L
        engine.stripSide = if (filmstrip) FILMSTRIP_SIDE else 0
    }
    DisposableEffect(engine) {
        onDispose { engine.close() }
    }
    return engine
}

/**
 * Feeds the engine the streams to take thumbnails from and the scrub position, and sets the player's commit wait.
 * [previewHeight] is the Preview quality setting: other sources of the title from that quality up come before the
 * playing stream (0: only the playing stream).
 */
@Composable
internal fun SeekThumbnailDriver(engine: SeekThumbnailEngine, viewModel: PlayerViewModel, streamKey: String?, previewHeight: Int) {
    val controller = viewModel.controller
    // The sources chosen for this stream, kept once the engine is prepared (or the title's source search has
    // finished) so later search results never restart it and throw away what it has made.
    val settled = remember(engine) { AtomicReference<Pair<String, List<SeekThumbnailSource>>?>(null) }
    // Thumbnails are warmed when the controls come up only once this stream's index has been asked for, so the
    // controls shown as playback starts don't pull it in early.
    val warmReady = remember(engine) { AtomicBoolean(false) }
    // The stream playing now and the one before it on this screen, for a quality switch to keep using the old one.
    val playingNow = remember(engine) { AtomicReference<PlayingStream?>(null) }
    val playingBefore = remember(engine) { AtomicReference<PlayingStream?>(null) }
    fun currentSources(lock: Boolean = false): List<SeekThumbnailSource> {
        engine.playerVideoLongEdge = maxOf(controller.currentVideoWidth ?: 0, controller.currentVideoHeight ?: 0)
        val playing = SeekThumbnailSource.from(
            url = viewModel.getCurrentStreamUrl(),
            headers = viewModel.getCurrentHeaders(),
            isTorrent = controller.isTorrentStream,
            isLive = viewModel.playbackTimeline.value.isLive,
            mimeType = controller.currentStreamMimeType,
        ) ?: return emptyList()
        val title = controller.playingSourceRequestKey()
        // Thumbnails are kept per title: those from an earlier play show from the start.
        engine.titleKey = title
        val quality = controller.currentPlaybackQuality()
        val now = playingNow.get()
        if (now?.source?.linkKey != playing.linkKey) now?.let(playingBefore::set)
        if (now?.source?.linkKey != playing.linkKey || (quality != null && now?.quality == null)) {
            playingNow.set(PlayingStream(title, playing, quality))
        }
        if (previewHeight <= 0) return listOf(playing)
        val key = "${playing.key}@$previewHeight"
        settled.get()?.takeIf { it.first == key }?.let { return it.second }
        // Switched up a quality on the same title: the file that was playing comes first. Its length matches and
        // its thumbnails are already made, so previews carry on as they were.
        val before = playingBefore.get()?.takeIf { old ->
            old.title != null && old.title == title && old.source.linkKey != playing.linkKey &&
                old.quality != null && quality != null && old.quality.ordinal > quality.ordinal &&
                !SeekPreviewHosts.rejected(old.source.url)
        }
        val alternates = controller.seekPreviewAlternates(previewHeight)
            .filter { before == null || it.linkKey != before.source.linkKey }
        val sources = listOfNotNull(before?.let { it.source.asAlternate(it.quality!!.label) }) + alternates + playing
        if (lock || controller.seekPreviewSourcesSettled()) settled.set(key to sources)
        return sources
    }

    LaunchedEffect(engine, streamKey, previewHeight) {
        warmReady.set(false)
        engine.setSources(currentSources())
        // Playback first: the index is read once the stream is playing with a healthy buffer ahead, or after
        // [INDEX_PREFETCH_DELAY_MS] at the latest, so thumbnails never compete with the stream starting up.
        delay(INDEX_PREFETCH_MIN_DELAY_MS)
        withTimeoutOrNull(INDEX_PREFETCH_DELAY_MS - INDEX_PREFETCH_MIN_DELAY_MS) {
            viewModel.playbackTimeline.first { it.bufferedPosition - it.currentPosition >= HEALTHY_BUFFER_MS }
        }
        viewModel.uiState.first { it.isPlaying && !it.isBuffering }
        if (previewHeight > 0) {
            controller.requestSeekPreviewSources()
            withTimeoutOrNull(SOURCE_SEARCH_WAIT_MS) { viewModel.uiState.first { !it.isLoadingSourceStreams } }
        }
        engine.setSources(currentSources(lock = true))
        engine.prepare()
        warmReady.set(true)
    }
    LaunchedEffect(engine) {
        viewModel.uiState.map { it.showControls }.distinctUntilChanged().collect { shown ->
            if (shown && warmReady.get() && viewModel.uiState.value.pendingPreviewSeekPosition == null) {
                engine.warm(viewModel.playbackTimeline.value.currentPosition)
            }
        }
    }
    LaunchedEffect(engine) {
        var last: Long? = null
        viewModel.uiState.map { it.pendingPreviewSeekPosition }.distinctUntilChanged().collect { position ->
            if (position == null) {
                engine.stopScrubbing()
            } else {
                engine.setSources(currentSources())
                val direction = last?.let { prev -> (position - prev).coerceIn(-1L, 1L).toInt() } ?: 0
                engine.request(position, direction)
            }
            last = position
        }
    }
    LaunchedEffect(engine) {
        viewModel.uiState.map { it.isBuffering }.distinctUntilChanged().collect { engine.playerBuffering = it }
    }
    LaunchedEffect(engine) {
        viewModel.playbackTimeline.map { it.duration }.distinctUntilChanged().collect { engine.playerDurationMs = it }
    }
    LaunchedEffect(engine) {
        viewModel.playbackTimeline.map { it.currentPosition }.distinctUntilChanged().collect { engine.playerPositionMs = it }
    }
    val available by engine.available.collectAsState()
    val commitWait by engine.commitWaitMs.collectAsState()
    LaunchedEffect(available, commitWait) {
        // Unknown (index not read yet) counts as available, so the first scrub of a stream waits too.
        controller.previewCommitGraceMs = if (available != false) commitWait else 0L
    }
    DisposableEffect(engine) {
        onDispose {
            controller.previewCommitGraceMs = 0L
            controller.previewCommitJob?.cancel()
        }
    }
}

/** A stream as it played: its title's source search key and its quality, when known. */
private class PlayingStream(val title: String?, val source: SeekThumbnailSource, val quality: StreamQuality?)

/**
 * Zero-height row placed right above a progress bar: draws the thumbnail above the scrub position without
 * moving anything else.
 */
@Composable
internal fun SeekThumbnailAnchor(
    engine: SeekThumbnailEngine,
    positionMs: Long?,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (engine.stripSide > 0) {
        SeekFilmstripAnchor(engine, positionMs, durationMs, modifier)
        return
    }
    val thumbnail by engine.thumbnail.collectAsState()
    val available by engine.available.collectAsState()
    // Once the engine gives up on a stream its last picture would no longer follow the scrub: hide it.
    val shown = thumbnail?.takeIf { it.sourceKey == engine.sourceKey && available != false }
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val fraction = if (positionMs != null && durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            if (positionMs != null && durationMs > 0 && shown != null) SeekThumbnailCard(shown, positionMs)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(width, 0) {
            placeables.forEach { card ->
                val x = (width * fraction - card.width / 2f).roundToInt().coerceIn(0, (width - card.width).coerceAtLeast(0))
                card.place(x, -card.height - gap)
            }
        }
    }
}

@Composable
private fun SeekThumbnailCard(thumbnail: SeekThumbnail, positionMs: Long) {
    val image = remember(thumbnail) { thumbnail.bitmap.asImageBitmap() }
    val alpha by animateFloatAsState(
        targetValue = if (abs(thumbnail.timeMs - positionMs) > STALE_MS) 0.45f else 1f,
        animationSpec = tween(120),
        label = "seekThumbnailAlpha",
    )
    val aspect = thumbnail.bitmap.width.toFloat() / thumbnail.bitmap.height.coerceAtLeast(1)
    val width = if (aspect >= 16f / 9f) 256.dp else 144.dp * aspect
    val shape = RoundedCornerShape(8.dp)
    // The time sits inside the picture, so nothing hangs below it over the player's title.
    Box(
        modifier = Modifier
            .size(width, width / aspect)
            .shadow(14.dp, shape, clip = false)
            .clip(shape)
            .background(Color.Black)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape),
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
        )
        Text(
            text = seekThumbnailTime(positionMs),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The filmstrip: a row of frames centred above the progress bar, the scrub position's in the middle and outlined,
 * the steps either side next to it. Frames still coming show as dark tiles; positions outside the video show nothing.
 */
@Composable
private fun SeekFilmstripAnchor(
    engine: SeekThumbnailEngine,
    positionMs: Long?,
    durationMs: Long,
    modifier: Modifier,
) {
    val strip by engine.strip.collectAsState()
    val available by engine.available.collectAsState()
    val shown = strip?.takeIf { it.sourceKey == engine.sourceKey && available != false }
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.roundToPx() }
    val spacing = with(density) { 8.dp.roundToPx() }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            if (positionMs != null && durationMs > 0 && shown != null) {
                val side = shown.frames.size / 2
                shown.frames.forEachIndexed { index, frame ->
                    val offset = index - side
                    val slotMs = if (offset == 0) positionMs else shown.centerMs + offset * shown.stepMs
                    SeekFilmstripFrame(
                        frame = frame,
                        slotMs = slotMs,
                        center = offset == 0,
                        // Before the start or past the end: an empty slot keeps the others in place.
                        outside = slotMs < 0 || slotMs > durationMs,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(width, 0) {
            if (placeables.isEmpty()) return@layout
            val middle = placeables.size / 2
            val centre = placeables[middle]
            val baseline = -gap
            var x = width / 2 - centre.width / 2
            centre.place(x, baseline - centre.height)
            // Out from the middle on each side; frames past the screen edge are cut off, as in a filmstrip.
            for (i in middle - 1 downTo 0) {
                x -= placeables[i].width + spacing
                placeables[i].place(x, baseline - placeables[i].height)
            }
            x = width / 2 + centre.width / 2 + spacing
            for (i in middle + 1 until placeables.size) {
                placeables[i].place(x, baseline - placeables[i].height)
                x += placeables[i].width + spacing
            }
        }
    }
}

@Composable
private fun SeekFilmstripFrame(frame: SeekThumbnail?, slotMs: Long, center: Boolean, outside: Boolean) {
    val width = if (center) 240.dp else 208.dp
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(width, width * 9f / 16f)
            .then(if (center) Modifier.shadow(14.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(if (outside) Color.Transparent else Color.Black)
            .then(if (center) Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), shape) else Modifier),
    ) {
        if (frame != null && !outside) {
            val image = remember(frame) { frame.bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // A nearby frame standing in for one still coming: darkened, never see-through.
            if (abs(frame.timeMs - slotMs) > STALE_MS) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
            }
        }
        if (center) {
            Text(
                text = seekThumbnailTime(slotMs),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

private fun seekThumbnailTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
