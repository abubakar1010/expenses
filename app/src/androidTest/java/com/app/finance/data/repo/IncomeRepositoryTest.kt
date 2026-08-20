package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.IncomeKind
import com.app.finance.domain.model.IncomeScope
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The income rules of FR-IS-* and FR-IE-*, exercised through the repository
 * against real SQLite rather than a mock.
 *
 * The behaviour that matters here lives in the database — the unique index on
 * `name_key`, `ON DELETE RESTRICT`, the `amount_minor > 0` CHECK, and the three
 * rollup triggers — and a mock would assert only that the code calls the
 * methods it was written to call.
 */
@RunWith(AndroidJUnit4::class)
class IncomeRepositoryTest {

    private lateinit var fx: TestFixture

    private val aug = Period.of(2026, 8)
    private val jun = Period.of(2026, 6)

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.close()

    private fun day(month: Int, day: Int): LocalDate = LocalDate.of(2026, month, day)

    private suspend fun sourceId(name: String) =
        fx.db.incomeDao().observeAllSources().first().first { it.name == name }.id

    private suspend fun cells(scope: IncomeScope) = fx.income.observeCells(scope.window).first()

    private suspend fun total(scope: IncomeScope) = cells(scope).sumOf { it.totalMinor }

    // --- FR-IS-02: uniqueness on the normalised key --------------------------

    @Test
    fun three_spellings_of_one_name_resolve_to_a_single_source() = runBlocking {
        // The acceptance criterion verbatim: "salary", " Salary" and "SALARY"
        // when "Salary" exists must resolve to the existing source and not
        // create a duplicate row. Salary is seeded, so this also covers the
        // seeded row being reachable this way.
        val before = fx.db.incomeDao().observeAllSources().first().size

        fx.income.saveEntry(Money.ofTaka(30_000), "salary", day(8, 1))
        fx.income.saveEntry(Money.ofTaka(30_000), " Salary", day(8, 2))
        fx.income.saveEntry(Money.ofTaka(30_000), "SALARY", day(8, 3))

        val sources = fx.db.incomeDao().observeAllSources().first()
        assertEquals("no new source may be created", before, sources.size)
        assertEquals(3, fx.db.incomeDao().countEntriesForSource(sourceId("Salary")))
    }

    @Test
    fun the_stored_name_keeps_the_spelling_the_source_was_created_with() = runBlocking {
        // Resolving on the key must not rewrite the display name: "SALARY"
        // attaching to "Salary" leaves the source called Salary.
        fx.income.saveEntry(Money.ofTaka(1_000), "SALARY", day(8, 1))
        assertTrue(fx.db.incomeDao().observeAllSources().first().any { it.name == "Salary" })
    }

    // --- FR-IS-03: inline creation -------------------------------------------

    @Test
    fun an_unrecognised_name_creates_the_source_and_attaches_the_entry() = runBlocking {
        // "Typing 'Poultry' in the source field of the entry form and saving
        // produces one new income_source row and one income_entry row
        // referencing it." No separate navigation step, which is the point.
        val outcome = fx.income.saveEntry(Money.ofTaka(12_000), "Poultry", day(8, 4))
        assertTrue(outcome is SaveOutcome.Saved)

        val poultry = fx.db.incomeDao().observeAllSources().first().first { it.name == "Poultry" }
        assertEquals(1, fx.db.incomeDao().countEntriesForSource(poultry.id))
        // FR-IS-01's default: a source created this way is Variable, because
        // calling something stable when it is not overstates the coverage
        // figure the income screen exists to report.
        assertEquals(IncomeKind.VARIABLE.code, poultry.kind)
    }

    @Test
    fun a_rejected_entry_leaves_no_source_behind() = runBlocking {
        // The reason the source insert and the entry insert are one
        // transaction. A user who typed a name and then a zero should not have
        // to go and delete a source they never asked for.
        val before = fx.db.incomeDao().observeAllSources().first().size
        assertEquals(
            SaveOutcome.Rejected(EntryError.NON_POSITIVE_INCOME),
            fx.income.saveEntry(Money.ZERO, "Poultry", day(8, 4)),
        )
        assertEquals(before, fx.db.incomeDao().observeAllSources().first().size)
    }

