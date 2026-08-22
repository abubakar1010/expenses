package com.app.finance.ui.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.ImportCounts
import com.app.finance.data.export.ImportMode
import com.app.finance.data.export.ImportOutcome
import com.app.finance.data.repo.BackupOutcome
import com.app.finance.data.repo.BackupRepository
import com.app.finance.data.repo.RestoreOutcome
import com.app.finance.data.repo.SettingsRepository
import com.app.finance.domain.model.BackupInterval
import com.app.finance.domain.model.BackupSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** What the screen reports after an action. Resolved to copy by the screen. */
sealed interface BackupMessage {
    @JvmInline value class Done(val name: String) : BackupMessage
    @JvmInline value class Failed(val failure: BackupOutcome.Failure) : BackupMessage
    @JvmInline value class Restored(val counts: ImportCounts) : BackupMessage
    @JvmInline value class RestoreRefused(val failure: ImportOutcome.Failure) : BackupMessage
    data object PassphraseSet : BackupMessage
    data object PassphraseCleared : BackupMessage
    data object NothingToSend : BackupMessage
}

enum class PassphraseError { TOO_SHORT, DIFFERS }

/** The sheet that sets a passphrase. Null when it is closed. */
data class PassphraseDraft(
    val first: String = "",
    val second: String = "",
    val error: PassphraseError? = null,
) {
    val armed: Boolean get() = first.length >= MINIMUM && first == second

    companion object {
        /**
         * Eight, and no upper bound or character classes.
         *
         * Composition rules push people towards `Taka@123`, and this passphrase
         * has to be remembered without a manager for as long as the backups
         * live — years, potentially on a phone that no longer exists. Length is
         * the only rule that helps here and does not fight that.
         */
        const val MINIMUM = 8
    }
}

/** A file has been picked and is waiting on a decision. Null when nothing is pending. */
data class RestoreDraft(
    val open: () -> InputStream?,
    val locked: Boolean = false,
    val typed: String = "",
    val wrong: Boolean = false,
)

data class BackupUiState(
    val settings: BackupSettings = EMPTY,
    val folderLabel: String? = null,
    /** A folder was chosen and cannot be reached — the card is out, or the grant went. */
    val folderMissing: Boolean = false,
    val busy: Boolean = false,
    val message: BackupMessage? = null,
    val passphrase: PassphraseDraft? = null,
    val restore: RestoreDraft? = null,
    /** The newest backup, which is what "Send a copy" sends. */
    val newestId: String? = null,
    val newestName: String? = null,
) {
    val hasFolder: Boolean get() = settings.treeUri != null

    private companion object {
        val EMPTY = BackupSettings(
            treeUri = null,
            interval = BackupInterval.OFF,
            keep = 5,
            encrypted = false,
            lastAt = null,
            lastCount = null,
            lastRevision = null,
        )
    }
}

/**
 * The Backup screen — FR-DAT-07 … FR-DAT-12.
 *
 * **No Android types**, the same rule `SettingsViewModel` states and for the
 * same reason: the screen owns the folder picker and the document picker and
 * hands this class a tree string or an already-opened stream. Backup is the one
 * feature whose bugs are discovered only when they are unrecoverable, so being
 * able to drive every path from a test without a picker matters more than the
 * indirection costs.
 *
 * Everything runs on [io] with [BackupUiState.busy] set, per 04 §5.3's
 * "`Dispatchers.IO`, foreground with progress". Two things here genuinely take
 * seconds on the reference device — writing five years of ledger, and the
 * 210,000 PBKDF2 rounds behind a new passphrase — and pretending otherwise
 * would mean a frozen screen with no explanation.
 */
