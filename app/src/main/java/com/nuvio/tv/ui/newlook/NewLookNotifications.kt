package com.nuvio.tv.ui.newlook

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.NotificationStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The new look's notifications: short, informational heads-ups (calendar, Remind Me, app updates, the player's
 * "Source failed to play", Auto quality's "Upgrading to 1080p") shown as an Apple Dynamic Island. They never take
 * focus or block input. One at a time; the rest wait in a queue. Only [Kind.forPlayer] ones show over the player; the
 * others wait until it closes. A notice with no [Notice.text] is a single line. A countdown notice (Auto quality's
 * "Upgrading to 1080p") stays up until its moment, shows the seconds left, and can be [cancel]led.
 */
object NewLookNotifications {
    enum class Kind {
        CALENDAR, REMINDER, UPDATE, SAMPLE, PLAYER, QUALITY_UP, QUALITY_DOWN;

        /** Shown over the player (the others wait for it to close). */
        val forPlayer: Boolean get() = this == PLAYER || this == QUALITY_UP || this == QUALITY_DOWN
    }

    data class Notice(
        val id: Long,
        val kind: Kind,
        val title: String,
        val text: String,
        /** A countdown notice: when it runs out ([SystemClock.elapsedRealtime] time), and how long it ran. */
        val countdownEndsAtMs: Long? = null,
        val countdownTotalMs: Long = 0L,
        /** A small line centred along the bottom ("Press OK to cancel"). */
        val hint: String? = null
    )

    private val nextId = java.util.concurrent.atomic.AtomicLong(1L)
    private val _queue = MutableStateFlow<List<Notice>>(emptyList())
    internal val queue: StateFlow<List<Notice>> = _queue.asStateFlow()
    private val _cancelled = MutableStateFlow<Set<Long>>(emptySet())
    internal val cancelled: StateFlow<Set<Long>> = _cancelled.asStateFlow()

    /** Queues a notice; its id (that of the same message already waiting, if there is one). */
    fun post(kind: Kind, title: String, text: String): Long = enqueue(kind, title, text, null, 0L, null)

    /** Queues a notice that counts down [durationMs] from now, with [hint] along its bottom; its id. */
    fun postCountdown(kind: Kind, title: String, durationMs: Long, hint: String?): Long =
        enqueue(kind, title, "", SystemClock.elapsedRealtime() + durationMs, durationMs, hint)

    private fun enqueue(kind: Kind, title: String, text: String, endsAt: Long?, total: Long, hint: String?): Long {
        var id = 0L
        _queue.update { current ->
            // The same message twice in a row (e.g. a recomposition re-posting) shows once.
            val same = current.firstOrNull { it.kind == kind && it.title == title && it.text == text }
            if (same != null) {
                id = same.id
                current
            } else {
                id = nextId.getAndIncrement()
                current + Notice(id, kind, title, text, endsAt, total, hint)
            }
        }
        return id
    }

    /** Takes a notice down early (it folds away if showing, or never shows). */
    fun cancel(id: Long) {
        _cancelled.update { it + id }
    }

    /** Settings' "Show a sample notification"; the host fills in the words. */
    fun sample() = post(Kind.SAMPLE, "", "")

    internal fun done(id: Long) {
        _queue.update { list -> list.filterNot { it.id == id } }
        _cancelled.update { it - id }
    }
}

private val IslandSmallW = 112.dp
private val IslandSmallH = 30.dp
private val IslandOpenW = 430.dp
private val IslandOpenH = 58.dp
/** Open height of a notice with a hint line along its bottom. */
private val IslandOpenHintH = 74.dp

/** What the profile avatar should do while a style-2 notification grows out of it. */
internal class AvatarNotifyState {
    /** Show the avatar even where it's normally hidden (menu hidden, or another screen). */
    var visible by mutableStateOf(false)
    /** The avatar nudges forward as the island lets go of it. */
    var lifted by mutableStateOf(false)
}