    @Test
    fun a_blank_source_name_is_refused() = runBlocking {
        assertEquals(
            SaveOutcome.Rejected(EntryError.BLANK_NAME),
            fx.income.saveEntry(Money.ofTaka(500), "   ", day(8, 4)),
        )
    }

    // --- FR-IE-02, FR-IE-03 ---------------------------------------------------

    @Test
    fun one_source_takes_unlimited_entries_in_one_period() = runBlocking {
        // The acceptance criterion's own example: two farming sales in June are
        // two entries, not one, and both appear in the June total.
        fx.income.saveEntry(Money.ofTaka(50_000), "Farming", day(6, 4))
        fx.income.saveEntry(Money.ofTaka(30_000), "Farming", day(6, 19))

        assertEquals(2, fx.db.incomeDao().countEntriesForSource(sourceId("Farming")))
        assertEquals(80_000_00L, total(IncomeScope.Month(jun)))
    }

    @Test
    fun zero_and_negative_amounts_are_both_refused() = runBlocking {
        // FR-IE-03 — "greater than zero", with the criterion naming "0 or
        // negative input". Income has no refund case; an expense of −৳340 is
        // legal and this is not.
        assertEquals(
            SaveOutcome.Rejected(EntryError.NON_POSITIVE_INCOME),
            fx.income.saveEntry(Money.ZERO, "Salary", day(8, 1)),
        )
        assertEquals(
            SaveOutcome.Rejected(EntryError.NON_POSITIVE_INCOME),
            fx.income.saveEntry(Money.ofTaka(-500), "Salary", day(8, 1)),
        )
        assertTrue(cells(IncomeScope.Month(aug)).isEmpty())
    }

    // --- FR-IE-08 -------------------------------------------------------------

    @Test
    fun editing_an_entry_across_a_year_boundary_leaves_both_years_right() = runBlocking {
        // The trigger decrements the old (period, source) bucket and increments
        // the new one, so this is correct by construction rather than by
        // remembering to handle it.
        val saved = fx.income.saveEntry(
            Money.ofTaka(40_000),
            "Property",
            LocalDate.of(2025, 12, 20),
        ) as SaveOutcome.Saved

        assertEquals(40_000_00L, total(IncomeScope.Year(Period.of(2025, 6))))
        assertEquals(0L, total(IncomeScope.Year(aug)))

        fx.income.updateEntry(saved.id, Money.ofTaka(40_000), "Property", day(1, 8), null)

        assertEquals(0L, total(IncomeScope.Year(Period.of(2025, 6))))
        assertEquals(40_000_00L, total(IncomeScope.Year(aug)))
    }

    @Test
    fun editing_the_amount_moves_every_total_that_depends_on_it() = runBlocking {
        val saved = fx.income.saveEntry(Money.ofTaka(50_000), "Farming", day(6, 4))
                as SaveOutcome.Saved
        fx.income.updateEntry(saved.id, Money.ofTaka(20_000), "Farming", day(6, 4), "revised")

        assertEquals(20_000_00L, total(IncomeScope.Month(jun)))
        assertEquals(20_000_00L, total(IncomeScope.Year(aug)))
        assertEquals("revised", fx.income.entryById(saved.id)!!.note)
    }

    @Test
    fun editing_to_a_new_source_creates_it_and_moves_the_money() = runBlocking {
        val saved = fx.income.saveEntry(Money.ofTaka(9_000), "Salary", day(8, 1))
                as SaveOutcome.Saved
        fx.income.updateEntry(saved.id, Money.ofTaka(9_000), "Tuition", day(8, 1), null)

        val cells = cells(IncomeScope.Month(aug))
        assertEquals(listOf("Tuition"), cells.map { it.sourceName })
        assertEquals(0, fx.db.incomeDao().countEntriesForSource(sourceId("Salary")))
    }