class BackupViewModel(
    private val backups: BackupRepository,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.observeBackupSettings().collect { prefs ->
                _state.update { it.copy(settings = prefs) }
                refreshFolder()
            }
        }
        viewModelScope.launch {
            // The automatic run belongs to no screen; if one is in flight when
            // this opens, the bar should already be there.
            backups.running.collect { on -> _state.update { it.copy(busy = it.busy || on) } }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // --- FR-DAT-07 -----------------------------------------------------------

    /** [tree] is the persisted grant; the screen took it before calling. */
    fun onFolderChosen(tree: String) {
        viewModelScope.launch {
            withContext(io) {
                settings.setBackupFolder(tree)
                // Choosing a folder is the point at which the user has said
                // where. Turning the schedule on for them here would be the app
                // deciding the other half by itself, which is exactly what
                // NFR-SEC-01 is about — so the interval stays whatever it was.
            }
            refreshFolder()
        }
    }

    fun setInterval(interval: BackupInterval) {
        viewModelScope.launch { withContext(io) { settings.setBackupInterval(interval) } }
    }

    fun setKeep(keep: Int) {
        viewModelScope.launch { withContext(io) { settings.setBackupKeep(keep) } }
    }

    // --- FR-DAT-08 -----------------------------------------------------------

    fun backUpNow() {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = withContext(io) { backups.runNow() }
            _state.update {
                it.copy(
                    busy = false,
                    message = when (outcome) {
                        is BackupOutcome.Done -> BackupMessage.Done(outcome.name)
                        is BackupOutcome.Failure -> BackupMessage.Failed(outcome)
                        // "Back up now" only skips when there is no folder, and
                        // `runNow` reports that as a failure instead.
                        BackupOutcome.Skipped -> null
                    },
                )
            }
            refreshFolder()
        }
    }

    // --- FR-DAT-11 -----------------------------------------------------------

    fun openPassphrase() = _state.update { it.copy(passphrase = PassphraseDraft()) }

    fun cancelPassphrase() = _state.update { it.copy(passphrase = null) }

    fun onPassphraseTyped(first: String) =
        _state.update { it.copy(passphrase = it.passphrase?.copy(first = first, error = null)) }

    fun onPassphraseRepeated(second: String) =
        _state.update { it.copy(passphrase = it.passphrase?.copy(second = second, error = null)) }

    /**
     * Derives the key and stores it.
     *
     * Slow — deliberately, and once. `BackupCodec.Secret` has the reasoning for
     * keeping the key rather than re-deriving on every automatic run.
     */
    fun savePassphrase() {
        val draft = _state.value.passphrase ?: return
        val error = when {
            draft.first.length < PassphraseDraft.MINIMUM -> PassphraseError.TOO_SHORT
            draft.first != draft.second -> PassphraseError.DIFFERS
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(passphrase = draft.copy(error = error)) }
            return
        }

        _state.update { it.copy(passphrase = null, busy = true, message = null) }
        viewModelScope.launch {
            withContext(io) {
                settings.setBackupSecret(BackupCodec.secretFrom(draft.first.toCharArray()))
            }
            _state.update { it.copy(busy = false, message = BackupMessage.PassphraseSet) }
        }
    }

    /**
     * Turns encryption off.
     *
     * The backups already written stay encrypted and stay openable with the old
     * passphrase — this only changes what the next one is sealed with. Nothing
     * is re-written, because rewriting history to match a setting is how a
     * backup folder loses the generation somebody actually needed.
     */
    fun clearPassphrase() {
        viewModelScope.launch {
            withContext(io) { settings.setBackupSecret(null) }
            _state.update { it.copy(message = BackupMessage.PassphraseCleared) }
        }
    }

    // --- FR-DAT-10, FR-DAT-12 ------------------------------------------------

    /**
     * A file has been picked; nothing has been read yet.
     *
     * Held rather than acted on, for the reason `SettingsViewModel.offerImport`
     * gives: "replace" deletes everything the user has, and the difference
     * between the two buttons is the difference between a restore and a merge.
     * The passphrase question is settled first so the sheet does not demand a
     * secret for a file that does not want one.
     */
    fun offerRestore(open: () -> InputStream?) {
        viewModelScope.launch {
            val locked = withContext(io) { backups.needsPassphrase(open) }
            _state.update { it.copy(restore = RestoreDraft(open = open, locked = locked)) }
        }
    }

    fun cancelRestore() = _state.update { it.copy(restore = null) }

    fun onRestorePassphraseTyped(typed: String) =
        _state.update { it.copy(restore = it.restore?.copy(typed = typed, wrong = false)) }

    fun confirmRestore(mode: ImportMode) {
        val draft = _state.value.restore ?: return
        _state.update { it.copy(busy = true, message = null) }

        viewModelScope.launch {
            val pass = draft.typed.takeIf { it.isNotEmpty() }?.toCharArray()
            val outcome = withContext(io) { backups.restore(draft.open, pass, mode) }

            _state.update {
                when (outcome) {
                    is RestoreOutcome.Done ->
                        it.copy(busy = false, restore = null, message = BackupMessage.Restored(outcome.counts))

                    is RestoreOutcome.Refused ->
                        it.copy(busy = false, restore = null, message = BackupMessage.RestoreRefused(outcome.failure))

                    // Both keep the sheet open: the user can fix these, and
                    // closing it would make them find the file again.
                    RestoreOutcome.WrongPassphrase ->
                        it.copy(busy = false, restore = draft.copy(locked = true, wrong = true))

                    RestoreOutcome.NeedsPassphrase ->
                        it.copy(busy = false, restore = draft.copy(locked = true))
                }
            }
        }
    }

    // --- sending a copy off the device ---------------------------------------

    /** Nothing to send is a message, not a disabled button with no explanation. */
    fun reportNothingToSend() = _state.update { it.copy(message = BackupMessage.NothingToSend) }

    private suspend fun refreshFolder() {
        val prefs = _state.value.settings
        if (prefs.treeUri == null) {
            _state.update { it.copy(folderLabel = null, folderMissing = false, newestId = null, newestName = null) }
            return
        }
        val label = withContext(io) { backups.folderLabel() }
        val newest = withContext(io) { backups.newest() }
        _state.update {
            it.copy(
                folderLabel = label,
                folderMissing = label == null,
                newestId = newest?.id,
                newestName = newest?.name,
            )
        }
    }
}