/**
 * Plays queued notifications in the chosen style. [avatar] is driven for the "out of the profile picture" style;
 * the caller draws the avatar above this host.
 */
@Composable
internal fun NotificationHost(
    style: NotificationStyle,
    avatar: AvatarNotifyState,
    shows: (NewLookNotifications.Notice) -> Boolean = { true }
) {
    val queue by NewLookNotifications.queue.collectAsState()
    val current = queue.firstOrNull(shows)
    if (style == NotificationStyle.OFF) {
        LaunchedEffect(queue) { queue.filter(shows).forEach { NewLookNotifications.done(it.id) } }
        return
    }
    if (current == null) return

    val title = if (current.kind == NewLookNotifications.Kind.SAMPLE) stringResource(R.string.notif_sample_title) else current.title
    val text = if (current.kind == NewLookNotifications.Kind.SAMPLE) stringResource(R.string.notif_sample_text) else current.text

    val width = remember(current.id) { Animatable(0f) }
    val height = remember(current.id) { Animatable(0f) }
    val drop = remember(current.id) { Animatable(0f) }
    val contentAlpha = remember(current.id) { Animatable(0f) }
    val presence = remember(current.id) { Animatable(0f) }
    val open = spring<Float>(dampingRatio = 0.72f, stiffness = 260f)

    // Up for the usual few seconds, or a countdown notice until its moment; either folds away early if cancelled.
    suspend fun hold(usualMs: Long) {
        val ms = current.countdownEndsAtMs?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) } ?: usualMs
        withTimeoutOrNull(ms) { NewLookNotifications.cancelled.first { current.id in it } }
    }

    LaunchedEffect(current.id, style) {
        try {
            if (current.id in NewLookNotifications.cancelled.value) {
                NewLookNotifications.done(current.id)
                return@LaunchedEffect
            }
            if (style == NotificationStyle.ISLAND) {
                // Drops in small, swells open, holds, shrinks back and rises out of sight.
                presence.snapTo(1f)
                drop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
                launch { width.animateTo(1f, open) }
                height.animateTo(1f, open)
                contentAlpha.animateTo(1f, tween(220))
                hold(3_900)
                contentAlpha.animateTo(0f, tween(160))
                launch { width.animateTo(0f, tween(380)) }
                height.animateTo(0f, tween(380))
                drop.animateTo(0f, tween(320))
            } else {
                // Grows out of the avatar (which nudges forward as it lets go), holds, folds back in.
                avatar.visible = true
                delay(320)
                presence.snapTo(1f)
                avatar.lifted = true
                delay(80)
                launch { width.animateTo(1f, open) }
                height.animateTo(1f, open)
                contentAlpha.animateTo(1f, tween(220))
                hold(3_600)
                contentAlpha.animateTo(0f, tween(160))
                launch { width.animateTo(0f, tween(420)) }
                height.animateTo(0f, tween(420))
                presence.animateTo(0f, tween(160))
                avatar.lifted = false
                delay(150)
                avatar.visible = false
            }
            delay(250)
            NewLookNotifications.done(current.id)
        } finally {
            // Cut short (the player opened, or the style changed): never leave the avatar showing over a screen.
            avatar.lifted = false
            avatar.visible = false
        }
    }

    val icon = noticeIcon(current.kind)
    val openH = if (current.hint != null) IslandOpenHintH else IslandOpenH
    if (style == NotificationStyle.ISLAND) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val w = IslandSmallW + (IslandOpenW - IslandSmallW) * width.value
            val h = IslandSmallH + (openH - IslandSmallH) * height.value
            IslandCapsule(
                icon = icon,
                title = title,
                text = text,
                countdownEndsAtMs = current.countdownEndsAtMs,
                countdownTotalMs = current.countdownTotalMs,
                hint = current.hint,
                contentAlpha = contentAlpha.value,
                modifier = Modifier
                    .offset { IntOffset(0, (10.dp.toPx() + (drop.value - 1f) * 80.dp.toPx()).toInt()) }
                    .graphicsLayer { alpha = presence.value }
                    .size(w, h)
            )
        }
    } else {
        // Behind the avatar (36 at 30,12): starts as a 42 circle around it and opens to the right.
        val w = 42.dp + (IslandOpenW - 42.dp) * width.value
        val h = 42.dp + (openH - 42.dp) * height.value
        val top = 9.dp - 8.dp * height.value
        IslandCapsule(
            icon = icon,
            title = title,
            text = text,
            countdownEndsAtMs = current.countdownEndsAtMs,
            countdownTotalMs = current.countdownTotalMs,
            hint = current.hint,
            contentAlpha = contentAlpha.value,
            startPadding = 52.dp,
            iconSize = 32.dp,
            modifier = Modifier
                .offset { IntOffset(27.dp.roundToPx(), top.roundToPx()) }
                .graphicsLayer { alpha = presence.value }
                .size(w, h)
        )
    }
}