    @Test
    fun a_deleted_entry_can_be_restored_verbatim() = runBlocking {
        // NFR-USE-03's undo. The uuid must survive, because export dedup keys
        // on it (03 §1) — an undo that changed identity would look like a new
        // row to any future import.
        val saved = fx.income.saveEntry(Money.ofTaka(7_000), "Farming", day(6, 4))
                as SaveOutcome.Saved
        val row = fx.income.deleteEntry(saved.id)
        assertNotNull(row)
        assertEquals(0L, total(IncomeScope.Month(jun)))

        fx.income.restoreEntry(row!!)
        assertEquals(7_000_00L, total(IncomeScope.Month(jun)))
        assertEquals(row.uuid, fx.db.incomeDao().observeEntriesInPeriod(jun.ym).first().single().uuid)
    }

    // --- FR-IS-04, FR-IS-05, FR-IS-06 ----------------------------------------

    @Test
    fun an_archived_source_leaves_the_picker_but_keeps_its_history() = runBlocking {
        // "Archived sources MUST be excluded from entry pickers and MUST remain
        // visible in historical reports." Both halves, in one test, because
        // getting one without the other is the failure mode.
        fx.income.saveEntry(Money.ofTaka(80_000), "Farming", day(6, 4))
        val farming = sourceId("Farming")
        fx.income.setSourceArchived(farming, archived = true)

        assertFalse(fx.income.observeActiveSources().first().any { it.id == farming })
        assertTrue(cells(IncomeScope.Year(aug)).any { it.sourceName == "Farming" })
    }

    @Test
    fun a_source_with_entries_cannot_be_deleted() = runBlocking {
        // FR-IS-05. The repository refuses it, and `ON DELETE RESTRICT` would
        // refuse it even if the repository forgot.
        fx.income.saveEntry(Money.ofTaka(1_000), "Farming", day(6, 4))
        val farming = sourceId("Farming")

        assertEquals(
            DeleteSourceOutcome.Rejected(EntryError.SOURCE_HAS_ENTRIES),
            fx.income.deleteSource(farming),
        )
        assertNotNull(fx.income.sourceById(farming))
    }

    @Test
    fun a_source_with_no_entries_can_be_deleted() = runBlocking {
        // FR-IS-06 — the only delete in the application.
        val created = fx.income.createSource("Consulting", IncomeKind.VARIABLE) as SaveOutcome.Saved
        val outcome = fx.income.deleteSource(created.id)

        assertTrue(outcome is DeleteSourceOutcome.Deleted)
        assertNull(fx.income.sourceById(created.id))
    }

    @Test
    fun deleting_a_source_that_had_its_last_entry_removed_now_succeeds() = runBlocking {
        // The state that makes the disabled/enabled distinction real rather
        // than academic: the count is what governs, not whether the source ever
        // had entries.
        val saved = fx.income.saveEntry(Money.ofTaka(1_000), "Consulting", day(6, 4))
                as SaveOutcome.Saved
        val consulting = sourceId("Consulting")
        assertEquals(
            DeleteSourceOutcome.Rejected(EntryError.SOURCE_HAS_ENTRIES),
            fx.income.deleteSource(consulting),
        )

        fx.income.deleteEntry(saved.id)
        assertTrue(fx.income.deleteSource(consulting) is DeleteSourceOutcome.Deleted)
    }

    @Test
    fun a_deleted_source_can_be_restored() = runBlocking {
        val created = fx.income.createSource("Consulting", IncomeKind.STABLE) as SaveOutcome.Saved
        val deleted = fx.income.deleteSource(created.id) as DeleteSourceOutcome.Deleted

        fx.income.restoreSource(deleted.source)
        val restored = fx.db.incomeDao().observeAllSources().first().first { it.name == "Consulting" }
        assertEquals(deleted.source.uuid, restored.uuid)
        assertEquals(IncomeKind.STABLE.code, restored.kind)
    }

