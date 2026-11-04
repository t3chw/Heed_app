package com.bol.app.ui.screens.restore

import android.net.Uri
import com.bol.app.data.backup.BackupPreview
import com.bol.app.data.backup.RestoreMode
import com.bol.app.data.backup.RestoreProgress
import com.bol.app.data.backup.RestoreReport

/**
 * Phase the Restore screen is in. Each phase carries the data the
 * corresponding UI needs, so the screen can `when (phase)` without
 * threading separate state fields through.
 */
sealed class RestorePhase {
    /** Initial state — show the "Pick a backup file" CTA. */
    object AwaitingPick : RestorePhase()

    /** SAF returned a URI; we're reading the manifest. */
    object LoadingPreview : RestorePhase()

    /** Manifest read OK and the file is restorable on this Heed build. */
    data class PreviewReady(val preview: BackupPreview) : RestorePhase()

    /** Manifest read OK but the file is NOT restorable (encryption,
     *  schema version mismatch). [preview.incompatibleReason] is set. */
    data class PreviewIncompatible(val preview: BackupPreview) : RestorePhase()

    /** Reading the manifest failed (file isn't a Heed backup, IO error). */
    data class PreviewError(val message: String) : RestorePhase()

    /** Restore is in progress; [progress] reflects the latest event from
     *  the Flow. */
    data class Restoring(val progress: RestoreProgress) : RestorePhase()

    /** Restore finished successfully. */
    data class Done(val report: RestoreReport) : RestorePhase()

    /** Restore aborted at any step. The DB transaction (if it had been
     *  opened) was rolled back. */
    data class Failed(val message: String) : RestorePhase()
}

/**
 * Top-level state for the Restore screen. The phase carries most of
 * the data; the rest of the fields are user-controlled inputs that
 * survive across phases (e.g. mode + confirm text persist while the
 * preview loads).
 */
data class RestoreUiState(
    val phase: RestorePhase = RestorePhase.AwaitingPick,
    val sourceUri: Uri? = null,
    val mode: RestoreMode = RestoreMode.MERGE,
    /** GitHub-style typed-confirmation text for [RestoreMode.REPLACE].
     *  The destructive Restore button only enables when this equals
     *  exactly "REPLACE". Empty for MERGE mode. */
    val replaceConfirmText: String = "",
)

/** True when the Restore button should be enabled. */
val RestoreUiState.canRunRestore: Boolean
    get() = phase is RestorePhase.PreviewReady &&
        (mode == RestoreMode.MERGE || replaceConfirmText == "REPLACE")
