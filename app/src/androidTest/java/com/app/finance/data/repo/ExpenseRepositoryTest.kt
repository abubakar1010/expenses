package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.LedgerFilters
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hot write path — 04 §5.1 — plus the filtered reads FR-EXP-08 requires.
 *
 * NFR-MAIN-02 asks for ≥80% line coverage on the repository layer. Before this
 * pass the layer had none at all, including `insert()`, which every expense in
 * the product goes through.
 */
@RunWith(AndroidJUnit4::class)
class ExpenseRepositoryTest {

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.close()

    private suspend fun savedId(outcome: SaveOutcome): Long {
        assertTrue("expected a save, got $outcome", outcome is SaveOutcome.Saved)
        return (outcome as SaveOutcome.Saved).id
    }

    private fun rollup(period: Int, categoryId: Long): Long =
        fx.db.openHelper.writableDatabase
            .query("SELECT total_minor FROM rollup_expense_month WHERE period_ym=$period AND category_id=$categoryId")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    // --- insert -------------------------------------------------------------

    @Test
    fun insert_defaults_the_date_to_today_from_the_injected_clock() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val id = savedId(fx.expenses.insert(Money.ofTaka(250), grocery))

        val row = fx.expenses.byId(id)!!
        assertEquals(fx.today.toEpochDay(), row.spentOn)
        // FR-EXP-02's default, and the period derived from it rather than from
        // the caller — the whole monthly-query strategy rests on this column.
        assertEquals(202608, row.periodYm)
    }

    @Test
    fun insert_updates_the_rollup_in_the_same_transaction() = runBlocking {
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(250), grocery)
        fx.expenses.insert(Money.ofTaka(100), grocery)
        assertEquals(35_000L, rollup(202608, grocery))
    }

    @Test
    fun insert_rejects_zero_but_accepts_a_negative_refund() = runBlocking {
        val grocery = fx.leafId("Grocery")

        val zero = fx.expenses.insert(Money.ZERO, grocery)
        assertEquals(SaveOutcome.Rejected(EntryError.ZERO_AMOUNT), zero)

        // FR-EXP-06: "A −৳500 entry against Grocery reduces the Grocery period
        // total by 500."
        fx.expenses.insert(Money.ofTaka(800), grocery)
        fx.expenses.insert(Money.ofTaka(-500), grocery)
        assertEquals(30_000L, rollup(202608, grocery))
    }

    @Test
    fun insert_rejects_a_group_category() = runBlocking {
        // FR-EXP-04 — only leaves may carry an expense.
        val fixed = fx.rootId("Fixed Expenses")
        assertEquals(
            SaveOutcome.Rejected(EntryError.NOT_A_LEAF_CATEGORY),
            fx.expenses.insert(Money.ofTaka(100), fixed),
        )
    }

    @Test
    fun insert_rejects_an_archived_category() = runBlocking {
        // FR-CAT-08 — archived categories are hidden from entry.
        val grocery = fx.leafId("Grocery")
        fx.categories.archive(grocery)
        assertEquals(
            SaveOutcome.Rejected(EntryError.CATEGORY_ARCHIVED),
            fx.expenses.insert(Money.ofTaka(100), grocery),
        )
    }

    @Test
    fun insert_remembers_the_last_used_category_and_method() = runBlocking {
        // FR-EXP-02/03 — "defaults do the work", written in the same
        // transaction so they survive a kill immediately after the save.
        val transport = fx.leafId("Transport")
        fx.expenses.insert(Money.ofTaka(60), transport, method = PaymentMethod.BKASH)

        assertEquals(transport, fx.meta.lastCategoryId())
        assertEquals(PaymentMethod.BKASH, fx.meta.lastPaymentMethod())
        assertEquals(
            transport.toString(),
            fx.db.appMetaDao().get(AppMetaDao.KEY_RECENT_CATEGORIES),
        )
    }

    @Test
    fun recent_categories_are_most_recent_first_and_capped() = runBlocking {
        val names = listOf("Grocery", "Transport", "Dining Out", "Household", "Medical", "Gifts", "Repairs")
        names.forEach { fx.expenses.insert(Money.ofTaka(10), fx.leafId(it)) }

        val recent = fx.db.appMetaDao().get(AppMetaDao.KEY_RECENT_CATEGORIES)!!
            .split(',').map(String::toLong)

        assertEquals(AppMetaDao.RECENT_CATEGORY_LIMIT, recent.size)
        assertEquals(fx.leafId("Repairs"), recent.first())
    }

    // --- update (FR-EXP-07) --------------------------------------------------

    @Test
    fun update_moves_the_total_between_periods_and_categories() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val transport = fx.leafId("Transport")

        val id = savedId(fx.expenses.insert(Money.ofTaka(900), grocery))
        assertEquals(90_000L, rollup(202608, grocery))

        // "Editing a January expense's category updates January's rollups for
        // both the old and new category."
        val outcome = fx.expenses.update(
            id = id,
            amount = Money.ofTaka(900),
            categoryId = transport,
            spentOn = fx.today.minusMonths(2),
            method = PaymentMethod.CASH,
            note = null,
        )
        assertTrue(outcome is SaveOutcome.Saved)

        assertEquals("left the old bucket", 0L, rollup(202608, grocery))
        assertEquals("landed in the new one", 90_000L, rollup(202606, transport))
    }

    @Test
    fun update_rejects_zero_and_leaves_the_row_untouched() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val id = savedId(fx.expenses.insert(Money.ofTaka(300), grocery))

        val outcome = fx.expenses.update(
            id, Money.ZERO, grocery, fx.today, PaymentMethod.CASH, null,
        )
        assertEquals(SaveOutcome.Rejected(EntryError.ZERO_AMOUNT), outcome)
        assertEquals(30_000L, fx.expenses.byId(id)!!.amountMinor)
    }

    @Test
    fun update_trims_a_blank_note_to_null() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val id = savedId(fx.expenses.insert(Money.ofTaka(300), grocery, note = "  "))
        assertNull(fx.expenses.byId(id)!!.note)
    }

    // --- delete and undo (NFR-USE-03) ---------------------------------------

    @Test
    fun delete_then_restore_returns_the_rollup_to_where_it_was() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val id = savedId(fx.expenses.insert(Money.ofTaka(420), grocery))

        val removed = fx.expenses.delete(id)
        assertNotNull(removed)
        assertEquals(0L, rollup(202608, grocery))

        fx.expenses.restore(removed!!)
        assertEquals(42_000L, rollup(202608, grocery))
    }

    @Test
    fun restore_preserves_the_uuid_so_an_undo_is_not_a_new_entity() = runBlocking {
        // Every row carries a UUID for export dedup (03 §1). An undo must
        // restore the same record, not mint a second one.
        val grocery = fx.leafId("Grocery")
        val id = savedId(fx.expenses.insert(Money.ofTaka(75), grocery))
        val removed = fx.expenses.delete(id)!!
        val newId = fx.expenses.restore(removed)

        assertEquals(removed.expense.uuid, fx.expenses.byId(newId)!!.uuid)
    }

    // --- filtering and search (FR-EXP-08) -----------------------------------

    @Test
    fun filters_by_leaf_root_method_and_date_range() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val rent = fx.leafId("House Rent")
        fx.expenses.insert(Money.ofTaka(100), grocery, method = PaymentMethod.CASH)
        fx.expenses.insert(Money.ofTaka(200), rent, method = PaymentMethod.BANK)
        fx.expenses.insert(
            Money.ofTaka(300), grocery,
            spentOn = fx.today.minusDays(40), method = PaymentMethod.NAGAD,
        )

        assertEquals(3, fx.expenses.filteredPage(LedgerFilters.NONE).size)

        assertEquals(2, fx.expenses.filteredPage(LedgerFilters(leafId = grocery)).size)

        // A root selects every leaf beneath it.
        assertEquals(
            1,
            fx.expenses.filteredPage(LedgerFilters(rootId = fx.rootId("Fixed Expenses"))).size,
        )

        assertEquals(
            1,
            fx.expenses.filteredPage(LedgerFilters(method = PaymentMethod.BANK)).size,
        )

        assertEquals(
            2,
            fx.expenses.filteredPage(LedgerFilters(from = fx.today.minusDays(7))).size,
        )
    }

    @Test
    fun search_matches_a_note_substring_and_an_exact_amount_only() = runBlocking {
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(250), grocery, note = "weekly shop")
        fx.expenses.insert(Money.ofTaka(1250), grocery, note = "monthly stock-up")

        assertEquals(1, fx.expenses.filteredPage(LedgerFilters(query = "weekly")).size)

        // FR-EXP-08's asymmetry: "250" finds the ৳250 row exactly, and must not
        // also drag in ৳1,250 the way a substring match on the figure would.
        val byAmount = fx.expenses.filteredPage(LedgerFilters(query = "250"))
        assertEquals(1, byAmount.size)
        assertEquals(25_000L, byAmount.first().expense.amountMinor)
    }

    @Test
    fun a_root_whose_children_are_all_archived_matches_nothing() = runBlocking {
        // The sentinel path: an empty id list must not become `IN ()`.
        val fixed = fx.rootId("Fixed Expenses")
        fx.expenses.insert(Money.ofTaka(100), fx.leafId("Grocery"))
        val page = fx.expenses.filteredPage(LedgerFilters(rootId = fixed))
        assertTrue(page.isEmpty())
    }

    @Test
    fun keyset_paging_walks_the_whole_ledger_without_repeats() = runBlocking {
        val grocery = fx.leafId("Grocery")
        repeat(120) { i ->
            fx.expenses.insert(Money.ofTaka(10L + i), grocery, spentOn = fx.today.minusDays(i.toLong()))
        }

        val seen = mutableListOf<Long>()
        var page = fx.expenses.filteredPage(LedgerFilters.NONE)
        while (page.isNotEmpty()) {
            seen += page.map { it.expense.id }
            page = fx.expenses.filteredPage(LedgerFilters.NONE, after = page.last())
        }

        assertEquals(120, seen.size)
        assertEquals("no row may appear on two pages", 120, seen.toSet().size)
    }
    // --- rules that were only enforced above the repository -------------------

    @Test
    fun a_date_that_has_not_happened_yet_is_refused_here_and_not_only_in_the_sheet() = runBlocking {
        // `EntryError.FUTURE_DATE` calls this a data-integrity rule — "a future
        // one would post straight into the period rollup and inflate spending
        // that has not happened" — and the only thing enforcing it was
        // `QuickAddViewModel`. A rule enforced in one ViewModel is a rule the
        // next caller does not have.
        val outcome = fx.expenses.insert(
            amount = Money.ofTaka(500),
            categoryId = fx.leafId("Grocery"),
            spentOn = fx.today.plusDays(1),
        )

        assertEquals(SaveOutcome.Rejected(EntryError.FUTURE_DATE), outcome)
        assertTrue(
            "a refused entry must not reach the ledger",
            fx.expenses.filteredPage(LedgerFilters.NONE).isEmpty(),
        )
    }

    @Test
    fun today_itself_is_not_in_the_future() = runBlocking {
        // The boundary, because `isAfter` and `!isBefore` differ by exactly the
        // day every entry is made on.
        assertTrue(
            fx.expenses.insert(Money.ofTaka(500), fx.leafId("Grocery"), fx.today)
                is SaveOutcome.Saved,
        )
    }

    @Test
    fun a_percent_sign_in_a_search_matches_a_percent_sign() = runBlocking {
        // `%` and `_` are LIKE wildcards and the query went straight into the
        // pattern, so searching for "50%" returned every note containing "50"
        // followed by anything. Nobody reports that as a bug — they conclude
        // the search is unreliable and stop using it.
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(100), grocery, fx.today, note = "50% off")
        fx.expenses.insert(Money.ofTaka(200), grocery, fx.today, note = "50 taka bus")

        val hits = fx.expenses.filteredPage(LedgerFilters(query = "50%"))

        assertEquals(1, hits.size)
        assertEquals("50% off", hits.single().expense.note)
    }

    @Test
    fun an_underscore_in_a_search_matches_an_underscore() = runBlocking {
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(100), grocery, fx.today, note = "bus_fare")
        fx.expenses.insert(Money.ofTaka(200), grocery, fx.today, note = "busXfare")

        val hits = fx.expenses.filteredPage(LedgerFilters(query = "bus_fare"))

        assertEquals(1, hits.size)
        assertEquals("bus_fare", hits.single().expense.note)
    }

    @Test
    fun a_backslash_in_a_search_is_a_backslash() = runBlocking {
        // The escape character itself. Escaping the wildcards without escaping
        // this first would turn a user's backslash into an escape and swallow
        // the character after it.
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(100), grocery, fx.today, note = "a\\b")
        fx.expenses.insert(Money.ofTaka(200), grocery, fx.today, note = "ab")

        val hits = fx.expenses.filteredPage(LedgerFilters(query = "a\\b"))

        assertEquals(1, hits.size)
        assertEquals("a\\b", hits.single().expense.note)
    }

    // --- FR-EXP-11: the total, and the predicate it shares with the pages ----

    /** Walks every page of [filters], the way a user scrolling to the end would. */
    private suspend fun everyPage(filters: LedgerFilters): List<ExpenseWithCategory> = buildList {
        var cursor: ExpenseWithCategory? = null
        while (true) {
            val page = fx.expenses.filteredPage(filters, after = cursor)
            if (page.isEmpty()) break
            addAll(page)
            cursor = page.last()
        }
    }

    @Test
    fun the_total_agrees_with_the_sum_of_every_page() = runBlocking {
        // `page` and `filteredTotal` hand-copy one predicate, because Room needs
        // a literal @Query for each. The values they bind come from one place so
        // only the SQL text can drift — and this is what notices when it does.
        // §22.5 records the EXPLAIN test that hand-copied a query and had
        // already drifted without anything failing.
        val grocery = fx.leafId("Grocery")
        val transport = fx.leafId("Transport")
        repeat(70) { i ->
            fx.expenses.insert(
                amount = Money.ofTaka(10 + i.toLong()),
                categoryId = if (i % 5 == 0) transport else grocery,
                spentOn = fx.today.minusDays((i % 14).toLong()),
                note = if (i % 3 == 0) "rice" else null,
            )
        }

        // Each filter exercises a different arm of the shared predicate.
        val cases = listOf(
            LedgerFilters.NONE,
            LedgerFilters(leafId = grocery),
            LedgerFilters(from = fx.today.minusDays(6)),
            LedgerFilters(query = "rice"),
            LedgerFilters(leafId = grocery, from = fx.today.minusDays(6), query = "rice"),
        )

        for (filters in cases) {
            val pages = everyPage(filters)
            val total = fx.expenses.filteredTotal(filters)
            assertEquals(
                "row count disagrees for $filters",
                pages.size,
                total.txnCount,
            )
            assertEquals(
                "total disagrees for $filters",
                pages.sumOf { it.expense.amountMinor },
                total.totalMinor,
            )
        }
    }

    @Test
    fun the_total_of_a_filter_that_matches_nothing_is_zero_not_null() = runBlocking {
        // `SUM` over no rows is NULL in SQLite; the IFNULL is what keeps this a
        // Long rather than a crash on a search that found nothing.
        val total = fx.expenses.filteredTotal(LedgerFilters(query = "no such note"))
        assertEquals(0L, total.totalMinor)
        assertEquals(0, total.txnCount)
    }

    @Test
    fun a_pending_row_is_not_in_the_filtered_total() = runBlocking {
        // status = 1 is excluded from every rollup trigger and every aggregate
        // read in the app; this is an aggregate read.
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(100), grocery, fx.today)

        val posted = fx.expenses.filteredTotal(LedgerFilters(leafId = grocery))
        assertEquals(Money.ofTaka(100).paisa, posted.totalMinor)
        assertEquals(1, posted.txnCount)

        fx.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO expense (uuid, category_id, amount_minor, spent_on, period_ym, " +
                "payment_method, status, created_at, updated_at) " +
                "VALUES ('pending-row', $grocery, 500000, ${fx.today.toEpochDay()}, 202608, 0, 1, 0, 0)",
        )

        val after = fx.expenses.filteredTotal(LedgerFilters(leafId = grocery))
        assertEquals("a pending row joined an aggregate", posted.totalMinor, after.totalMinor)
        assertEquals(1, after.txnCount)
    }

}
