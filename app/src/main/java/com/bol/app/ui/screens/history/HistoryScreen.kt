package com.bol.app.ui.screens.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.EmptyState
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.IconTile
import com.bol.app.ui.components.ListRow
import com.bol.app.ui.components.PageHeader
import com.bol.app.ui.components.RowDivider
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    onOpenRecording: (String) -> Unit = {},
    viewModel: HistoryViewModel = koinViewModel(),
) {
    // No `initial =` args: these are StateFlows so collectAsState picks up
    // the ViewModel's cached value on frame 1 — prevents the transient-empty
    // scroll reset on re-entry.
    val recordings by viewModel.recordings.collectAsState()
    val pendingUpgradeIds by viewModel.pendingUpgradeIds.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState(initial = com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_24H
    val playback by viewModel.playback.collectAsState()

    // Stop audio when the user leaves History (tab switch, back gesture,
    // etc.). MediaPlayer is also released in the ViewModel's onCleared as
    // the last line of defence.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPlayback()
        }
    }

    val totalItems = recordings.sumOf { it.parsedItemCount }
    val groups = groupByWhen(recordings)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        PageHeader(
            eyebrow = "History",
            title = "Everything",
            accent = "you've said.",
            mood = BolMood.Idle,
            modifier = Modifier.padding(horizontal = HeedLayout.Gutter),
            meta = {
                Text(
                    text = "${recordings.size} recording${if (recordings.size == 1) "" else "s"} · " +
                        "$totalItems item${if (totalItems == 1) "" else "s"}",
                    style = BolMono.copy(color = HeedTheme.colors.textMuted),
                )
            },
        )

        if (recordings.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState(
                    title = "Nothing yet",
                    body = "Tap the mic and say something.",
                    mood = BolMood.Sleeping,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = HeedLayout.Gutter, end = HeedLayout.Gutter, bottom = 24.dp),
            ) {
                groups.forEach { (label, list) ->
                    item(key = "h-$label") {
                        SectionLabel(label)
                    }
                    itemsIndexed(list, key = { _, r -> r.id }) { index, recording ->
                        // File.exists() is cheap — cached per recording id
                        // so we don't re-stat on every recomposition of
                        // the list.
                        val audioAvailable = remember(recording.id) {
                            viewModel.hasAudio(recording.id)
                        }
                        if (index > 0) RowDivider()
                        RecordingRow(
                            recording = recording,
                            isPending = recording.id in pendingUpgradeIds,
                            format24h = format24h,
                            hasAudio = audioAvailable,
                            playback = playback,
                            onOpenDetail = { onOpenRecording(recording.id) },
                            onPlayPauseClick = { viewModel.togglePlayback(recording.id) },
                            onStopPlayback = { viewModel.stopPlayback() },
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/**
 * Recording row. Tapping the row body navigates to RecordingDetail. Tapping the
 * mic tile toggles playback; long-press on an active-playback tile stops it.
 * Recordings without a WAV leave the tile decorative.
 *
 * Cancelled recordings show a muted mic and a red "not processed" caption;
 * tapping the row still opens the detail screen (which offers the Retry action).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    recording: RecordingEntity,
    isPending: Boolean,
    format24h: Boolean,
    hasAudio: Boolean,
    playback: PlaybackState,
    onOpenDetail: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    val c = HeedTheme.colors
    val isCancelled = recording.cancelled
    val isActivePlayback = playback.playingRecordingId == recording.id
    val isPlayingThis = isActivePlayback && playback.isPlaying
    val isPausedThis = isActivePlayback && !playback.isPlaying

    val timeLabel = com.bol.app.util.DateTimeDisplay.time(recording.createdAt, format24h)
    val n = recording.parsedItemCount
    val itemsLabel = "$n item${if (n == 1) "" else "s"}"

    ListRow(
        title = "\"${recording.rawText}\"",
        titleMaxLines = 2,
        metaContent = {
            when {
                isCancelled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.danger))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$timeLabel · not processed".uppercase(),
                        style = BolMono.copy(color = c.danger),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                isPending -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = c.brand,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$timeLabel · processing…".uppercase(),
                        style = BolMono.copy(color = c.brand),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                else -> Text(
                    text = "$timeLabel · $itemsLabel".uppercase(),
                    style = BolMono.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leading = {
            // The tile's own combinedClickable consumes the tap before it
            // bubbles up to the row's onOpenDetail.
            Box(modifier = Modifier.size(36.dp)) {
                val icon = when {
                    isPlayingThis -> Icons.Rounded.Pause
                    isPausedThis -> Icons.Rounded.PlayArrow
                    else -> Icons.Rounded.Mic
                }
                IconTile(
                    icon = icon,
                    size = 36.dp,
                    tint = if (isCancelled) c.textMuted else c.brand,
                    background = if (isCancelled) c.surfaceSofter else c.brandSoft,
                    modifier = if (hasAudio) {
                        Modifier.combinedClickable(
                            onClick = onPlayPauseClick,
                            onLongClick = if (isActivePlayback) onStopPlayback else null,
                        )
                    } else {
                        Modifier
                    },
                )
                if (isActivePlayback) {
                    val arcColor = c.brand
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val stroke = 2.5.dp.toPx()
                        drawArc(
                            color = arcColor,
                            startAngle = -90f,
                            sweepAngle = 360f * playback.progressFraction.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        },
        onClick = onOpenDetail,
    )
}

/**
 * Groups recordings into "Today", "Yesterday", "<MMM d>" sections — each
 * group preserves the original chronological order.
 */
private fun groupByWhen(recordings: List<RecordingEntity>): List<Pair<String, List<RecordingEntity>>> {
    if (recordings.isEmpty()) return emptyList()
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterday = today - 24L * 60 * 60 * 1000
    val monthFmt = SimpleDateFormat("MMM d", Locale.getDefault())
    val groups = LinkedHashMap<String, MutableList<RecordingEntity>>()
    recordings.forEach { rec ->
        val label = when {
            rec.createdAt >= today -> "Today"
            rec.createdAt >= yesterday -> "Yesterday"
            else -> monthFmt.format(Date(rec.createdAt))
        }
        groups.getOrPut(label) { mutableListOf() }.add(rec)
    }
    return groups.map { (k, v) -> k to v }
}
