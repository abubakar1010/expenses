package com.app.finance.data.repo

import android.util.Log
import com.app.finance.data.backup.BackupStore
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.Exporter
import com.app.finance.data.export.ImportCounts
import com.app.finance.data.export.ImportMode
import com.app.finance.data.export.ImportOutcome
import com.app.finance.data.export.Importer
import com.app.finance.domain.model.BackupSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a backup attempt did. */
sealed interface BackupOutcome {
    data class Done(val name: String, val rows: Int, val sizeBytes: Long) : BackupOutcome

    /** Nothing to do: not armed, not yet due, or nothing has changed. */
    data object Skipped : BackupOutcome

    enum class Failure : BackupOutcome {
        /** No folder has been nominated yet. */
        NO_FOLDER,

        /** The folder is gone, unmounted, or the grant was withdrawn. */
        UNREACHABLE,

        /** The folder is there and would not take the file. */
        WRITE_FAILED,
    }
}

/** What a restore did. Nothing between "done" and "nothing was changed" exists. */
sealed interface RestoreOutcome {
    data class Done(val counts: ImportCounts) : RestoreOutcome

    /** Refused by [Importer], with its own reason — every one leaves the database untouched. */
    data class Refused(val failure: ImportOutcome.Failure) : RestoreOutcome

    data object NeedsPassphrase : RestoreOutcome

    data object WrongPassphrase : RestoreOutcome
}

/**
 * The automatic backup — FR-DAT-07 … FR-DAT-12.
 *
 * It writes nothing new. `Exporter` produces the file, `BackupCodec` wraps it,
 * `Importer` reads it back, and all three were already here and already tested.
 * What lives in this class is only the four decisions around them: **whether**
 * to back up, **where**, **what to throw away**, and **what a restore must not
 * lose**.
 *
 * That is deliberately all it is. `data/repo/` is inside NFR-MAIN-02's 80%
 * coverage gate, [BackupStore] is an interface so a fake folder can stand in for
 * a document tree no test can be granted, and the arithmetic below — the one
 * place a bug quietly eats somebody's backups — is therefore drivable end to
 * end without a device picker.
 *
 * ### Why it runs on launch and not in the background
 *
 * The same reason `RecurringRepository.evaluate` does, and `MainActivity` states
 * it there: 04 §6 keeps `ContentProvider` initialisers off the startup path,
 * which rules out WorkManager's default initialisation, and 05 §12 has no
 * notification through which a background run could report anything. NFR-COMP-05
 * settles it — "no background work is required for core function" — and a
 * backup that only happens under Doze's good graces would make core data safety
 * depend on exactly the OEM battery policies that requirement refuses to trust.
 *
 * The cost is stated rather than hidden: **a phone left in a drawer is not
 * backed up until it is next opened.** That is the price of an app with no
 * foreground service and no network permission, and the copy on the Backup
 * screen says when backups happen rather than implying they are continuous.
 */
