package com.app.finance.data.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.Schema
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * What an import refuses, and what it leaves behind when it does — FR-DAT-05
 * and NFR-REL-04.
 *
 * > "A failed import MUST leave the existing database unmodified — the
 * > operation is transactional."
 *
 * Every test here asserts the same thing twice: the failure is reported, **and**
 * the database is byte-for-byte what it was. A half-applied import of somebody's
 * financial history is the worst outcome this app has, worse than refusing.
 */
@RunWith(AndroidJUnit4::class)
class ImportValidationTest {

    private lateinit var fx: TestFixture
    private val aug = Period(202608)

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.closeAfterDraining()

    private fun seedSomething() = runBlocking {
        fx.expenses.insert(Money.ofTaka(340), fx.leafId("Grocery"), LocalDate.of(2026, 8, 3))
        fx.income.saveEntry(Money.ofTaka(30_000), "Salary", LocalDate.of(2026, 8, 1))
        fx.budgets.setLimit(fx.leafId("Grocery"), aug, Money.ofTaka(18_000))
    }

    private fun row(sql: String): String =
        fx.db.openHelper.writableDatabase.query(sql).use {
            if (it.moveToFirst()) "${it.getLong(0)}:${it.getLong(1)}" else "0:0"
        }

    /** Enough of the database to notice any write at all. */
    private fun snapshot(): List<String> = listOf(
        "SELECT COUNT(*), IFNULL(SUM(id + amount_minor + spent_on + status), 0) FROM expense",
        "SELECT COUNT(*), IFNULL(SUM(id + amount_minor + earned_on + status), 0) FROM income_entry",
        "SELECT COUNT(*), IFNULL(SUM(id + limit_minor + period_ym), 0) FROM budget",
        "SELECT COUNT(*), IFNULL(SUM(id + nature), 0) FROM category",
        "SELECT COUNT(*), IFNULL(SUM(id + kind), 0) FROM income_source",
        "SELECT COUNT(*), IFNULL(SUM(period_ym + total_minor + txn_count), 0) FROM rollup_expense_month",
        "SELECT COUNT(*), IFNULL(SUM(period_ym + total_minor + entry_count), 0) FROM rollup_income_month",
    ).map(::row)

