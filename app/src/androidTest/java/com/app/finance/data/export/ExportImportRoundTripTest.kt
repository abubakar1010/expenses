package com.app.finance.data.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.time.Period
import com.app.finance.data.db.Schema
import com.app.finance.dev.SeedFiveYears
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.app.finance.core.money.Money
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.Split
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * **The M5 exit criterion.** `01-PRD.md` §8: *"Round-trip export→wipe→import
 * loses nothing."*
 *
 * FR-DAT-04's acceptance is exact about what "nothing" means:
 *
 * > "Row counts and checksums for every entity match pre-export values; **every
 * > report renders identical figures**."
 *
 * Two claims, and the second is not implied by the first. The rollups are
 * **not** in the file — they are derived, and the importer rebuilds them from
 * the ledger — so a restored database could carry every row correctly and still
 * render a different dashboard if the rebuild and the triggers disagreed. That
 * is why this suite drives `DashboardViewModel` and `IncomeViewModel` after the
 * import and compares what they show, figure by figure, with what they showed
 * before it.
 *
 * Five years of data, from the generator M4 built for its own criterion.
 * PRD §6.6 is the reason this matters more than its line in the milestone table
 * suggests: export is "the only backup mechanism in a no-server product", so a
 * round trip that loses a row loses it for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ExportImportRoundTripTest {

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

    // --- the fingerprint ------------------------------------------------------

    /**
     * Row counts and checksums per entity, which is what FR-DAT-04 names.
     *
     * The checksum sums the columns that carry meaning rather than hashing the
     * row: an id that changed would be a real difference for `replace`, and a
     * sum over amounts and dates catches the ways a restore actually goes wrong
     * — a dropped row, a re-filed category, a truncated paisa.
     */
    private fun fingerprint(): Map<String, String> = mapOf(
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
        // FR-SHR-07. Without these a round trip could drop every share and
        // still pass, which is the shape of gate that is not one.
        "person" to row("SELECT COUNT(*), IFNULL(SUM(id + sort_order + is_archived), 0) FROM person"),
        "expense_share" to row(
            "SELECT COUNT(*), IFNULL(SUM(id + expense_id + person_id + share_minor), 0) FROM expense_share",
        ),
        "settlement" to row(
            "SELECT COUNT(*), IFNULL(SUM(id + person_id + amount_minor + settled_on), 0) FROM settlement",
        ),
        "app_meta" to row("SELECT COUNT(*), 0 FROM app_meta"),
        // Derived, and rebuilt rather than restored — which is exactly why it
        // is worth fingerprinting too.
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

    private suspend fun exportJson(): ByteArray {
        val out = ByteArrayOutputStream()
        fx.exporter.writeJson(out, exportedAt = fx.clock.millis())
        return out.toByteArray()
    }

    private suspend fun importJson(bytes: ByteArray, mode: ImportMode): ImportOutcome =
        fx.importer.import(ByteArrayInputStream(bytes), mode)

    /**
     * [Schema.WIPE_ORDER], not a copy of it.
     *
     * This held its own nine-element list, which is the exact failure that
     * list's docstring warns about — "two copies of a nine-element ordering is
     * how a table added later gets cleared by one caller and left behind by
     * the other". Here it was worse than being left behind: the copy went on
     * deleting `category` in one statement after the real one had learned it
     * cannot, so the test kept failing on a bug that was already fixed.
     */
    private fun wipe() {
        val sql = fx.db.openHelper.writableDatabase
        Schema.WIPE_ORDER.forEach(sql::execSQL)
    }

    // --- the screens ----------------------------------------------------------

    private fun dashboard(): DashboardViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DashboardViewModel(fx.dashboard, fx.categories, fx.clock, aug) as T
        },
    )["dash${seq++}", DashboardViewModel::class.java]

    private fun income(): IncomeViewModel = ViewModelProvider(
        store,
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

    /** Everything the two screens render, as one comparable value. */
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

    // --- the criterion --------------------------------------------------------

    @Test
    fun export_wipe_import_loses_nothing() = runBlocking {
        SeedFiveYears.into(fx.db, aug)
        // A rule too: `recurring_rule` is the one table nothing else in the
        // suite writes, and an export that silently dropped it would still pass
        // every other assertion here.
        fx.recurring.createRule(
            target = com.app.finance.domain.model.RuleTarget.EXPENSE,
            targetId = fx.leafId("House Rent"),
            amount = com.app.finance.core.money.Money.ofTaka(15_000),
            frequency = com.app.finance.domain.model.Frequency.MONTHLY,
            anchorDay = 31,
        )

        val before = fingerprint()
        val beforeDashboard = settledDashboard().figures()
        val beforeIncome = settledIncome().figures()

        val file = exportJson()
        wipe()
        assertEquals("the wipe really wiped", "0:0", row("SELECT COUNT(*), 0 FROM expense"))

        val outcome = importJson(file, ImportMode.REPLACE)
        assertTrue("import failed: $outcome", outcome is ImportOutcome.Done)

        // FR-DAT-04's first half.
        assertEquals("row counts and checksums", before, fingerprint())

        // Its second half, which the first does not imply: the rollups were
        // rebuilt from the ledger rather than restored from the file.
        assertEquals("every dashboard figure", beforeDashboard, settledDashboard().figures())
        assertEquals("every income figure", beforeIncome, settledIncome().figures())
    }

    @Test
    fun the_file_holds_every_row_the_database_did() = runBlocking {
        SeedFiveYears.into(fx.db, aug)
        val parsed = fx.importer.parse(ByteArrayInputStream(exportJson())).getOrThrow()

        assertEquals(count("category"), parsed.categories.size.toLong())
        assertEquals(count("income_source"), parsed.sources.size.toLong())
        assertEquals(count("budget"), parsed.budgets.size.toLong())
        assertEquals(count("expense"), parsed.expenses.size.toLong())
        assertEquals(count("income_entry"), parsed.incomeEntries.size.toLong())
        assertEquals(count("person"), parsed.persons.size.toLong())
        assertEquals(count("expense_share"), parsed.shares.size.toLong())
        assertEquals(count("settlement"), parsed.settlements.size.toLong())
        assertEquals(count("app_meta"), parsed.meta.size.toLong())
    }

    @Test
    fun pending_entries_survive_the_round_trip() = runBlocking {
        // The rollups exclude `status = 1`, so a fingerprint over aggregates
        // alone would not notice them going missing — and they are data the
        // user can see on the ledger and would expect back.
        fx.recurring.createRule(
            target = com.app.finance.domain.model.RuleTarget.EXPENSE,
            targetId = fx.leafId("House Rent"),
            amount = com.app.finance.core.money.Money.ofTaka(15_000),
            frequency = com.app.finance.domain.model.Frequency.MONTHLY,
            anchorDay = 1,
            startingFrom = java.time.LocalDate.of(2026, 6, 1),
        )
        fx.recurring.evaluate(java.time.LocalDate.of(2026, 8, 14))
        val pendingBefore = count("expense WHERE status = 1")
        assertTrue("expected the rule to have generated", pendingBefore > 0)

        val file = exportJson()
        wipe()
        importJson(file, ImportMode.REPLACE)

        assertEquals(pendingBefore, count("expense WHERE status = 1"))
    }

    @Test
    fun merging_a_file_into_the_database_it_came_from_changes_nothing() = runBlocking {
        SeedFiveYears.into(fx.db, aug)
        val before = fingerprint()
        val beforeDashboard = settledDashboard().figures()

        val outcome = importJson(exportJson(), ImportMode.MERGE) as ImportOutcome.Done

        // FR-DAT-03's three counts, in the case that proves the de-duplication
        // works: every row matched on its UUID and was identical, so nothing
        // was written.
        assertEquals("nothing inserted", 0, outcome.totals.inserted)
        assertEquals("nothing updated", outcome.perEntity["meta"]?.updated, outcome.totals.updated)
        assertTrue("everything skipped", outcome.totals.skipped > 0)

        assertEquals(before, fingerprint())
        assertEquals(beforeDashboard, settledDashboard().figures())
    }

    @Test
    fun a_merge_that_adds_rows_reports_what_it_added() = runBlocking {
        SeedFiveYears.into(fx.db, aug)
        val file = exportJson()

        val added = fx.expenses.insert(
            amount = com.app.finance.core.money.Money.ofTaka(999),
            categoryId = fx.leafId("Grocery"),
            spentOn = java.time.LocalDate.of(2026, 8, 9),
        )
        assertTrue(added is com.app.finance.domain.model.SaveOutcome.Saved)
        val afterLocalEdit = count("expense")

        // The file predates the new expense, so merging it must leave the
        // expense alone — a merge adds, it does not reconcile absences.
        val outcome = importJson(file, ImportMode.MERGE) as ImportOutcome.Done
        assertEquals(0, outcome.totals.inserted)
        assertEquals(afterLocalEdit, count("expense"))
    }

    @Test
    fun the_csv_archive_carries_one_file_per_entity() = runBlocking {
        // FR-DAT-02 — "CSV, one file per entity, delivered as a single archive".
        SeedFiveYears.into(fx.db, aug)
        val out = ByteArrayOutputStream()
        fx.exporter.writeCsvArchive(out, fx.clock.millis())

        val names = mutableListOf<String>()
        var expenseLines = 0
        java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                if (entry.name == "expenses.csv") {
                    expenseLines = zip.readBytes().decodeToString().count { it == '\n' }
                }
                entry = zip.nextEntry
            }
        }

        assertEquals(
            listOf(
                "categories.csv", "sources.csv", "budgets.csv", "expenses.csv",
                "income_entries.csv", "recurring_rules.csv",
                "persons.csv", "shares.csv", "settlements.csv", "meta.csv",
            ),
            names,
        )
        // A header plus one line per row.
        assertEquals(count("expense") + 1, expenseLines.toLong())
    }

    private fun count(table: String): Long = row("SELECT COUNT(*), 0 FROM $table").substringBefore(':').toLong()

    // --- FR-SHR-07: shared expenses survive the round trip --------------------

    private suspend fun seedShared() {
        val rahim = (fx.people.findOrCreate("Rahim") as SaveOutcome.Saved).id
        val karim = (fx.people.findOrCreate("Karim") as SaveOutcome.Saved).id
        val grocery = fx.leafId("Grocery")

        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim, karim))
        fx.expenses.insert(yours, grocery, fx.today, split = split)
        fx.expenses.insert(
            Money.ofTaka(250), grocery, fx.today, split = Split.TheyPaid(rahim),
        )
        fx.settlements.record(rahim, Money.ofTaka(200), fx.today)
    }

    @Test
    fun a_shared_ledger_survives_export_wipe_and_import() = runBlocking {
        // FR-DAT-04 extended to FR-SHR-07: what comes back has to include who
        // owed what, or the balances are silently forgiven by a restore.
        seedShared()
        val before = fingerprint()
        val balanceBefore = fx.db.settlementDao().balanceOf(
            fx.db.personDao().byNameKey("rahim")!!.id,
        )

        val json = exportJson()
        wipe()
        val outcome = fx.importer.import(ByteArrayInputStream(json), ImportMode.REPLACE)

        assertTrue("import failed: $outcome", outcome is ImportOutcome.Done)
        assertEquals(before, fingerprint())
        assertEquals(
            balanceBefore,
            fx.db.settlementDao().balanceOf(fx.db.personDao().byNameKey("rahim")!!.id),
        )
    }

    @Test
    fun merging_a_shared_file_into_its_own_database_changes_nothing() = runBlocking {
        // The symmetry guard on `toDto`/`toEntity`. If `toDto` failed to carry
        // `payerPersonId`, `plan`'s skip test — data-class equality — would call
        // every payer-bearing expense changed and re-update it forever.
        seedShared()
        val json = exportJson()

        val outcome = fx.importer.import(ByteArrayInputStream(json), ImportMode.MERGE)

        assertTrue(outcome is ImportOutcome.Done)
        val done = outcome as ImportOutcome.Done
        assertEquals("a merge into itself inserted rows", 0, done.totals.inserted)
        assertEquals(
            "a merge into itself rewrote rows",
            done.perEntity["meta"]?.updated ?: 0,
            done.totals.updated,
        )
    }

    @Test
    fun a_file_written_before_shared_expenses_existed_still_imports() = runBlocking {
        // The existing older-schema test starts from a *new* export, so it does
        // not prove this: what matters is a file with no `persons`, `shares` or
        // `settlements` keys at all, and no `payer_person_id` on an expense.
        // Every array is defaulted and `explicitNulls = false` drops the null,
        // which is exactly what makes absence mean "you paid".
        val grocery = fx.leafId("Grocery")
        val v1 = """
            {"schema_version":1,"exported_at":1,
             "categories":[
               {"id":900,"uuid":"root-u","name":"Fixed Expenses","name_key":"fixed expenses",
                "nature":0,"created_at":1,"updated_at":1},
               {"id":901,"uuid":"leaf-u","parent_id":900,"name":"Rent","name_key":"rent",
                "nature":0,"created_at":1,"updated_at":1}],
             "expenses":[
               {"id":902,"uuid":"exp-u","category_id":901,"amount_minor":12345,
                "spent_on":${fx.today.toEpochDay()},"period_ym":202608,
                "created_at":1,"updated_at":1}]}
        """.trimIndent()

        val outcome = fx.importer.import(v1.byteInputStream(), ImportMode.MERGE)

        assertTrue("an old backup was refused: $outcome", outcome is ImportOutcome.Done)
        // Restored as an expense you paid, which is what every pre-feature
        // expense was.
        assertEquals(
            0L,
            row("SELECT COUNT(*), 0 FROM expense WHERE payer_person_id IS NOT NULL")
                .substringBefore(':').toLong(),
        )
        assertEquals(0L, count("person"))
        assertTrue(grocery > 0)
    }
}