    @Test
    fun a_duplicate_source_name_is_rejected_by_the_unique_index() = runBlocking {
        // FR-IS-02 through the explicit create path rather than the inline one.
        // `ux_income_source_key` is what makes it structural; this asserts the
        // exception becomes a typed error rather than reaching the user.
        assertEquals(
            SaveOutcome.Rejected(EntryError.DUPLICATE_NAME),
            fx.income.createSource("salary", IncomeKind.VARIABLE),
        )
    }

    @Test
    fun a_source_kind_can_be_changed_after_creation() = runBlocking {
        // The coverage figure depends on the classification, and a source that
        // turns out to arrive on a rhythm should be reclassifiable without
        // deleting and re-entering its history.
        val created = fx.income.createSource("Rent received", IncomeKind.VARIABLE)
                as SaveOutcome.Saved
        fx.income.setSourceKind(created.id, IncomeKind.STABLE)
        assertEquals(IncomeKind.STABLE.code, fx.income.sourceById(created.id)!!.kind)
    }

    @Test
    fun the_source_list_carries_its_entry_count() = runBlocking {
        // What drives the manager's enabled/disabled delete.
        fx.income.saveEntry(Money.ofTaka(1_000), "Farming", day(6, 4))
        fx.income.saveEntry(Money.ofTaka(2_000), "Farming", day(6, 9))
        fx.income.createSource("Consulting", IncomeKind.VARIABLE)

        val rows = fx.income.observeSourcesWithCounts().first()
        assertEquals(2, rows.first { it.source.name == "Farming" }.entryCount)
        assertEquals(0, rows.first { it.source.name == "Consulting" }.entryCount)
    }

    // --- FR-IE-04, FR-IE-05 ---------------------------------------------------

    @Test
    fun a_custom_range_reads_the_ledger_rather_than_the_rollup() = runBlocking {
        // 03 §5.3 — a range that does not align to month boundaries cannot use
        // the rollup. The figure it produces must still be exact, which is what
        // this pins: 15 June to 10 August excludes the 4 June sale.
        fx.income.saveEntry(Money.ofTaka(50_000), "Farming", day(6, 4))
        fx.income.saveEntry(Money.ofTaka(30_000), "Farming", day(6, 19))
        fx.income.saveEntry(Money.ofTaka(20_000), "Salary", day(8, 1))

        val scope = IncomeScope.Range(day(6, 15), day(8, 10))
        assertEquals(50_000_00L, total(scope))
        assertEquals(100_000_00L, total(IncomeScope.Year(aug)))
    }

    @Test
    fun the_entry_list_combines_a_source_subset_with_the_window() = runBlocking {
        // FR-IE-05's acceptance criterion: {Salary, Farming} over a range
        // returns exactly the entries matching both predicates.
        fx.income.saveEntry(Money.ofTaka(50_000), "Farming", day(6, 4))
        fx.income.saveEntry(Money.ofTaka(20_000), "Salary", day(7, 1))
        fx.income.saveEntry(Money.ofTaka(15_000), "Property", day(7, 9))

        val window = IncomeScope.Year(aug).window
        val subset = setOf(sourceId("Farming"), sourceId("Salary"))

        val filtered = fx.income.observeEntries(window, subset).first()
        assertEquals(setOf("Farming", "Salary"), filtered.map { it.sourceName }.toSet())

        val all = fx.income.observeEntries(window, emptySet()).first()
        assertEquals("an empty subset means every source, not none", 3, all.size)
    }

    @Test
    fun the_entry_list_is_newest_first() = runBlocking {
        fx.income.saveEntry(Money.ofTaka(1_000), "Salary", day(6, 4))
        fx.income.saveEntry(Money.ofTaka(2_000), "Salary", day(8, 1))
        fx.income.saveEntry(Money.ofTaka(3_000), "Salary", day(7, 9))

        val days = fx.income.observeEntries(IncomeScope.Year(aug).window, emptySet())
            .first()
            .map { it.entry.earnedOn }
        assertEquals(days.sortedDescending(), days)
    }

