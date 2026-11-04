package com.bol.app.data.backup

/**
 * What the Restore screen shows to the user after they pick a file but
 * before they commit to running the restore. Built from `manifest.json`
 * alone — no `data.json` parse needed — so even a 100 MB backup
 * previews instantly.
 *
 * `canRestore` is the gate the UI uses to enable / disable the action
 * button. When false, [incompatibleReason] carries a user-readable
 * message explaining why (file is from a newer Heed, file is encrypted
 * and v1 doesn't support encryption, etc.).
 */
data class BackupPreview(
    val manifest: BackupManifest,
    val canRestore: Boolean,
    val incompatibleReason: String? = null,
) {
    val counts: BackupCounts get() = manifest.counts
    val schemaVersion: Int get() = manifest.schemaVersion
    val exportedAt: Long get() = manifest.exportedAt
    val exportedFromTimezone: String get() = manifest.exportedFromTimezone
    val appVersionName: String get() = manifest.appVersionName
}
