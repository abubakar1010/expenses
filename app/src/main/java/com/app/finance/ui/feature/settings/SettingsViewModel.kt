package com.app.finance.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.data.export.ExportSummary
import com.app.finance.data.export.Exporter
import com.app.finance.data.export.ImportCounts
import com.app.finance.data.export.ImportMode
import com.app.finance.data.export.ImportOutcome
import com.app.finance.data.export.Importer
import com.app.finance.data.repo.SettingsRepository
import com.app.finance.domain.model.ThemeChoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock

/** What the screen reports after an action. Resolved to copy by the screen. */
sealed interface SettingsMessage {
    @JvmInline value class Exported(val rows: Int) : SettingsMessage
    @JvmInline value class Imported(val counts: ImportCounts) : SettingsMessage
    @JvmInline value class ImportFailed(val failure: ImportOutcome.Failure) : SettingsMessage
    data object ExportFailed : SettingsMessage
    data object Rebuilt : SettingsMessage
    data object Deleted : SettingsMessage
}

data class SettingsUiState(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** NFR-SEC-04 and FR-APP-04, both off until asked for. */
    val secureScreen: Boolean = false,
    val appLock: Boolean = false,
    /** Export and import are foreground with progress — 04 §5's dispatcher table. */
    val busy: Boolean = false,
    val message: SettingsMessage? = null,
    /** FR-DAT-03's choice, asked after the file is picked and before it is read. */
    val importPicker: (() -> InputStream?)? = null,
    /** FR-DAT-06's typed confirmation. Null when the sheet is closed. */
    val deleteTyped: String? = null,
) {
    val deleteArmed: Boolean get() = deleteTyped?.trim() == DELETE_WORD

    companion object {
        /** Matched exactly, and not translated — a typed word that varies by
         *  locale is a typed word somebody gets wrong under pressure. */
        const val DELETE_WORD = "DELETE"
    }
}

/**
 * Settings — FR-DAT-01 … FR-DAT-06, 03 §6's rebuild, and 04 §7's theme.
 *
 * **No Android types.** The screen owns the document picker and hands this class
 * an already-opened stream, which keeps every path here drivable from a test
 * with a `ByteArrayOutputStream` — and export/import is the one feature where a
 * bug is unrecoverable, so being able to test it without a device picker
 * matters more than the indirection costs.
 *
 * Everything runs on [io] with [SettingsUiState.busy] set. 04 §5 puts
 * export/import on "`Dispatchers.IO`, foreground with progress": these are the
 * only operations in the app the user has to wait for, and pretending otherwise
 * would mean a frozen screen with no explanation.
 */
class SettingsViewModel(
    private val exporter: Exporter,
    private val importer: Importer,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.observeTheme().collect { choice -> _state.update { it.copy(theme = choice) } }
        }
        viewModelScope.launch {
            settings.observeSecureScreen().collect { on -> _state.update { it.copy(secureScreen = on) } }
        }
        viewModelScope.launch {
            settings.observeAppLock().collect { on -> _state.update { it.copy(appLock = on) } }
        }
    }

    fun setTheme(choice: ThemeChoice) {
        viewModelScope.launch { withContext(io) { settings.setTheme(choice) } }
    }

    /** NFR-SEC-04. */
    fun setSecureScreen(on: Boolean) {
        viewModelScope.launch { withContext(io) { settings.setSecureScreen(on) } }
    }

    /** FR-APP-04. */
    fun setAppLock(on: Boolean) {
        viewModelScope.launch { withContext(io) { settings.setAppLock(on) } }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // --- FR-DAT-01, FR-DAT-02 ------------------------------------------------

    fun exportJson(open: () -> OutputStream?) =
        runExport(open) { out -> exporter.writeJson(out, clock.millis()) }

    fun exportCsv(open: () -> OutputStream?) =
        runExport(open) { out -> exporter.writeCsvArchive(out, clock.millis()) }

    private fun runExport(
        open: () -> OutputStream?,
        write: suspend (OutputStream) -> ExportSummary,
    ) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = withContext(io) {
                runCatching {
                    val out = open() ?: return@runCatching null
                    out.use { write(it) }
                }.getOrNull()
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = message
                        ?.let { s -> SettingsMessage.Exported(s.total) }
                        ?: SettingsMessage.ExportFailed,
                )
            }
        }
    }

    // --- FR-DAT-03 -----------------------------------------------------------

    /**
     * A file has been picked; the mode has not been chosen.
     *
     * Held rather than acted on, because "replace" deletes everything the user
     * has and the difference between the two buttons is the difference between
     * a restore and a merge. Asking after the picker rather than before it means
     * the question is about a file they can see the name of.
     */
    fun offerImport(open: () -> InputStream?) = _state.update { it.copy(importPicker = open) }

    fun cancelImport() = _state.update { it.copy(importPicker = null) }

    fun confirmImport(mode: ImportMode) {
        val open = _state.value.importPicker ?: return
        _state.update { it.copy(importPicker = null, busy = true, message = null) }
        viewModelScope.launch {
            val outcome = withContext(io) {
                val input = open() ?: return@withContext ImportOutcome.Failure.UNREADABLE
                input.use { importer.import(it, mode) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = when (outcome) {
                        is ImportOutcome.Done -> SettingsMessage.Imported(outcome.totals)
                        is ImportOutcome.Failure -> SettingsMessage.ImportFailed(outcome)
                    },
                )
            }
        }
    }

    // --- 03 §6 ---------------------------------------------------------------

    fun rebuildAggregates() {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            withContext(io) { settings.rebuildAggregates() }
            _state.update { it.copy(busy = false, message = SettingsMessage.Rebuilt) }
        }
    }

    // --- FR-DAT-06 -----------------------------------------------------------

    fun openDeleteAll() = _state.update { it.copy(deleteTyped = "") }

    fun onDeleteTyped(text: String) = _state.update { it.copy(deleteTyped = text) }

    fun cancelDeleteAll() = _state.update { it.copy(deleteTyped = null) }

    /** Refuses unless the word was typed exactly — the guard, not the button. */
    fun confirmDeleteAll() {
        if (!_state.value.deleteArmed) return
        _state.update { it.copy(deleteTyped = null, busy = true, message = null) }
        viewModelScope.launch {
            withContext(io) { settings.deleteAllData() }
            _state.update { it.copy(busy = false, message = SettingsMessage.Deleted) }
        }
    }
}