    // --- source-domain errors say "source" -----------------------------------

    @Test
    fun a_missing_source_is_reported_as_a_missing_source() = runBlocking {
        // Routing these through CATEGORY_NOT_FOUND printed "Pick a category" on
        // a screen with no categories on it. 04 §8 requires the typed error to
        // be actionable, and copy about the wrong domain is not.
        val gone = 9_999L

        assertEquals(
            SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND),
            fx.income.saveEntryToSource(Money.ofTaka(100), gone, LocalDate.of(2026, 8, 1)),
        )
        assertEquals(
            SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND),
            fx.income.renameSource(gone, "Whatever"),
        )
        assertEquals(
            SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND),
            fx.income.setSourceKind(gone, IncomeKind.STABLE),
        )
        assertEquals(
            SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND),
            fx.income.setSourceArchived(gone, archived = true),
        )
        assertEquals(
            DeleteSourceOutcome.Rejected(EntryError.SOURCE_NOT_FOUND),
            fx.income.deleteSource(gone),
        )
    }

    @Test
    fun name_and_kind_change_together_or_not_at_all() = runBlocking {
        // Two writes let the rename land and the kind fail behind it, leaving a
        // source renamed and still classified wrongly — and the kind is the one
        // field the coverage figure depends on.
        val id = (fx.income.createSource("Consulting", IncomeKind.VARIABLE) as SaveOutcome.Saved).id

        assertEquals(
            SaveOutcome.Saved(id),
            fx.income.updateSource(id, "Retainer", IncomeKind.STABLE),
        )
        val after = fx.income.sourceById(id)!!
        assertEquals("Retainer", after.name)
        assertEquals(IncomeKind.STABLE.code, after.kind)
    }

    @Test
    fun a_rejected_update_changes_neither_the_name_nor_the_kind() = runBlocking {
        // FR-IS-02's unique key is what rejects it. The point is that the kind
        // does not slip through behind the refusal.
        val id = (fx.income.createSource("Consulting", IncomeKind.VARIABLE) as SaveOutcome.Saved).id

        assertEquals(
            SaveOutcome.Rejected(EntryError.DUPLICATE_NAME),
            fx.income.updateSource(id, " SALARY ", IncomeKind.STABLE),
        )
        val after = fx.income.sourceById(id)!!
        assertEquals("Consulting", after.name)
        assertEquals(IncomeKind.VARIABLE.code, after.kind)
    }

    // --- a repeating entry blocks the delete too (C2) -------------------------

    @Test
    fun a_source_a_repeating_entry_points_at_cannot_be_deleted() = runBlocking {
        // `recurring_rule.source_id` is `ON DELETE RESTRICT` just as
        // `income_entry.source_id` is. Before the audit the delete was gated on
        // entries alone, reached SQLite, was refused by the foreign key, and
        // came back as "that source no longer exists" — about a row in front of
        // the user.
        val id = (fx.income.createSource("Consulting", IncomeKind.VARIABLE) as SaveOutcome.Saved).id
        fx.recurring.createRule(
            target = com.app.finance.domain.model.RuleTarget.INCOME,
            targetId = id,
            amount = Money.ofTaka(8_000),
            frequency = com.app.finance.domain.model.Frequency.MONTHLY,
            anchorDay = 1,
        )

        assertEquals(
            DeleteSourceOutcome.Rejected(EntryError.SOURCE_HAS_RULES),
            fx.income.deleteSource(id),
        )
        assertNotNull("and it is still there", fx.income.sourceById(id))
    }

    @Test
    fun the_rule_count_is_what_the_manager_reads() = runBlocking {
        // FR-IS-05 wants the control *disabled with a reason*, so the count has
        // to reach the row rather than being discovered on tap.
        val id = (fx.income.createSource("Consulting", IncomeKind.VARIABLE) as SaveOutcome.Saved).id
        assertEquals(0, fx.income.countRulesForSource(id))

        fx.recurring.createRule(
            target = com.app.finance.domain.model.RuleTarget.INCOME,
            targetId = id,
            amount = Money.ofTaka(8_000),
            frequency = com.app.finance.domain.model.Frequency.MONTHLY,
            anchorDay = 1,
        )
        assertEquals(1, fx.income.countRulesForSource(id))
    }

    @Test
    fun deleting_becomes_possible_once_the_rule_is_gone() = runBlocking {
        val id = (fx.income.createSource("Consulting", IncomeKind.VARIABLE) as SaveOutcome.Saved).id
        val rule = (
            fx.recurring.createRule(
                target = com.app.finance.domain.model.RuleTarget.INCOME,
                targetId = id,
                amount = Money.ofTaka(8_000),
                frequency = com.app.finance.domain.model.Frequency.MONTHLY,
                anchorDay = 1,
            ) as SaveOutcome.Saved
            ).id

        fx.recurring.deleteRule(rule)

        assertTrue(fx.income.deleteSource(id) is DeleteSourceOutcome.Deleted)
    }

    @Test
    fun entries_are_reported_ahead_of_rules_when_both_hold_it() = runBlocking {
        // Both are true; the row shows one sentence. Entries first, because
        // that is the one the user is likelier to be able to act on.
        val id = (fx.income.createSource("Consulting", IncomeKind.VARIABLE) as SaveOutcome.Saved).id
        fx.income.saveEntryToSource(Money.ofTaka(500), id, LocalDate.of(2026, 8, 1))
        fx.recurring.createRule(
            target = com.app.finance.domain.model.RuleTarget.INCOME,
            targetId = id,
            amount = Money.ofTaka(8_000),
            frequency = com.app.finance.domain.model.Frequency.MONTHLY,
            anchorDay = 1,
        )

        assertEquals(
            DeleteSourceOutcome.Rejected(EntryError.SOURCE_HAS_ENTRIES),
            fx.income.deleteSource(id),
        )
    }

    // --- FR-IS-07: reorder sources for display (§20.2) -----------------------

    private suspend fun sourceNames(): List<String> =
        fx.income.observeActiveSources().first().map { it.name }

    @Test
    fun a_source_swaps_with_the_one_above_it() = runBlocking {
        fx.income.createSource("Consulting", IncomeKind.VARIABLE)
        fx.income.createSource("Rent received", IncomeKind.STABLE)
        val before = sourceNames()
        assertTrue("needs at least two sources", before.size >= 2)

        val id = fx.income.observeActiveSources().first()[1].id
        assertTrue(fx.income.moveSource(id, up = true))

        val after = sourceNames()
        assertEquals(before[1], after[0])
        assertEquals(before[0], after[1])
    }

    @Test
    fun the_ends_refuse_rather_than_wrap() = runBlocking {
        fx.income.createSource("Consulting", IncomeKind.VARIABLE)
        val sources = fx.income.observeActiveSources().first()
        val before = sourceNames()

        assertFalse(fx.income.moveSource(sources.first().id, up = true))
        assertFalse(fx.income.moveSource(sources.last().id, up = false))
        assertEquals(before, sourceNames())
    }

    @Test
    fun an_archived_source_is_not_a_place_to_move_to() = runBlocking {
        // FR-IS-04 lists archived sources apart. They keep positions so the
        // next insert cannot collide with them, but they are not somewhere the
        // user can move an active source to.
        fx.income.createSource("Consulting", IncomeKind.VARIABLE)
        val consulting = fx.income.observeActiveSources().first().last()
        fx.income.setSourceArchived(consulting.id, archived = true)

        val active = fx.income.observeActiveSources().first()
        assertFalse(fx.income.moveSource(active.last().id, up = false))
    }
}
