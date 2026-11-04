package com.bol.app.ui.screens.restore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bol.app.data.backup.BackupPreview
import com.bol.app.data.backup.RestoreMode
import com.bol.app.data.backup.RestoreProgress
import com.bol.app.data.backup.RestoreReport
import com.bol.app.ui.components.CalloutTone
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCallout
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.PanelDivider
import com.bol.app.ui.components.PanelRow
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.Segmented
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.components.heedTextFieldColors
import com.bol.app.ui.theme.HeedTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RestoreScreen(
    onBack: () -> Unit,
    viewModel: RestoreViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    val pickLauncher = rememberLauncherForActivityResult(
        // Heed exports use application/zip; some file managers tag
        // .heedbackup as octet-stream. Include both so the picker
        // doesn't grey out the file the user just exported.
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.onFilePicked(uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "Restore", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = HeedLayout.Gutter)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Restore from backup",
                style = MaterialTheme.typography.displaySmall,
                color = HeedTheme.colors.textPrimary,
            )
            Spacer(Modifier.height(16.dp))

            when (val phase = state.phase) {
                RestorePhase.AwaitingPick -> AwaitingPickBody(
                    onPick = { pickLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                )
                RestorePhase.LoadingPreview -> LoadingPreviewBody()
                is RestorePhase.PreviewReady -> PreviewReadyBody(
                    preview = phase.preview,
                    state = state,
                    onModeChange = viewModel::setMode,
                    onConfirmTextChange = viewModel::setReplaceConfirmText,
                    onRestore = viewModel::runRestore,
                    onPickAnother = {
                        viewModel.reset()
                        pickLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                )
                is RestorePhase.PreviewIncompatible -> PreviewIncompatibleBody(
                    preview = phase.preview,
                    onPickAnother = {
                        viewModel.reset()
                        pickLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                )
                is RestorePhase.PreviewError -> PreviewErrorBody(
                    message = phase.message,
                    onRetry = {
                        viewModel.reset()
                        pickLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                )
                is RestorePhase.Restoring -> RestoringBody(progress = phase.progress)
                is RestorePhase.Done -> DoneBody(report = phase.report, onDone = onBack)
                is RestorePhase.Failed -> FailedBody(
                    message = phase.message,
                    onRetry = viewModel::reset,
                    onBack = onBack,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AwaitingPickBody(onPick: () -> Unit) {
    Text(
        text = "Pick a `.heedbackup` file you exported earlier. Heed will read it, show you a preview, and let you choose merge or replace before any data changes.",
        style = MaterialTheme.typography.bodyMedium,
        color = HeedTheme.colors.textSecondary,
    )
    Spacer(Modifier.height(20.dp))
    HeedButton(
        text = "Pick backup file",
        onClick = onPick,
        style = HeedButtonStyle.Filled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LoadingPreviewBody() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = HeedTheme.colors.brand,
        )
        Spacer(Modifier.width(12.dp))
        Text("Reading manifest…", style = MaterialTheme.typography.bodyMedium, color = HeedTheme.colors.textMuted)
    }
}

@Composable
private fun PreviewReadyBody(
    preview: BackupPreview,
    state: RestoreUiState,
    onModeChange: (RestoreMode) -> Unit,
    onConfirmTextChange: (String) -> Unit,
    onRestore: () -> Unit,
    onPickAnother: () -> Unit,
) {
    SectionLabel("Backup details")
    BackupSummaryPanel(preview)

    SectionLabel("Mode")
    Segmented(
        options = listOf("Merge", "Replace everything"),
        selectedIndex = if (state.mode == RestoreMode.MERGE) 0 else 1,
        onSelect = { onModeChange(if (it == 0) RestoreMode.MERGE else RestoreMode.REPLACE) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = when (state.mode) {
            RestoreMode.MERGE -> "Add what's in the backup, keep your local data. New rows are inserted; rows already on this device are left alone."
            RestoreMode.REPLACE -> "Wipe your current tasks, voice notes and search history, then put in everything from the backup. This cannot be undone."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (state.mode == RestoreMode.REPLACE) HeedTheme.colors.danger else HeedTheme.colors.textMuted,
    )

    if (state.mode == RestoreMode.REPLACE) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Type REPLACE to confirm.",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = HeedTheme.colors.danger,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.replaceConfirmText,
            onValueChange = onConfirmTextChange,
            singleLine = true,
            shape = HeedLayout.ControlShape,
            colors = heedTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(20.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HeedButton(
            text = "Pick another",
            onClick = onPickAnother,
            style = HeedButtonStyle.Outlined,
            modifier = Modifier.weight(1f),
        )
        HeedButton(
            text = if (state.mode == RestoreMode.REPLACE) "Replace" else "Restore",
            onClick = onRestore,
            enabled = state.canRunRestore,
            style = if (state.mode == RestoreMode.REPLACE) HeedButtonStyle.Danger else HeedButtonStyle.Filled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BackupSummaryPanel(preview: BackupPreview) {
    val date = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US)
        .format(Date(preview.exportedAt))
    HeedCard {
        PanelRow(label = "Made on", right = { ValueText(date) })
        PanelDivider()
        PanelRow(label = "Timezone", right = { ValueText(preview.exportedFromTimezone) })
        PanelDivider()
        PanelRow(label = "Heed version", right = { ValueText("v${preview.appVersionName}") })
        PanelDivider()
        PanelRow(label = "Schema version", right = { ValueText("v${preview.schemaVersion}") })
    }
    SectionLabel("Contents")
    HeedCard {
        CountRow("Categories", preview.counts.categories)
        PanelDivider()
        CountRow("Tasks", preview.counts.items)
        PanelDivider()
        // Backups hold transcripts, not audio: restored notes can be read and searched, not played.
        CountRow("Voice notes (text, no audio)", preview.counts.recordings)
        PanelDivider()
        CountRow("Item tags", preview.counts.itemTags)
        PanelDivider()
        CountRow("Recording tags", preview.counts.recordingTags)
        PanelDivider()
        CountRow("Item enrichments", preview.counts.itemEnrichments)
        PanelDivider()
        CountRow("Search queries", preview.counts.searchQueries)
        PanelDivider()
        CountRow("User context", preview.counts.userContext)
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    PanelRow(label = label, right = { ValueText(count.toString()) })
}

@Composable
private fun ValueText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = HeedTheme.colors.textMuted)
}

@Composable
private fun PreviewIncompatibleBody(
    preview: BackupPreview,
    onPickAnother: () -> Unit,
) {
    HeedCallout(
        tone = CalloutTone.Danger,
        icon = Icons.Default.Warning,
        title = "Can't restore this backup",
        body = preview.incompatibleReason ?: "This backup file isn't compatible with this Heed build.",
    )
    Spacer(Modifier.height(20.dp))
    HeedButton(
        text = "Pick a different file",
        onClick = onPickAnother,
        style = HeedButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PreviewErrorBody(message: String, onRetry: () -> Unit) {
    HeedCallout(
        tone = CalloutTone.Danger,
        icon = Icons.Default.Error,
        title = "Couldn't read that file",
        body = message,
    )
    Spacer(Modifier.height(20.dp))
    HeedButton(
        text = "Try a different file",
        onClick = onRetry,
        style = HeedButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RestoringBody(progress: RestoreProgress) {
    val phaseText = when (progress) {
        RestoreProgress.Validating -> "Validating backup…"
        RestoreProgress.Wiping -> "Wiping current data…"
        is RestoreProgress.Writing -> "Writing data…"
        RestoreProgress.RestoringPrefs -> "Restoring preferences…"
        is RestoreProgress.Done -> "Done"
        is RestoreProgress.Failed -> "Failed"
    }
    Column {
        Text(
            text = phaseText,
            style = MaterialTheme.typography.titleMedium,
            color = HeedTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = HeedTheme.colors.brand,
            trackColor = HeedTheme.colors.brandSoft,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Don't close the app — Heed is restoring your data.",
            style = MaterialTheme.typography.bodySmall,
            color = HeedTheme.colors.textMuted,
        )
    }
}

@Composable
private fun DoneBody(report: RestoreReport, onDone: () -> Unit) {
    val nothingNew = report.totalRowsWritten == 0
    val baseBody = if (nothingNew) {
        "Nothing new to restore — your data was already up to date."
    } else {
        "${report.totalRowsWritten} row${if (report.totalRowsWritten == 1) "" else "s"} restored " +
            "(${report.itemsWritten} item${if (report.itemsWritten == 1) "" else "s"}, " +
            "${report.recordingsWritten} recording${if (report.recordingsWritten == 1) "" else "s"})."
    }
    val prefsSuffix = if (!report.prefsRestored) " Preferences couldn't be restored." else ""
    val indexingSuffix = if (report.itemsWritten > 0) {
        " Heed will index the new items in the background — search will warm up as it runs."
    } else ""
    HeedCallout(
        tone = CalloutTone.Brand,
        icon = Icons.Default.CheckCircle,
        title = "Restore complete",
        body = baseBody + prefsSuffix + indexingSuffix,
    )
    Spacer(Modifier.height(20.dp))
    HeedButton(text = "Done", onClick = onDone, style = HeedButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FailedBody(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    HeedCallout(
        tone = CalloutTone.Danger,
        icon = Icons.Default.Error,
        title = "Restore failed",
        body = message,
    )
    Spacer(Modifier.height(20.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HeedButton(text = "Back", onClick = onBack, style = HeedButtonStyle.Outlined, modifier = Modifier.weight(1f))
        HeedButton(text = "Try again", onClick = onRetry, style = HeedButtonStyle.Filled, modifier = Modifier.weight(1f))
    }
}
