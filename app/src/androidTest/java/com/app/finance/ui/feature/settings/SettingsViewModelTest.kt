package com.app.finance.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.export.ImportMode
import com.app.finance.domain.model.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * Settings — FR-DAT-01 … FR-DAT-06, and 03 §6's rebuild.
 *
 * The ViewModel takes an already-opened stream rather than a `Uri`, which is
 * what lets every path here be driven with a `ByteArrayOutputStream`. Export
 * and import are the one feature where a bug is unrecoverable, so being able to
 * test them without a document picker is worth the indirection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    private lateinit var fx: TestFixture
    private val store = ViewModelStore()
    private var seq = 0
    private val aug = Period(202608)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fx = TestFixture()
    }

    @After
    fun tearDown() {
        store.clear()
        fx.closeAfterDraining()
        Dispatchers.resetMain()
    }

    private fun vm(): SettingsViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(fx.exporter, fx.importer, fx.settings, fx.clock) as T
        },
    )["vm${seq++}", SettingsViewModel::class.java]

    private fun seed() = runBlocking {
        fx.expenses.insert(Money.ofTaka(340), fx.leafId("Grocery"), LocalDate.of(2026, 8, 3))
        fx.income.saveEntry(Money.ofTaka(30_000), "Salary", LocalDate.of(2026, 8, 1))
        fx.budgets.setLimit(fx.leafId("Grocery"), aug, Money.ofTaka(18_000))
    }

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    // --- FR-DAT-01, FR-DAT-02 -------------------------------------------------

    @Test
    fun exporting_json_writes_a_file_and_reports_the_row_count() = runBlocking {
        seed()
        val out = ByteArrayOutputStream()
        val vm = vm()

        vm.exportJson { out }

        val state = vm.state.awaitState { !it.busy && it.message != null }
        val message = state.message as SettingsMessage.Exported
        assertTrue("expected rows, got ${message.rows}", message.rows > 0)
        assertTrue(out.toString().startsWith("{\"schema_version\""))
    }

    @Test
    fun exporting_csv_writes_an_archive() = runBlocking {
        seed()
        val out = ByteArrayOutputStream()
        val vm = vm()

        vm.exportCsv { out }

        vm.state.awaitState { !it.busy && it.message is SettingsMessage.Exported }
        // PK — the zip local file header.
        assertEquals(0x50, out.toByteArray()[0].toInt())
        assertEquals(0x4B, out.toByteArray()[1].toInt())
    }

    @Test
    fun a_destination_that_cannot_be_opened_is_reported_rather_than_swallowed() = runBlocking {
        val vm = vm()
        vm.exportJson { null }

        val state = vm.state.awaitState { !it.busy && it.message != null }
        assertEquals(SettingsMessage.ExportFailed, state.message)
    }

    // --- FR-DAT-03 ------------------------------------------------------------

    @Test
    fun a_picked_file_waits_for_the_mode_before_anything_is_read() = runBlocking {
        // "Replace" deletes everything the user has. The difference between the
        // two buttons is the difference between a restore and a merge, so the
        // file is held rather than acted on.
        seed()
        val before = scalar("SELECT COUNT(*) FROM expense")
        val vm = vm()

        vm.offerImport { ByteArrayInputStream(ByteArray(0)) }

        val state = vm.state.awaitState { it.importPicker != null }
        assertTrue(state.importPicker != null)
        assertEquals("nothing was read yet", before, scalar("SELECT COUNT(*) FROM expense"))
    }

    @Test
    fun cancelling_the_mode_choice_leaves_the_database_alone() = runBlocking {
        seed()
        val before = scalar("SELECT COUNT(*) FROM expense")
        val vm = vm()

        vm.offerImport { ByteArrayInputStream(ByteArray(0)) }
        vm.state.awaitState { it.importPicker != null }
        vm.cancelImport()

        assertEquals(null, vm.state.awaitState { it.importPicker == null }.importPicker)
        assertEquals(before, scalar("SELECT COUNT(*) FROM expense"))
    }

    @Test
    fun a_merge_reports_the_three_counts() = runBlocking {
        seed()
        val out = ByteArrayOutputStream()
        fx.exporter.writeJson(out, fx.clock.millis())

        val vm = vm()
        vm.offerImport { ByteArrayInputStream(out.toByteArray()) }
        vm.state.awaitState { it.importPicker != null }
        vm.confirmImport(ImportMode.MERGE)

        val state = vm.state.awaitState { !it.busy && it.message is SettingsMessage.Imported }
        val counts = (state.message as SettingsMessage.Imported).counts
        assertEquals("its own file, so nothing new", 0, counts.inserted)
        assertTrue("and everything recognised", counts.skipped > 0)
    }

    @Test
    fun an_unreadable_file_is_reported_with_its_own_message() = runBlocking {
        seed()
        val vm = vm()
        vm.offerImport { ByteArrayInputStream("not a backup".toByteArray()) }
        vm.state.awaitState { it.importPicker != null }
        vm.confirmImport(ImportMode.REPLACE)

        val state = vm.state.awaitState { !it.busy && it.message != null }
        assertTrue(state.message is SettingsMessage.ImportFailed)
        assertEquals("and the ledger is untouched", 1, scalar("SELECT COUNT(*) FROM expense"))
    }

    // --- 03 §6 ----------------------------------------------------------------

    @Test
    fun rebuilding_the_aggregates_changes_no_figure() = runBlocking {
        // It is the recovery path, not a correction. `assertion19` proves the
        // rebuild reproduces the trigger-maintained state; this is that promise
        // from the user's side of the button.
        seed()
        val before = scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month")

        val vm = vm()
        vm.rebuildAggregates()
        vm.state.awaitState { !it.busy && it.message == SettingsMessage.Rebuilt }

        assertEquals(before, scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month"))
    }

    @Test
    fun rebuilding_repairs_a_rollup_somebody_corrupted() = runBlocking {
        seed()
        fx.db.openHelper.writableDatabase.execSQL("UPDATE rollup_expense_month SET total_minor = 1")

        val vm = vm()
        vm.rebuildAggregates()
        vm.state.awaitState { !it.busy && it.message == SettingsMessage.Rebuilt }

        assertEquals(
            scalar("SELECT IFNULL(SUM(amount_minor), 0) FROM expense WHERE status = 0"),
            scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month"),
        )
    }

    // --- FR-DAT-06 ------------------------------------------------------------

    @Test
    fun the_delete_stays_disarmed_until_the_word_is_exact() = runBlocking {
        // 05 §8's single exception to "no confirmation dialogs", "because there
        // is no undo for it". The typing *is* the confirmation.
        val vm = vm()
        vm.openDeleteAll()
        vm.state.awaitState { it.deleteTyped != null }

        listOf("", "delete", "DELET", "DELETE ALL").forEach { typed ->
            vm.onDeleteTyped(typed)
            assertFalse("'$typed' must not arm it", vm.state.value.deleteArmed)
        }
        vm.onDeleteTyped("DELETE")
        assertTrue(vm.state.value.deleteArmed)
    }

    @Test
    fun confirming_without_the_word_does_nothing_at_all() = runBlocking {
        seed()
        val vm = vm()
        vm.openDeleteAll()
        vm.onDeleteTyped("nope")
        vm.confirmDeleteAll()

        assertEquals(1, scalar("SELECT COUNT(*) FROM expense"))
    }

    @Test
    fun deleting_everything_leaves_a_fresh_install_rather_than_a_broken_one() = runBlocking {
        // An expense must reference a leaf (FR-EXP-04, by trigger), so a wipe
        // that left the category table empty would leave an app that cannot
        // record anything.
        seed()
        val vm = vm()
        vm.openDeleteAll()
        vm.onDeleteTyped("DELETE")
        vm.confirmDeleteAll()

        vm.state.awaitState { !it.busy && it.message == SettingsMessage.Deleted }

        assertEquals(0, scalar("SELECT COUNT(*) FROM expense"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM income_entry"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM budget"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM rollup_expense_month"))
        assertEquals("the three seeded roots", 3, scalar("SELECT COUNT(*) FROM category WHERE parent_id IS NULL"))
        assertEquals("the thirteen seeded leaves", 13, scalar("SELECT COUNT(*) FROM category WHERE parent_id IS NOT NULL"))
        assertEquals("and the seeded source", 1, scalar("SELECT COUNT(*) FROM income_source"))
    }

    @Test
    fun an_expense_can_be_recorded_immediately_after_deleting_everything() = runBlocking {
        seed()
        val vm = vm()
        vm.openDeleteAll()
        vm.onDeleteTyped("DELETE")
        vm.confirmDeleteAll()
        vm.state.awaitState { !it.busy && it.message == SettingsMessage.Deleted }

        val outcome = fx.expenses.insert(
            Money.ofTaka(100),
            fx.leafId("Grocery"),
            LocalDate.of(2026, 8, 14),
        )
        assertTrue("the app still works: $outcome", outcome is com.app.finance.domain.model.SaveOutcome.Saved)
    }

    // --- 04 §7's theme --------------------------------------------------------

    @Test
    fun the_theme_defaults_to_following_the_phone() = runBlocking {
        // A phone already set to dark has answered the question; asking again
        // by ignoring it is a bug, not a design decision.
        assertEquals(ThemeChoice.SYSTEM, vm().state.awaitState { true }.theme)
    }

    @Test
    fun the_theme_persists() = runBlocking {
        val vm = vm()
        vm.setTheme(ThemeChoice.DARK)
        assertEquals(ThemeChoice.DARK, vm.state.awaitState { it.theme == ThemeChoice.DARK }.theme)
        // Read back through a second ViewModel, which is what a relaunch is.
        assertEquals(ThemeChoice.DARK, vm().state.awaitState { it.theme == ThemeChoice.DARK }.theme)
    }

    // --- NFR-SEC-04 and FR-APP-04 (§20.3) ------------------------------------

    @Test
    fun both_privacy_settings_are_off_until_they_are_asked_for() = runBlocking {
        // Both requirements say "optional" in as many words, and `FLAG_SECURE`
        // in particular breaks the user's screenshots the moment it is on. A
        // privacy control nobody switched on is a surprise, not a feature.
        val state = vm().state.awaitState { true }
        assertFalse(state.secureScreen)
        assertFalse(state.appLock)
    }

    @Test
    fun hiding_from_screenshots_survives_a_new_view_model() = runBlocking {
        vm().setSecureScreen(true)
        assertTrue(vm().state.awaitState { it.secureScreen }.secureScreen)
        assertTrue("and the repository is where it lives", fx.settings.observeSecureScreen().first())
    }

    @Test
    fun requiring_unlock_survives_a_new_view_model() = runBlocking {
        vm().setAppLock(true)
        assertTrue(vm().state.awaitState { it.appLock }.appLock)

        vm().setAppLock(false)
        assertFalse(vm().state.awaitState { !it.appLock }.appLock)
    }
}
