package com.bol.app.data.backup

/**
 * Progress events emitted by the restore pipeline. The UI maps each
 * variant to a phase string and a busy state. The flow always ends
 * with either [Done] or [Failed]; intermediate states may be skipped
 * if a phase finishes before the next one starts (the flow doesn't
 * guarantee one emission per phase, only that ordering is monotonic).
 */
sealed class RestoreProgress {
    /** The codec is reading the ZIP, validating the manifest and the
     *  data.json checksum. No DB writes yet. */
    object Validating : RestoreProgress()

    /** REPLACE mode only — wiping the existing tables before insert. */
    object Wiping : RestoreProgress()

    /** Bulk inserting rows. Reported per-table so the UI can show
     *  "Writing 1432 items …" without per-row chatter. */
    data class Writing(val table: String) : RestoreProgress()

    /** DataStore preferences are being applied. Runs after the DB
     *  transaction commits so a DataStore failure doesn't roll back
     *  the data restore. */
    object RestoringPrefs : RestoreProgress()

    /** Restore finished successfully. [report] carries per-table
     *  counts so the UI can show a confirmation snackbar. */
    data class Done(val report: RestoreReport) : RestoreProgress()

    /** Restore aborted. The DB transaction (if any was open) was
     *  rolled back. [message] is user-facing; [error] is the
     *  underlying throwable for logging. */
    data class Failed(val message: String, val error: Throwable) : RestoreProgress()
}

/**
 * Per-table counts of what actually landed in the DB. Distinct from
 * the manifest counts because in MERGE mode rows whose primary key
 * already existed locally are skipped — so `categoriesWritten` here
 * is `<= manifest.counts.categories`.
 */
data class RestoreReport(
    val mode: RestoreMode,
    val categoriesWritten: Int,
    val itemsWritten: Int,
    val recordingsWritten: Int,
    val itemTagsWritten: Int,
    val recordingTagsWritten: Int,
    val itemEnrichmentsWritten: Int,
    val searchQueriesWritten: Int,
    val userContextWritten: Int,
    val prefsRestored: Boolean,
) {
    val totalRowsWritten: Int = categoriesWritten + itemsWritten + recordingsWritten +
        itemTagsWritten + recordingTagsWritten + itemEnrichmentsWritten +
        searchQueriesWritten + userContextWritten
}