    private suspend fun exportBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        fx.exporter.writeJson(out, fx.clock.millis())
        return out.toByteArray()
    }

    private suspend fun importText(text: String, mode: ImportMode = ImportMode.REPLACE) =
        fx.importer.import(ByteArrayInputStream(text.toByteArray()), mode)

    /**
     * A `categories` entry for a leaf that already exists here, carrying that
     * row's own uuid.
     *
     * Several tests below used to paste a local id straight into the JSON and
     * declare no categories at all. That is not a file the app can produce or
     * accept: the importer resolves every foreign key **through uuid, never
     * through the file's integer id** (`Importer` §"Foreign keys"), because
     * the same integer means a different category on a different phone —
     * exactly the silent mis-filing the uuid indirection exists to prevent.
     * So the importer was right to answer `DANGLING_REFERENCE`, and the tests
     * were asking it to do the dangerous thing.
     */
    private fun categoryJson(id: Long): String =
        fx.db.openHelper.writableDatabase.query(
            "SELECT uuid, parent_id, name, name_key, nature FROM category WHERE id = $id",
        ).use {
            check(it.moveToFirst()) { "no category $id" }
            // `null`, not 0, when the row is a root: `getLong` on a NULL
            // column returns 0, and a root that claims parent 0 is parsed as a
            // child of a category that does not exist.
            val parent = if (it.isNull(1)) "null" else it.getLong(1).toString()
            """{"id":$id,"uuid":"${it.getString(0)}","parent_id":$parent,""" +
                """"name":"${it.getString(2)}","name_key":"${it.getString(3)}",""" +
                """"nature":${it.getInt(4)},"created_at":1,"updated_at":1}"""
        }

    /**
     * The leaf **and the root above it**, which is what makes the file
     * self-contained.
     *
     * A leaf alone is still dangling: `merge` remaps every child's `parent_id`
     * through the same id map, so declaring the leaf and not its parent asks
     * the importer to resolve a root the file never mentioned. That is the
     * importer being right again — a category tree that arrives without its
     * own parents is not a tree.
     */
    private fun categoriesFor(leafId: Long): String {
        val parent = fx.db.openHelper.writableDatabase
            .query("SELECT parent_id FROM category WHERE id = $leafId")
            .use { check(it.moveToFirst()) { "no category $leafId" }; it.getLong(0) }
        return categoryJson(parent) + "," + categoryJson(leafId)
    }

    /** The same, for an income source. */
    private fun sourceJson(id: Long): String =
        fx.db.openHelper.writableDatabase.query(
            "SELECT uuid, name, name_key, kind FROM income_source WHERE id = $id",
        ).use {
            check(it.moveToFirst()) { "no source $id" }
            """{"id":$id,"uuid":"${it.getString(0)}","name":"${it.getString(1)}",""" +
                """"name_key":"${it.getString(2)}","kind":${it.getInt(3)},""" +
                """"created_at":1,"updated_at":1}"""
        }

    // --- FR-DAT-05 ------------------------------------------------------------

    @Test
    fun a_file_from_a_newer_schema_is_refused() = runBlocking {
        // 05 §9 already writes the sentence the user reads: "That file is from a
        // newer version of the app. Update, then import again."
        seedSomething()
        val before = snapshot()

        val outcome = importText(
            """{"schema_version":${Schema.VERSION + 1},"exported_at":1,"expenses":[]}""",
        )

        assertEquals(ImportOutcome.Failure.NEWER_SCHEMA, outcome)
        assertEquals("nothing was touched", before, snapshot())
    }

    @Test
    fun a_file_from_an_older_schema_is_accepted() = runBlocking {
        // Only *newer* is refused. Refusing older would mean every release
        // broke the backups taken by the one before it, which is the opposite
        // of what a backup is for.
        seedSomething()
        val file = exportBytes().decodeToString().replace(
            "\"schema_version\":${Schema.VERSION}",
            "\"schema_version\":0",
        )
        assertTrue(importText(file) is ImportOutcome.Done)
    }

    // --- NFR-REL-04 -----------------------------------------------------------

    @Test
    fun a_file_that_is_not_an_export_changes_nothing() = runBlocking {
        seedSomething()
        val before = snapshot()

        assertEquals(ImportOutcome.Failure.UNREADABLE, importText("""{"hello":"world"}"""))
        assertEquals(before, snapshot())
    }

    @Test
    fun a_truncated_file_changes_nothing() = runBlocking {
        seedSomething()
        val before = snapshot()

        val truncated = exportBytes().decodeToString().dropLast(40)
        assertEquals(ImportOutcome.Failure.UNREADABLE, importText(truncated))
        assertEquals(before, snapshot())
    }

    @Test
    fun a_file_referring_to_a_category_it_does_not_contain_changes_nothing() = runBlocking {
        // The merge path's dangling-reference guard. Without it the expense
        // would be written against whatever local row happens to hold that
        // integer — a corruption with no error and nothing for the user to see.
        seedSomething()
        val before = snapshot()

        val outcome = importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "expenses":[{"id":1,"uuid":"orphan","category_id":9999,"amount_minor":100,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        )

        assertEquals(ImportOutcome.Failure.DANGLING_REFERENCE, outcome)
        assertEquals(before, snapshot())
    }

    @Test
    fun a_row_violating_a_check_constraint_rolls_the_whole_import_back() = runBlocking {
        // `amount_minor <> 0` on expense. One bad row must not leave the other
        // nine thousand half-written.
        seedSomething()
        val before = snapshot()

        val grocery = fx.leafId("Grocery")
        val outcome = importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "categories":[${categoriesFor(grocery)}],
             "expenses":[{"id":1,"uuid":"a","category_id":$grocery,"amount_minor":500,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1},
                         {"id":2,"uuid":"b","category_id":$grocery,"amount_minor":0,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        )

        assertEquals(ImportOutcome.Failure.REJECTED, outcome)
        assertEquals("neither row was written", before, snapshot())
    }

    // --- FR-DAT-03's counts ---------------------------------------------------

    @Test
    fun a_merge_reports_what_it_inserted_updated_and_skipped() = runBlocking {
        seedSomething()
        val grocery = fx.leafId("Grocery")

        val outcome = importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "categories":[${categoriesFor(grocery)}],
             "expenses":[{"id":900,"uuid":"brand-new","category_id":$grocery,"amount_minor":700,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        ) as ImportOutcome.Done

        assertEquals(1, outcome.perEntity["expenses"]?.inserted)
        assertEquals(0, outcome.perEntity["expenses"]?.updated)
        assertEquals(0, outcome.perEntity["expenses"]?.skipped)
    }

    @Test
    fun a_merged_insert_gets_a_local_id_rather_than_the_files_one() = runBlocking {
        // The file's integer key belongs to another device. Reusing it is how
        // two rows end up fighting over one id — and here it would collide with
        // nothing, which is exactly why the bug would go unnoticed until it did.
        seedSomething()
        val grocery = fx.leafId("Grocery")

        importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "categories":[${categoriesFor(grocery)}],
             "expenses":[{"id":900,"uuid":"brand-new","category_id":$grocery,"amount_minor":700,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        )

        val id = row("SELECT id, 0 FROM expense WHERE uuid = 'brand-new'").substringBefore(':')
        assertTrue("expected a locally assigned id, got $id", id.toLong() < 900)
    }

    @Test
    fun a_merge_remaps_a_foreign_key_through_the_uuid_not_the_integer() = runBlocking {
        // The defect this whole design exists to prevent. The file says
        // category_id = 1; locally, uuid `cat-grocery` is a different integer.
        // Trusting the integer would file the expense under the wrong category
        // with no error at all.
        seedSomething()
        val grocery = fx.leafId("Grocery")
        val groceryUuid = row("SELECT 0, 0 FROM category").let {
            fx.db.openHelper.writableDatabase
                .query("SELECT uuid FROM category WHERE id = $grocery")
                .use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        }
        val wrongId = grocery + 5

        importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "categories":[{"id":$wrongId,"uuid":"$groceryUuid","parent_id":null,
                            "name":"Grocery","name_key":"grocery","nature":1,
                            "created_at":1,"updated_at":1}],
             "expenses":[{"id":1,"uuid":"remapped","category_id":$wrongId,"amount_minor":700,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        )

        val landed = row("SELECT category_id, 0 FROM expense WHERE uuid = 'remapped'")
            .substringBefore(':').toLong()
        assertEquals("the expense followed the uuid, not the integer", grocery, landed)
    }

    // --- the rollups are rebuilt, not restored --------------------------------

    @Test
    fun the_rollups_match_the_ledger_after_an_import() = runBlocking {
        // They are absent from the file on purpose (03 §6 makes triggers their
        // only writer), so this is the assertion that the rebuild ran.
        seedSomething()
        val file = exportBytes()
        fx.db.openHelper.writableDatabase.execSQL("DELETE FROM rollup_expense_month")

        importText(file.decodeToString(), ImportMode.MERGE)

        assertEquals(
            row("SELECT COUNT(*), IFNULL(SUM(amount_minor), 0) FROM expense WHERE status = 0"),
            row("SELECT IFNULL(SUM(txn_count), 0), IFNULL(SUM(total_minor), 0) FROM rollup_expense_month"),
        )
    }

    // --- A1: the case merge exists for ---------------------------------------

    /**
     * A backup from a *second phone*: same category and source names, different
     * UUIDs, because every seeded row's uuid comes from `randomblob` at install.
     *
     * This is the ordinary cross-device merge, not an exotic one, and before the
     * audit it failed outright — `plan` saw an unfamiliar uuid, inserted, and
     * `ux_category_parent_key` took the whole import down with it.
     */
    private fun otherPhoneExport(
        expenses: String = "",
        budgets: String = "",
        income: String = "",
    ) = """
        {"schema_version":${Schema.VERSION},"exported_at":1,
         "categories":[
           {"id":10,"uuid":"other-root","parent_id":null,"name":"Variable Expenses",
            "name_key":"variable expenses","nature":1,"is_system":true,
            "created_at":1,"updated_at":1},
           {"id":11,"uuid":"other-grocery","parent_id":10,"name":"Grocery",
            "name_key":"grocery","nature":1,"created_at":1,"updated_at":1}],
         "sources":[
           {"id":20,"uuid":"other-salary","name":"Salary","name_key":"salary",
            "kind":0,"created_at":1,"updated_at":1}]
         $expenses $budgets $income}
    """.trimIndent()

    @Test
    fun a_backup_from_another_phone_merges_instead_of_being_rejected() = runBlocking {
        seedSomething()
        val outcome = importText(otherPhoneExport(), ImportMode.MERGE)

        assertTrue("expected a merge, got $outcome", outcome is ImportOutcome.Done)
        assertEquals(
            "no second Grocery",
            1L,
            scalar("SELECT COUNT(*) FROM category WHERE name_key = 'grocery'"),
        )
        assertEquals(
            "and no second Salary",
            1L,
            scalar("SELECT COUNT(*) FROM income_source WHERE name_key = 'salary'"),
        )
    }

    @Test
    fun a_name_matched_row_is_counted_as_skipped_and_left_alone() = runBlocking {
        // The local row is the identity: its sort_order, icon and is_system are
        // facts about *this* phone, and is_system in particular is not something
        // a file should be able to set on a seeded root.
        seedSomething()
        val before = scalar("SELECT sort_order FROM category WHERE name_key = 'grocery'")

        val outcome = importText(otherPhoneExport(), ImportMode.MERGE) as ImportOutcome.Done

        assertEquals(0, outcome.perEntity["categories"]?.inserted)
        assertEquals(0, outcome.perEntity["categories"]?.updated)
        assertEquals(2, outcome.perEntity["categories"]?.skipped)
        assertEquals(before, scalar("SELECT sort_order FROM category WHERE name_key = 'grocery'"))
    }

    @Test
    fun another_phones_expense_lands_on_this_phones_category() = runBlocking {
        // The defect the whole design exists to prevent, from the direction it
        // actually arrives: the file says category_id = 11, which on this phone
        // is some other category entirely.
        seedSomething()
        val grocery = fx.leafId("Grocery")

        importText(
            otherPhoneExport(
                expenses = ""","expenses":[{"id":50,"uuid":"other-expense","category_id":11,
                    "amount_minor":9900,"spent_on":20678,"period_ym":202608,
                    "created_at":1,"updated_at":1}]""",
            ),
            ImportMode.MERGE,
        )

        assertEquals(
            grocery,
            scalar("SELECT category_id FROM expense WHERE uuid = 'other-expense'"),
        )
    }

    @Test
    fun another_phones_income_lands_on_this_phones_source() = runBlocking {
        seedSomething()
        val salary = scalar("SELECT id FROM income_source WHERE name_key = 'salary'")

        importText(
            otherPhoneExport(
                income = ""","income_entries":[{"id":60,"uuid":"other-income","source_id":20,
                    "amount_minor":500000,"earned_on":20666,"period_ym":202608,
                    "created_at":1,"updated_at":1}]""",
            ),
            ImportMode.MERGE,
        )

        assertEquals(salary, scalar("SELECT source_id FROM income_entry WHERE uuid = 'other-income'"))
    }

    @Test
    fun a_budget_for_a_period_this_phone_already_has_does_not_collide() = runBlocking {
        // `ux_budget_cat_period` is FR-BUD-02 at the storage layer — one limit
        // per (category, period) — so a second row for August Grocery is not a
        // merge conflict to resolve, it is a row that must not be written.
        seedSomething()
        val outcome = importText(
            otherPhoneExport(
                budgets = ""","budgets":[{"id":70,"uuid":"other-budget","category_id":11,
                    "period_ym":202608,"limit_minor":9900000,"created_at":1,"updated_at":1}]""",
            ),
            ImportMode.MERGE,
        )

        assertTrue("expected a merge, got $outcome", outcome is ImportOutcome.Done)
        assertEquals(
            1L,
            scalar("SELECT COUNT(*) FROM budget WHERE period_ym = 202608"),
        )
        assertEquals(
            "and this phone's limit stood",
            1_800_000L,
            scalar("SELECT limit_minor FROM budget WHERE period_ym = 202608"),
        )
    }

    @Test
    fun two_identical_expenses_on_one_day_stay_two_expenses() = runBlocking {
        // The other half of A1: where the schema has no unique index there is no
        // natural key, and inventing one would silently merge transactions the
        // user really made twice. FR-IE-02 says the same of income.
        seedSomething()
        val grocery = fx.leafId("Grocery")

        importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "categories":[${categoriesFor(grocery)}],
             "expenses":[{"id":1,"uuid":"twin-a","category_id":$grocery,"amount_minor":34500,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1},
                         {"id":2,"uuid":"twin-b","category_id":$grocery,"amount_minor":34500,
                          "spent_on":20678,"period_ym":202608,"created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        )

        // 34,500 rather than 34,000: `seedSomething` inserts ৳340, which *is*
        // 34,000 paisa, so the original figure counted the fixture's own row
        // and asserted two where a working merge gives three.
        assertEquals(2L, scalar("SELECT COUNT(*) FROM expense WHERE amount_minor = 34500"))
    }

    // --- A8 -------------------------------------------------------------------

    @Test
    fun an_older_backup_does_not_leave_the_database_claiming_its_version() = runBlocking {
        // `app_meta` carries `schema_version`, and the file's copy is the version
        // it was *written* at. Restoring a year-old backup must not leave a v1
        // database saying it is a v0 one.
        seedSomething()
        val file = exportBytes().decodeToString().replace(
            "\"schema_version\":${Schema.VERSION}",
            "\"schema_version\":0",
        )
        importText(file, ImportMode.REPLACE)

        assertEquals(
            Schema.VERSION.toString(),
            fx.db.appMetaDao().get("schema_version"),
        )
    }

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    // --- C3: the file supplies dates, the app supplies the derivation --------

    @Test
    fun a_file_whose_period_disagrees_with_its_date_is_corrected_on_import() = runBlocking {
        // 03 §4.3 — `period_ym` "is derived from `earned_on` by the
        // application". The importer is the one door into the database that
        // does not go through a repository, and it was trusting the file. A
        // hand-edited backup could file August's rent under June while the row
        // still read 3 August, and nothing in the app could see it: the rollups
        // would be built from the stated period and the drift check compares
        // against that same period.
        seedSomething()
        val grocery = fx.leafId("Grocery")
        val august = LocalDate.of(2026, 8, 3).toEpochDay()

        importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "categories":[${categoriesFor(grocery)}],
             "expenses":[{"id":1,"uuid":"mislabelled","category_id":$grocery,
                          "amount_minor":50000,"spent_on":$august,"period_ym":202606,
                          "created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        )

        assertEquals(
            "the date decides the period, not the file",
            202608L,
            scalar("SELECT period_ym FROM expense WHERE uuid = 'mislabelled'"),
        )
        assertEquals(
            "and the month total follows the date",
            0L,
            scalar("SELECT COUNT(*) FROM rollup_expense_month WHERE period_ym = 202606"),
        )
    }

    @Test
    fun an_income_entry_is_corrected_the_same_way() = runBlocking {
        seedSomething()
        val salary = scalar("SELECT id FROM income_source WHERE name_key = 'salary'")
        val august = LocalDate.of(2026, 8, 1).toEpochDay()

        importText(
            """
            {"schema_version":${Schema.VERSION},"exported_at":1,
             "sources":[${sourceJson(salary)}],
             "income_entries":[{"id":1,"uuid":"mislabelled","source_id":$salary,
                                "amount_minor":300000,"earned_on":$august,
                                "period_ym":202512,"created_at":1,"updated_at":1}]}
            """.trimIndent(),
            ImportMode.MERGE,
        )

        assertEquals(202608L, scalar("SELECT period_ym FROM income_entry WHERE uuid = 'mislabelled'"))
    }

    @Test
    fun a_correct_file_round_trips_with_its_periods_untouched() = runBlocking {
        // Deriving must not change anything about a file the app wrote itself.
        seedSomething()
        val before = row("SELECT COUNT(*), IFNULL(SUM(period_ym), 0) FROM expense")
        importText(exportBytes().decodeToString(), ImportMode.REPLACE)
        assertEquals(before, row("SELECT COUNT(*), IFNULL(SUM(period_ym), 0) FROM expense"))
    }
}
