package com.app.finance.ui.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.data.backup.BackupFile
import com.app.finance.data.backup.BackupStore
import com.app.finance.data.backup.FakeBackupStore
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.repo.BackupRepository
import com.app.finance.domain.model.BackupInterval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Backup screen's state machine — FR-DAT-07 … FR-DAT-11.
 *
 * There was no suite here, and its absence cost a defect: the screen folded two
 * different people's idea of "busy" into one flag with an `||`, so the first
 * automatic backup left the progress bar up and every control disabled until
 * the ViewModel was recreated. Nothing in the app noticed, because nothing was
 * looking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private lateinit var fx: TestFixture
    private lateinit var store: FakeBackupStore
    private lateinit var backups: BackupRepository
    private lateinit var vm: BackupViewModel

    /**
     * The ViewModel is built through a store so `viewModelScope` can be
     * cancelled, and that is not ceremony.
     *
     * `BackupViewModel.init` launches two collectors over Room flows that live
     * for as long as the scope does. Constructing the ViewModel directly left
     * them running after `fx.close()`, so they woke on a closed database and
     * threw on a Room executor thread — which the instrumentation attributes to
     * whichever test happens to run *next*. `CategoryManagerViewModelTest`, four
     * classes later, was the one that paid for it, and it passed in isolation
     * the whole time. CLAUDE.md warns about exactly this shape.
     */
    private val vmStore = ViewModelStore()

    /** Held open so a backup can be caught mid-flight rather than raced with. */
    private val gate = CompletableDeferred<Unit>()

    private val pass = "amar khata".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fx = TestFixture()
        store = FakeBackupStore()
        backups = BackupRepository(
            db = fx.db,
            exporter = fx.exporter,
            importer = fx.importer,
            settings = fx.settings,
            clock = fx.clock,
            storeFor = { GatedStore(store, gate) },
        )
        vm = ViewModelProvider(
            vmStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BackupViewModel(backups = backups, settings = fx.settings) as T
            },
        )[BackupViewModel::class.java]
    }

    @After
    fun tearDown() {
        // Order matters: release anything parked, cancel the collectors, *then*
        // let the pool drain and close.
        gate.complete(Unit)
        vmStore.clear()
        fx.closeAfterDraining()
        Dispatchers.resetMain()
    }

    // --- the defect this suite exists for -------------------------------------

    @Test
    fun the_screen_stops_being_busy_when_an_automatic_backup_ends() = runBlocking {
        // Driven through the repository rather than the screen, because that is
        // what FR-DAT-08 does: the launch-time run belongs to no screen and can
        // start before this one opens. The screen's own `working` flag is not
        // involved, which is the point — folding the two together is what broke.
        arm()

        val job = launch { backups.runNow() }
        // The gate guarantees the collector actually sees the flag go up; a
        // StateFlow conflates, so a fast backup could otherwise finish before
        // anyone observed it and the assertion below would pass vacuously.
        vm.state.awaitState { it.autoRunning }
        assertTrue("the bar should be up while a backup runs", vm.state.value.busy)

        gate.complete(Unit)
        job.join()

        vm.state.awaitState { !it.busy }
        assertFalse(vm.state.value.autoRunning)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun busy_is_derived_from_its_two_sources_and_cannot_latch() {
        // The structural half of the same fix. A stored flag can disagree with
        // what is actually happening; a derived one cannot.
        val idle = BackupUiState()
        assertFalse(idle.busy)
        assertTrue(idle.copy(working = true).busy)
        assertTrue(idle.copy(autoRunning = true).busy)
        assertFalse(idle.copy(working = true, autoRunning = true).copy(working = false, autoRunning = false).busy)
    }

    // --- the rest of the state machine ----------------------------------------

    @Test
    fun choosing_a_folder_does_not_switch_the_schedule_on() = runBlocking {
        // Half of the user's consent is *where* and half is *whether*. Turning
        // the second on for them would be the app deciding it by itself, which
        // is what NFR-SEC-01 is about.
        gate.complete(Unit)
        vm.onFolderChosen(TREE)

        vm.state.awaitState { it.settings.treeUri == TREE }
        assertEquals(BackupInterval.OFF, vm.state.value.settings.interval)
        assertFalse(vm.state.value.settings.isArmed)
    }

    @Test
    fun a_passphrase_must_be_long_enough_and_typed_twice() = runBlocking {
        gate.complete(Unit)
        vm.openPassphrase()

        vm.onPassphraseTyped("short")
        vm.onPassphraseRepeated("short")
        vm.savePassphrase()
        assertEquals(PassphraseError.TOO_SHORT, vm.state.value.passphrase?.error)

        vm.onPassphraseTyped("long enough")
        vm.onPassphraseRepeated("long enougi")
        vm.savePassphrase()
        assertEquals(PassphraseError.DIFFERS, vm.state.value.passphrase?.error)

        // Neither attempt may have armed anything.
        assertNull(fx.settings.backupSecret())
        assertFalse(vm.state.value.settings.encrypted)
    }

    @Test
    fun a_good_passphrase_arms_encryption_and_closes_the_sheet() = runBlocking {
        gate.complete(Unit)
        vm.openPassphrase()
        vm.onPassphraseTyped("cholish taka")
        vm.onPassphraseRepeated("cholish taka")
        vm.savePassphrase()

        vm.state.awaitState(timeoutMillis = 30_000) { it.settings.encrypted }
        assertNull("the sheet should close", vm.state.value.passphrase)
        assertEquals(BackupMessage.PassphraseSet, vm.state.value.message)
    }

    @Test
    fun turning_encryption_off_leaves_the_backups_already_written_alone() = runBlocking {
        // They stay encrypted and stay openable with the old passphrase.
        // Rewriting history to match a setting is how a folder loses the
        // generation somebody actually needed.
        gate.complete(Unit)
        arm()
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        backups.runNow()
        val before = store.names.toList()

        vm.clearPassphrase()
        vm.state.awaitState { !it.settings.encrypted }

        assertEquals(before, store.names)
        assertTrue(BackupCodec.needsPassphrase(store.bytesOf(before.single())!!.inputStream()))
    }

    @Test
    fun backing_up_without_a_folder_says_which_thing_is_missing() = runBlocking {
        gate.complete(Unit)
        vm.backUpNow()

        vm.state.awaitState { it.message != null }
        assertEquals(
            BackupMessage.Failed(com.app.finance.data.repo.BackupOutcome.Failure.NO_FOLDER),
            vm.state.value.message,
        )
    }

    @Test
    fun there_is_nothing_to_send_before_the_first_backup() = runBlocking {
        gate.complete(Unit)
        arm()
        vm.state.awaitState { it.settings.isArmed }

        assertNull(vm.state.value.newestId)
        vm.reportNothingToSend()
        assertEquals(BackupMessage.NothingToSend, vm.state.value.message)
    }

    @Test
    fun the_newest_backup_is_offered_once_one_exists() = runBlocking {
        gate.complete(Unit)
        arm()
        fx.expenses.insert(Money(4200), fx.leafId("Grocery"), fx.today)
        backups.runNow()

        vm.state.awaitState { it.newestId != null }
        assertEquals(store.names.single(), vm.state.value.newestName)
    }

    // --- helpers --------------------------------------------------------------

    private suspend fun arm() {
        fx.settings.setBackupFolder(TREE)
        fx.settings.setBackupInterval(BackupInterval.DAILY)
    }

    /**
     * [FakeBackupStore] that will not start until released.
     *
     * The only way to observe a state that exists solely *during* an operation:
     * a `StateFlow` conflates, so without a gate a fast backup finishes before
     * anything sees it running, and the assertion passes without testing
     * anything.
     */
    private class GatedStore(
        private val delegate: FakeBackupStore,
        private val gate: CompletableDeferred<Unit>,
    ) : BackupStore by delegate {
        override suspend fun create(name: String, mime: String): BackupFile? {
            gate.await()
            return delegate.create(name, mime)
        }
    }

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
    }
}
