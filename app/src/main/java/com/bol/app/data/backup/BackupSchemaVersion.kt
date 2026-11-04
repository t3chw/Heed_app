package com.bol.app.data.backup

/**
 * Wire-format version for Heed backup files.
 *
 * Bump [CURRENT] whenever the persisted schema in [BackupSchema] changes
 * in a way that older readers can't parse. Bump [MIN_SUPPORTED] only when
 * we deliberately drop support for very old files (e.g. after a major
 * v2.0 release with new required fields).
 *
 * The decoder rejects files with `schemaVersion > CURRENT` (file is from
 * a newer Heed) and files with `schemaVersion < MIN_SUPPORTED` (file is
 * older than we still support).
 */
object BackupSchemaVersion {
    const val CURRENT: Int = 1
    const val MIN_SUPPORTED: Int = 1
}
