package com.bol.app.data.backup

/**
 * How the importer should reconcile a backup against the user's
 * current data.
 *
 * The two modes mirror what most privacy-first apps offer (Standard
 * Notes, Joplin, Obsidian sync vaults): a non-destructive "fill the
 * gaps" merge, and a destructive "make my device match the file"
 * replace. Defaulting to [MERGE] in the UI keeps the destructive
 * path off the happy path — users have to opt into [REPLACE].
 */
enum class RestoreMode {
    /**
     * Add what's missing, keep what's local. Rows whose primary key
     * already exists locally are skipped. New rows are inserted.
     * Tag tables also get duplicate-tuple protection (e.g. you won't
     * end up with two rows for the same `(itemId, tag)` pair).
     *
     * Safe to run repeatedly with the same file.
     */
    MERGE,

    /**
     * Wipe the user's data first, then insert everything from the
     * backup. After this completes, the device's data is identical
     * to whatever the backup file contained. Categories, items,
     * recordings, tags, enrichments, search history, and user-context
     * rows are all replaced.
     *
     * DataStore preferences are still merged on top — only non-null
     * fields in the backup overwrite local prefs, so per-device
     * settings the user tweaked since exporting (e.g. theme) survive.
     */
    REPLACE,
}
