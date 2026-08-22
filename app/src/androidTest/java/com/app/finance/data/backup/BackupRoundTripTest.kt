package com.app.finance.data.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.ImportMode
import com.app.finance.data.repo.BackupOutcome
import com.app.finance.data.repo.BackupRepository
import com.app.finance.data.repo.RestoreOutcome
import com.app.finance.dev.SeedFiveYears
import com.app.finance.domain.model.BackupInterval
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
import com.app.finance.ui.feature.dashboard.DashboardUiState
import com.app.finance.ui.feature.dashboard.DashboardViewModel
import com.app.finance.ui.feature.income.IncomeUiState
import com.app.finance.ui.feature.income.IncomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * **The backup exit criterion.** The whole feature, end to end, against the
 * scenario it exists for: the phone is gone and everything must come back.
 *
 * `ExportImportRoundTripTest` already proves export → wipe → import is lossless.
 * This is not that test again through a different door. Two things here are
 * genuinely different, and each has been a real defect somewhere:
 *
 * 1. **The database is re-seeded before the restore, not merely emptied.**
 *    `deleteAllData` is what a fresh install looks like, and `Schema.SEED`
 *    builds its UUIDs from `randomblob` — so the categories waiting for the
 *    backup have *different* UUIDs from the ones inside it. That is precisely
 *    the case `06 §18.1` found broken, and the case every reinstall is:
 *    > "Merging a file into the database it came from worked, which is why
 *    > `ExportImportRoundTripTest` passed and the defect survived: the only
 *    > merge anyone had tested was the one that never leaves a phone."
 *
 * 2. **The file is compressed and encrypted**, and travels through the folder
 *    rather than a `ByteArrayOutputStream`. If any of the framing, the gzip or
 *    the key handling were wrong, five years would decode to something the
 *    importer refuses — and the user would find out at the only moment it
 *    cannot be fixed.
 *
 * FR-DAT-04's acceptance is the measure, both halves of it: row counts and
 * checksums, **and** every rendered figure, because the rollups are rebuilt from
 * the ledger rather than carried in the file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private lateinit var fx: TestFixture
    private var store = FakeBackupStore()
    private lateinit var backup: BackupRepository
    private val vmStore = ViewModelStore()
    private var seq = 0

    private val aug = Period(202608)
    private val pass = "shob kichu fire ashuk".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fx = TestFixture()
        store = FakeBackupStore()
        backup = BackupRepository(
            db = fx.db,
            exporter = fx.exporter,
            importer = fx.importer,
            settings = fx.settings,
            clock = fx.clock,
            storeFor = { store },
        )
    }

    @After
    fun tearDown() {
        vmStore.clear()
        fx.closeAfterDraining()
        Dispatchers.resetMain()
    }

    // --- the criterion --------------------------------------------------------

    @Test
    fun an_encrypted_backup_survives_a_reinstall_intact() = runBlocking {
        SeedFiveYears.into(fx.db, aug)
        // The one table nothing else here writes. An export that dropped it
        // would still pass every other assertion below.
        fx.recurring.createRule(
            target = RuleTarget.EXPENSE,
            targetId = fx.leafId("House Rent"),
            amount = Money.ofTaka(15_000),
            frequency = Frequency.MONTHLY,
            anchorDay = 31,
        )

        val before = ledgerFingerprint()
        val beforeDashboard = settledDashboard().figures()
        val beforeIncome = settledIncome().figures()
        val seededUuids = categoryUuids()

        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        fx.settings.setBackupFolder(TREE)
        fx.settings.setBackupInterval(BackupInterval.DAILY)

        val written = backup.runIfDue()
        assertTrue("backup failed: $written", written is BackupOutcome.Done)
        val file = store.bytesOf(store.names.single())!!

        // The phone is gone. This is what the new one looks like: not empty --
        // seeded, with categories of its own that share nothing with the file
        // but their names.
        fx.settings.deleteAllData()
        assertEquals("the ledger really went", "0:0", row("SELECT COUNT(*), 0 FROM expense"))
        assertNotEquals("a fresh install re-seeds new UUIDs", seededUuids, categoryUuids())

        val restored = backup.restore({ ByteArrayInputStream(file) }, pass, ImportMode.REPLACE)
        assertTrue("restore failed: $restored", restored is RestoreOutcome.Done)

        // FR-DAT-04's first half.
        assertEquals("row counts and checksums", before, ledgerFingerprint())

        // Its second half, which the first does not imply: the rollups were
        // rebuilt from the ledger, not carried in the file.
        assertEquals("every dashboard figure", beforeDashboard, settledDashboard().figures())
        assertEquals("every income figure", beforeIncome, settledIncome().figures())
    }

    @Test
    fun a_restored_phone_knows_it_is_not_protected_yet() = runBlocking {
        // The hole this test was written to find, and it is real.
        //
        // The file carries the user's schedule -- `backup_interval` is not
        // transient -- but it cannot carry the folder grant, which names a
        // permission the *old* phone held. So a reinstall restores five years of
        // ledger onto a phone with nowhere to back it up, and a dashboard that
        // looks entirely healthy.
        //
        // What must not happen is the app believing it is armed. `isArmed`
        // wants both halves, `runIfDue` writes nothing, and `WelcomeScreen`
        // asks for a folder on the strength of exactly this state.
        SeedFiveYears.into(fx.db, aug)
        fx.settings.setBackupFolder(TREE)
        fx.settings.setBackupInterval(BackupInterval.DAILY)
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        // A new phone: seeded, and knowing nothing about the old one.
        fx.settings.deleteAllData()
        backup.restore({ ByteArrayInputStream(file) }, pass, ImportMode.REPLACE)

        val after = fx.settings.backupSettings()
        assertEquals("the schedule travelled in the file", BackupInterval.DAILY, after.interval)
        assertNull("the folder grant could not, and must not appear to have", after.treeUri)
        assertFalse("so the app is not armed", after.isArmed)
        assertFalse("and has no key it never derived", after.encrypted)
        assertEquals("and writes nothing until a folder is chosen", BackupOutcome.Skipped, backup.runIfDue())
    }

    @Test
    fun choosing_a_folder_after_a_restore_arms_it_again() = runBlocking {
        // The other half: once WelcomeScreen has asked and been answered, the
        // restored phone protects itself without anything else being set up.
        SeedFiveYears.into(fx.db, aug)
        fx.settings.setBackupFolder(TREE)
        fx.settings.setBackupInterval(BackupInterval.DAILY)
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        fx.settings.deleteAllData()
        backup.restore({ ByteArrayInputStream(file) }, null, ImportMode.REPLACE)
        store = FakeBackupStore()

        fx.settings.setBackupFolder(TREE)

        assertTrue(backup.runIfDue() is BackupOutcome.Done)
        assertEquals(1, store.names.size)
    }

    @Test
    fun a_plain_backup_survives_a_reinstall_too() = runBlocking {
        // Encryption is optional by requirement, so the unencrypted path is not
        // a lesser case -- it is the default one.
        SeedFiveYears.into(fx.db, aug)
        val before = ledgerFingerprint()

        fx.settings.setBackupFolder(TREE)
        fx.settings.setBackupInterval(BackupInterval.DAILY)
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        fx.settings.deleteAllData()
        val restored = backup.restore({ ByteArrayInputStream(file) }, null, ImportMode.REPLACE)

        assertTrue("restore failed: $restored", restored is RestoreOutcome.Done)
        assertEquals(before, ledgerFingerprint())
    }

    @Test
    fun five_years_compresses_to_something_worth_keeping_several_of() = runBlocking {
        // FR-DAT-09 keeps generations of this on a phone. 03 §9 measures the
        // database itself at 5.41 MB, and NFR-SIZE-05 caps it at 6 MB; a backup
        // that stayed anywhere near that would make retention a real cost.
        SeedFiveYears.into(fx.db, aug)
        fx.settings.setBackupFolder(TREE)
        fx.settings.setBackupInterval(BackupInterval.DAILY)
        backup.runIfDue()

        val bytes = store.bytesOf(store.names.single())!!.size
        assertTrue("five years came to $bytes bytes", bytes < 2_000_000)
    }

    // --- helpers --------------------------------------------------------------

    /**
     * The ledger tables, and deliberately **not** `app_meta`.
     *
     * `app_meta` is the one table where a restore is *supposed* to differ: the
     * folder grant and the key are re-applied from this phone rather than the
     * file, and the record of what the old phone had already written is cleared
     * so the new one backs itself up. `the_restored_phone_can_back_itself_up_again`
     * asserts that difference directly instead of hiding it in a checksum.
     */
    private fun ledgerFingerprint(): Map<String, String> = mapOf(
        "category" to row("SELECT COUNT(*), IFNULL(SUM(id + nature + is_archived), 0) FROM category"),
        "income_source" to row("SELECT COUNT(*), IFNULL(SUM(id + kind + is_archived), 0) FROM income_source"),
        "budget" to row("SELECT COUNT(*), IFNULL(SUM(id + category_id + period_ym + limit_minor), 0) FROM budget"),
        "expense" to row(
            "SELECT COUNT(*), IFNULL(SUM(id + category_id + amount_minor + spent_on + period_ym + status), 0) FROM expense",
        ),
        "income_entry" to row(
            "SELECT COUNT(*), IFNULL(SUM(id + source_id + amount_minor + earned_on + period_ym + status), 0) FROM income_entry",
        ),
        "recurring_rule" to row(
            "SELECT COUNT(*), IFNULL(SUM(id + amount_minor + frequency + anchor_day + next_due_day), 0) FROM recurring_rule",
        ),
        "rollup_expense_month" to row(
            "SELECT COUNT(*), IFNULL(SUM(period_ym + category_id + total_minor + txn_count), 0) FROM rollup_expense_month",
        ),
        "rollup_income_month" to row(
            "SELECT COUNT(*), IFNULL(SUM(period_ym + source_id + total_minor + entry_count), 0) FROM rollup_income_month",
        ),
    )

    private fun row(sql: String): String =
        fx.db.openHelper.writableDatabase.query(sql).use {
            if (it.moveToFirst()) "${it.getLong(0)}:${it.getLong(1)}" else "0:0"
        }

    private suspend fun categoryUuids(): List<String> =
        fx.db.backupDao().allCategories().map { it.uuid }.sorted()

    private fun dashboard(): DashboardViewModel = ViewModelProvider(
        vmStore,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DashboardViewModel(fx.dashboard, fx.categories, fx.clock, aug) as T
        },
    )["dash${seq++}", DashboardViewModel::class.java]

    private fun income(): IncomeViewModel = ViewModelProvider(
        vmStore,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                IncomeViewModel(fx.income, fx.clock, aug) as T
        },
    )["inc${seq++}", IncomeViewModel::class.java]

    private suspend fun settledDashboard(): DashboardUiState =
        dashboard().state.awaitState(timeoutMillis = 30_000) {
            !it.initialLoad && !it.net.expenses.isZero
        }

    private suspend fun settledIncome(): IncomeUiState =
        income().state.awaitState(timeoutMillis = 30_000) {
            !it.initialLoad && !it.summary.total.isZero
        }

    private fun DashboardUiState.figures() = listOf(
        "safe" to safeToSpend?.remaining?.paisa,
        "perDay" to safeToSpend?.perDay?.paisa,
        "income" to net.income.paisa,
        "expenses" to net.expenses.paisa,
        "savings" to net.savingsRate?.toLong(),
        "coverage" to coverage?.toLong(),
        "average" to averageIncome.paisa,
        "ribbon" to ribbon.dailyTotals.sum(),
        "groups" to groups.sumOf { it.spent.paisa },
        "alerts" to alerts.size.toLong(),
        "mix" to mix.sumOf { it.total.paisa },
        "deltas" to deltas.sumOf { it.increase.paisa },
        "largest" to largest.sumOf { it.expense.amountMinor },
        "trend" to (trend?.spend?.sum() ?: 0L),
        "reference" to (trend?.reference?.sum() ?: 0L),
    )

    private fun IncomeUiState.figures() = listOf(
        "total" to summary.total.paisa,
        "stable" to summary.stableTotal.paisa,
        "shares" to summary.shares.sumOf { it.total.paisa },
        "percent" to summary.shares.sumOf { it.share }.toLong(),
        "trend" to summary.trend.sum(),
        "entries" to entries.sumOf { it.entry.amountMinor },
    )

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
    }
}
