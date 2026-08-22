package com.app.finance.domain.model

/**
 * Everything the automatic backup needs to know about itself — FR-DAT-07 …
 * FR-DAT-11.
 *
 * One value rather than seven `app_meta` reads, because every question the
 * backup asks ("is it due", "where does it go", "does it want a passphrase")
 * depends on several of these at once.
 *
 * [lastRevision] is the ledger's revision at the moment of the last successful
 * backup, not a timestamp. Comparing it to the current one is what stops a phone
 * opened every morning from rotating six real backups out of the folder to make
 * room for six identical copies of a week nothing happened in.
 */
data class BackupSettings(
    val treeUri: String?,
    val interval: BackupInterval,
    val keep: Int,
    val encrypted: Boolean,
    val lastAt: Long?,
    val lastCount: Int?,
    val lastRevision: Long?,
) {
    /** A folder has been chosen and a schedule turned on. */
    val isArmed: Boolean get() = treeUri != null && interval.isOn

    val hasEverRun: Boolean get() = lastAt != null
}