private data class NoticeIcon(val vector: ImageVector, val background: Color)

@Composable
private fun noticeIcon(kind: NewLookNotifications.Kind): NoticeIcon = when (kind) {
    NewLookNotifications.Kind.CALENDAR, NewLookNotifications.Kind.SAMPLE ->
        NoticeIcon(ImageVector.vectorResource(R.drawable.ic_nav_calendar), Color(0xFFC13AA8))
    NewLookNotifications.Kind.REMINDER -> NoticeIcon(Icons.Filled.Notifications, Color(0xFFE0A100))
    NewLookNotifications.Kind.UPDATE -> NoticeIcon(Icons.Filled.Download, Color(0xFF2E9D57))
    NewLookNotifications.Kind.PLAYER -> NoticeIcon(Icons.Filled.Warning, Color(0xFFD94848))
    NewLookNotifications.Kind.QUALITY_UP -> NoticeIcon(Icons.Filled.ArrowUpward, Color(0xFF2E9D57))
    NewLookNotifications.Kind.QUALITY_DOWN -> NoticeIcon(Icons.Filled.ArrowDownward, Color(0xFFE07A1F))
}

/**
 * Pure black capsule with the glass menu's faint rim and top highlight, and a soft shadow. A countdown notice
 * ends its row with a ring of the seconds left; a [hint] sits small and centred along the bottom.
 */
@Composable
private fun IslandCapsule(
    icon: NoticeIcon,
    title: String,
    text: String,
    contentAlpha: Float,
    modifier: Modifier,
    startPadding: androidx.compose.ui.unit.Dp = 11.dp,
    iconSize: androidx.compose.ui.unit.Dp = 36.dp,
    countdownEndsAtMs: Long? = null,
    countdownTotalMs: Long = 0L,
    hint: String? = null
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.55f))
            .clip(shape)
            .background(Color.Black)
            .hairlineRing(shape, Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = 14.dp, bottom = if (hint != null) 14.dp else 0.dp)
                .graphicsLayer { alpha = contentAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .clip(CircleShape)
                    .background(icon.background),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon.vector, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (text.isNotBlank()) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(text = text, color = Color(0xFFA8A8A8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (countdownEndsAtMs != null) {
                CountdownRing(endsAtMs = countdownEndsAtMs, totalMs = countdownTotalMs, color = icon.background)
            }
        }
        if (hint != null) {
            Text(
                text = hint,
                color = Color(0xFF8C8C8C),
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .graphicsLayer { alpha = contentAlpha }
            )
        }
    }
}

/** The seconds left until a countdown notice's moment, inside a ring that empties as they run out. */
@Composable
private fun CountdownRing(endsAtMs: Long, totalMs: Long, color: Color) {
    var now by remember(endsAtMs) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(endsAtMs) {
        while (now < endsAtMs) {
            delay(100)
            now = SystemClock.elapsedRealtime()
        }
    }
    val leftMs = (endsAtMs - now).coerceAtLeast(0L)
    val fraction = if (totalMs > 0L) (leftMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(
            text = ((leftMs + 999L) / 1000L).toString(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