class BackupRepository(
    db: AppDatabase,
    private val exporter: Exporter,
    private val importer: Importer,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val storeFor: (String) -> BackupStore,
) {
    private val dao = db.backupDao()

    private val _running = MutableStateFlow(false)

    /**
     * Whether a backup is in flight, so the shell can show a bar.
     *
     * State on a repository, which nothing else here has, and it earns its place:
     * 04 §5.3 puts export at "`Dispatchers.IO`, foreground with progress", and
     * the automatic run belongs to no screen — it starts beside the first frame
     * and outlives whatever is on top of it.
     */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // --- FR-DAT-08 -----------------------------------------------------------

    /**
     * Backs up if the schedule says so, and does nothing at all otherwise.
     *
     * The cheap path is the common one: two `app_meta` reads and a return. It is
     * called on every launch, so anything heavier would be paid every launch —
     * and the full settings object is seven point queries, six of them wasted
     * on a phone that has never turned this on.
     */
    suspend fun runIfDue(): BackupOutcome {
        // Stops at two rows on a phone that has never turned this on, which is
        // the state every launch is in until the user says otherwise.
        if (!settings.backupArmed()) return BackupOutcome.Skipped

        val prefs = settings.backupSettings()
        if (!prefs.isArmed) return BackupOutcome.Skipped

        // Time before content, and the order is the point. `ledgerRevision` is a
        // scan of six tables, and at five years that is not free — but it can
        // only change the answer on a launch where the interval has already
        // elapsed. Asking the cheap question first means the scan runs about
        // once a day rather than every time the app is opened.
        if (!intervalElapsed(prefs)) return BackupOutcome.Skipped

        val revision = dao.ledgerRevision()
        if (prefs.lastRevision == revision) return BackupOutcome.Skipped

        return write(prefs, revision)
    }

    /** The "Back up now" button. Runs whatever the schedule says, folder permitting. */
    suspend fun runNow(): BackupOutcome {
        val prefs = settings.backupSettings()
        if (prefs.treeUri == null) return BackupOutcome.Failure.NO_FOLDER
        return write(prefs, dao.ledgerRevision())
    }

    /**
     * Has the schedule come round again?
     *
     * The other half of "is it due" is in [runIfDue], because the two halves
     * cost very different amounts. This one is arithmetic on a value already
     * read; the other is a scan.
     *
     * The revision half is the one worth having at all. Without it, a phone
     * opened every morning writes a byte-identical copy each time and rotates a
     * real backup out of the folder to make room for it — after
     * [BackupSettings.keep] quiet days the oldest genuine backup is gone and
     * every remaining file is the same week. Retention would be actively
     * destroying history in the name of keeping it.
     */
    private fun intervalElapsed(prefs: BackupSettings): Boolean {
        val last = prefs.lastAt ?: return true
        val elapsed = clock.millis() - last
        // A negative elapsed means the clock moved backwards — a timezone
        // change, or the user correcting the date. Back up rather than wait for
        // it to catch up, which could be months.
        return elapsed < 0 || elapsed >= prefs.interval.days * MILLIS_PER_DAY
    }

    private suspend fun write(prefs: BackupSettings, revision: Long): BackupOutcome {
        val tree = prefs.treeUri ?: return BackupOutcome.Failure.NO_FOLDER
        val store = storeFor(tree)
        if (!store.isReachable()) return BackupOutcome.Failure.UNREACHABLE

        _running.value = true
        try {
            val at = clock.millis()
            val name = BackupCodec.fileName(stamp(at))

            // Written under `.part` and renamed only after a clean close. The
            // codec would detect a truncated file on the way back in, but
            // rotation counts files by name and would otherwise count a
            // half-written one as a good generation — throwing away a real
            // backup to keep a broken one.
            val partial = store.create(name + BackupCodec.PARTIAL, BackupCodec.MIME)
                ?: return BackupOutcome.Failure.WRITE_FAILED

            val summary = try {
                val sink = store.write(partial.id) ?: throw IOException("the folder would not open the file")
                // `use` is the belt, not the braces. `writeJson` closes what it
                // is given, which closes the codec, which seals the last frame
                // and closes this — but only once `encode` has returned. Deriving
                // the key or writing the header can throw before that, and
                // without `use` the document is left open: a StrictMode
                // `detectLeakedClosableObjects` violation, and on some providers
                // a document that stays locked until the process dies. Closing
                // twice is a no-op on every stream in the chain.
                sink.use { exporter.writeJson(BackupCodec.encode(it, settings.backupSecret()), at) }
            } catch (e: Exception) {
                // Logged, not swallowed. §18.7 A12 is the precedent: a failed
                // restore of five years of data with nothing to diagnose is a
                // defect in its own right, and a failed *backup* is the same
                // shape — the user is told it did not work and nobody can say
                // why.
                Log.w(TAG, "backup failed while writing", e)
                store.delete(partial.id)
                return BackupOutcome.Failure.WRITE_FAILED
            }

            // A file that could not be given its final name is not a backup.
            //
            // The content is written and valid, but `isBackupName` will not
            // match it, so rotation ignores it and `list()` never shows it —
            // the user would be told they have a backup that nothing in the app
            // can find, and the next rotation would count one fewer generation
            // than it has. Better to fail honestly and try again next launch.
            val done = store.rename(partial.id, name) ?: run {
                Log.w(TAG, "backup written but could not be renamed from its .part name")
                store.delete(partial.id)
                return BackupOutcome.Failure.WRITE_FAILED
            }

            // Recorded only now. A crash before this point leaves the app
            // believing it has not backed up since the last success, which is
            // the belief that makes it try again.
            settings.recordBackup(at, summary.total, revision)
            rotate(store, prefs.keep, keeping = done.id)

            return BackupOutcome.Done(done.name, summary.total, done.sizeBytes)
        } finally {
            _running.value = false
        }
    }

    /**
     * FR-DAT-09 — keep the newest [keep], delete the rest.
     *
     * Sorted by name and not by modification time: the stamp is ISO-ordered so
     * it sorts lexically, and more than one document provider reports a last
     * modified of zero for everything it holds.
     *
     * Only files this app wrote are touched. A backup folder is a folder the
     * user chose, and it may well be one they keep other things in.
     */
    private suspend fun rotate(store: BackupStore, keep: Int, keeping: String) {
        val all = store.list()

        // Leftovers from a write that was killed part-way. Never the one just
        // finished, whose rename may have been a no-op on this provider.
        all.filter {
            it.id != keeping &&
                it.name.startsWith(BackupCodec.NAME_PREFIX) &&
                it.name.endsWith(BackupCodec.PARTIAL)
        }.forEach { store.delete(it.id) }

        all.filter { BackupCodec.isBackupName(it.name) }
            .sortedByDescending { it.name }
            .drop(keep)
            .forEach { store.delete(it.id) }
    }

    /** The folder as the user would recognise it, or null if it cannot be reached. */
    suspend fun folderLabel(): String? {
        val tree = settings.backupSettings().treeUri ?: return null
        return storeFor(tree).label()
    }

    /** Backups in the folder, newest first. Empty when the folder is gone. */
    suspend fun list(): List<com.app.finance.data.backup.BackupFile> {
        val tree = settings.backupSettings().treeUri ?: return emptyList()
        return storeFor(tree).list()
            .filter { BackupCodec.isBackupName(it.name) }
            .sortedByDescending { it.name }
    }

    /** The newest backup, which is what "Send a copy" sends. */
    suspend fun newest() = list().firstOrNull()

    // --- FR-DAT-10, FR-DAT-11 ------------------------------------------------

    /**
     * Whether [open]'s file will ask for a passphrase, so the screen knows
     * whether to offer the field before it demands a secret nobody has.
     */
    suspend fun needsPassphrase(open: () -> InputStream?): Boolean =
        open()?.use { runCatching { BackupCodec.needsPassphrase(it) }.getOrDefault(false) } ?: false

    /**
     * Restores from a backup file.
     *
     * Every failure below leaves the database exactly as it was — NFR-REL-04,
     * and `Importer` already runs the whole import in one transaction. The two
     * passphrase cases are separated from the rest because they are the ones the
     * user can do something about.
     *
     * **The folder grant and the key survive the restore.** A REPLACE wipes
     * `app_meta` and refills it from the file, and the file deliberately does
     * not carry either of them (`AppMetaDao.TRANSIENT_KEYS`) — so without this
     * the app would come back with its ledger intact and its backups quietly
     * switched off. "Restore my ledger" is not "unconfigure my backups".
     */
    suspend fun restore(
        open: () -> InputStream?,
        passphrase: CharArray?,
        mode: ImportMode,
    ): RestoreOutcome {
        val keptTree = settings.backupSettings().treeUri
        val keptSecret = settings.backupSecret()

        val input = open() ?: return RestoreOutcome.Refused(ImportOutcome.Failure.UNREADABLE)

        val decoded = try {
            BackupCodec.decode(input, passphrase)
        } catch (e: BackupCodec.NeedsPassphrase) {
            input.close()
            return RestoreOutcome.NeedsPassphrase
        } catch (e: BackupCodec.WrongPassphrase) {
            input.close()
            return RestoreOutcome.WrongPassphrase
        } catch (e: BackupCodec.NewerFormat) {
            // Not damaged — written by a later release. FR-DAT-05's sentence
            // fits exactly: update, then import again.
            input.close()
            Log.w(TAG, "restore refused: the backup is from a newer version", e)
            return RestoreOutcome.Refused(ImportOutcome.Failure.NEWER_SCHEMA)
        } catch (e: BackupCodec.CorruptBackup) {
            // Ours, and damaged — "that backup couldn't be read to the end".
            // Kept apart from the case below on purpose: the codec goes to some
            // trouble to tell a Khata file that has been altered from a file
            // that was never one, and collapsing both into "this isn't a Khata
            // backup" would throw that away at the last step and tell a user
            // with a truncated backup to go and find a different file.
            input.close()
            Log.w(TAG, "restore refused: the file is a damaged backup", e)
            return RestoreOutcome.Refused(ImportOutcome.Failure.REJECTED)
        } catch (e: IOException) {
            input.close()
            Log.w(TAG, "restore refused: the file is not a backup", e)
            return RestoreOutcome.Refused(ImportOutcome.Failure.UNREADABLE)
        }

        // Damage that only shows up part-way through the file — a failed AEAD
        // tag, a truncated gzip stream — is thrown while `Importer` is reading,
        // and `Importer` reports every read failure as UNREADABLE: "that file
        // isn't a Khata backup". For a backup that *is* one and is merely
        // damaged, that is the wrong sentence and an unhelpful one, and it
        // silently undid the distinction the codec goes to some trouble to draw.
        //
        // So the stream remembers what escaped it, and the verdict below prefers
        // that over the importer's guess.
        val watched = DamageWatch(decoded)
        val outcome = watched.use { importer.import(it, mode) }

        return when (outcome) {
            is ImportOutcome.Done -> {
                settings.forgetBackupHistory()
                keptTree?.let { settings.setBackupFolder(it) }
                keptSecret?.let { settings.setBackupSecret(it) }
                RestoreOutcome.Done(outcome.totals)
            }

            is ImportOutcome.Failure -> {
                val damage = watched.failure
                if (damage != null) {
                    Log.w(TAG, "restore refused: the backup is damaged part-way through", damage)
                    RestoreOutcome.Refused(ImportOutcome.Failure.REJECTED)
                } else {
                    RestoreOutcome.Refused(outcome)
                }
            }
        }
    }

    /**
     * Remembers the first `IOException` to escape [delegate].
     *
     * `Importer` cannot tell a damaged backup from a file that was never one —
     * it sees a read that failed either way, and calls both UNREADABLE. This is
     * how the difference survives the trip: everything the codec throws passes
     * through here on its way out.
     */
    private class DamageWatch(private val delegate: InputStream) : InputStream() {
        var failure: IOException? = null
            private set

        override fun read(): Int = watch { delegate.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = watch { delegate.read(b, off, len) }

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()

        private inline fun watch(block: () -> Int): Int =
            try {
                block()
            } catch (e: IOException) {
                if (failure == null) failure = e
                throw e
            }
    }

    private fun stamp(at: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(at), clock.zone).format(STAMP)

    private companion object {
        const val TAG = "Khata"
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /**
         * `Locale.ROOT`, and not by habit.
         *
         * `ofPattern` without one formats in the default locale, and a phone set
         * to Bengali renders the year as ২০২৬. Rotation sorts these names
         * lexically, so a device-locale stamp would produce backups that sort
         * into a different order than they were taken in — and the oldest file
         * deleted would be whichever one happened to sort last.
         */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)
    }
}
