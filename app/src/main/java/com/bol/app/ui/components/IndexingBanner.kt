package com.bol.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bol.app.ai.indexing.IndexingCoordinator
import com.bol.app.ui.theme.HeedTheme
import org.koin.compose.koinInject

/**
 * Slim non-collapsible status bar for the deferred-indexing pipeline.
 * Hidden when there's nothing to index. When pending > 0 but not running,
 * shows a count + Start button. When running, shows a progress bar + phase
 * label + Stop button.
 *
 * Used at the top of HomeScreen and the top of SearchScreen so the user
 * always knows where they are in the indexing lifecycle and can opt in or
 * out at any moment. The behaviour is wired via [IndexingCoordinator]
 * which the composable pulls from Koin directly so callers don't have to
 * thread it through their ViewModels.
 */
@Composable
fun IndexingBanner(
    modifier: Modifier = Modifier,
    coordinator: IndexingCoordinator = koinInject(),
) {
    val pending by coordinator.pendingTotal.collectAsState()
    val progress by coordinator.progress.collectAsState()

    // Hide when there's nothing to index AND no run in flight. We keep the
    // banner visible during a run even if pending hits 0 momentarily, so
    // the "Done" beat lands cleanly before disappearing.
    if (pending == 0 && !progress.isIndexing) return

    val running = progress.isIndexing
    val total = if (running) progress.totalAtStart.coerceAtLeast(pending) else pending
    // Compute "done" from the LIVE pendingTotal, not progress.completed —
    // the latter only updates after each backfill() batch returns, so the
    // count flatlines for ~30 s and then jumps. The reactive pendingTotal
    // re-emits per Room invalidation tick, which fires per item insert.
    val done = (total - pending).coerceAtLeast(0)
    val percent = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    HeedCallout(
        title = if (running)
            "Indexing $done of $total · ${phaseLabel(progress.phase)}"
        else
            "$pending item${if (pending == 1) "" else "s"} waiting to be indexed",
        body = if (running) "Improving your search results" else "Indexing improves search quality",
        tone = if (running) CalloutTone.Brand else CalloutTone.Neutral,
        modifier = modifier,
    ) {
        if (running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { percent },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = HeedTheme.colors.brand,
                trackColor = HeedTheme.colors.surface,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            HeedButton(text = "Stop", style = HeedButtonStyle.Text, onClick = { coordinator.stop() })
        } else {
            HeedButton(text = "Start", style = HeedButtonStyle.Text, onClick = { coordinator.start() })
        }
    }
}

private fun phaseLabel(phase: IndexingCoordinator.Phase): String = when (phase) {
    IndexingCoordinator.Phase.Idle -> "starting"
    IndexingCoordinator.Phase.Upgrades -> "re-categorizing"
    IndexingCoordinator.Phase.Embeddings -> "embedding"
    IndexingCoordinator.Phase.Done -> "wrapping up"
}
