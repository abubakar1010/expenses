package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Recurring rules against a real database — FR-REC-01 … FR-REC-05.
 *
 * The schedule arithmetic has its own JVM suite. What is asserted here is what
 * only SQLite can answer: that a generated entry is invisible to every figure
 * until it is confirmed, that re-running produces nothing, and that a rule
 * unopened for four months produces four confirmable entries and not one.
 */
@RunWith(AndroidJUnit4::class)
class RecurringRepositoryTest {

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.closeAfterDraining()

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun pendingCount() = scalar("SELECT COUNT(*) FROM expense WHERE status = 1")
    private fun postedCount() = scalar("SELECT COUNT(*) FROM expense WHERE status = 0")
    private fun rollupTotal() = scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month")

    private suspend fun rentRule(
        anchor: Int = 1,
        autoPost: Boolean = false,
        from: LocalDate = LocalDate.of(2026, 6, 1),
        frequency: Frequency = Frequency.MONTHLY,
    ): Long = (
        fx.recurring.createRule(
            target = RuleTarget.EXPENSE,
            targetId = fx.leafId("House Rent"),
            amount = Money.ofTaka(15_000),
            frequency = frequency,
            anchorDay = anchor,
            autoPost = autoPost,
            startingFrom = from,
        ) as SaveOutcome.Saved
        ).id

    // --- FR-REC-01 ------------------------------------------------------------

    @Test
    fun a_rule_records_its_target_frequency_and_first_due_date() = runBlocking {
        val id = rentRule(anchor = 5, from = LocalDate.of(2026, 6, 1))
        val rule = fx.recurring.ruleById(id)!!

        assertEquals(RuleTarget.EXPENSE.code, rule.target)
        assertEquals(fx.leafId("House Rent"), rule.categoryId)
        assertEquals(null, rule.sourceId)
        assertEquals(Frequency.MONTHLY.code, rule.frequency)
        assertEquals(LocalDate.of(2026, 6, 5).toEpochDay(), rule.nextDueDay)
    }

    @Test
    fun an_income_rule_targets_a_source_and_not_a_category() = runBlocking {
        // The table CHECK is an XOR; this is the repository holding to it.
        val salary = fx.db.incomeDao().observeAllSources().first().first { it.name == "Salary" }
        val outcome = fx.recurring.createRule(
            target = RuleTarget.INCOME,
            targetId = salary.id,
            amount = Money.ofTaka(30_000),
            frequency = Frequency.MONTHLY,
            anchorDay = 1,
        )
        val rule = fx.recurring.ruleById((outcome as SaveOutcome.Saved).id)!!
        assertEquals(salary.id, rule.sourceId)
        assertEquals(null, rule.categoryId)
    }

    @Test
    fun a_zero_amount_is_refused() = runBlocking {
        val outcome = fx.recurring.createRule(
            target = RuleTarget.EXPENSE,
            targetId = fx.leafId("House Rent"),
            amount = Money.ZERO,
            frequency = Frequency.MONTHLY,
            anchorDay = 1,
        )
        assertTrue(outcome is SaveOutcome.Rejected)
    }

    // --- FR-REC-02 ------------------------------------------------------------

    @Test
    fun a_generated_entry_is_pending_and_in_no_figure_at_all() = runBlocking {
        // The property the whole feature rests on. PRD §6.5: "silently generated
        // transactions that didn't actually happen destroy trust in the ledger
        // faster than any other bug."
        rentRule(from = LocalDate.of(2026, 8, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals(1, pendingCount())
        assertEquals("it is in no rollup", 0L, rollupTotal())
        assertEquals("and in no ledger read", 0L, postedCount())
    }

    @Test
    fun confirming_moves_it_into_every_aggregate() = runBlocking {
        rentRule(from = LocalDate.of(2026, 8, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        val pending = fx.recurring.observePendingExpenses().first().single()

        assertTrue(fx.recurring.confirmExpense(pending.expense.id))

        assertEquals(0, pendingCount())
        assertEquals(1, postedCount())
        // The trigger did this, not a line of application code.
        assertEquals(15_000_00L, rollupTotal())
    }

    @Test
    fun dismissing_removes_it_rather_than_posting_it() = runBlocking {
        // The month the rent was waived. An entry that never happened has no
        // business in a ledger, so it is deleted rather than zeroed or archived.
        rentRule(from = LocalDate.of(2026, 8, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        val pending = fx.recurring.observePendingExpenses().first().single()

        assertNotNull(fx.recurring.dismissExpense(pending.expense.id))

        assertEquals(0, pendingCount())
        assertEquals(0, postedCount())
        assertEquals(0L, rollupTotal())
    }

    @Test
    fun an_auto_posting_rule_writes_a_confirmed_entry() = runBlocking {
        rentRule(autoPost = true, from = LocalDate.of(2026, 8, 1))
        val result = fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals(1, result.posted)
        assertEquals(0, result.pending)
        assertEquals(1, postedCount())
        assertEquals(15_000_00L, rollupTotal())
    }

    @Test
    fun auto_post_is_off_unless_asked_for() = runBlocking {
        val rule = fx.recurring.ruleById(rentRule())!!
        assertEquals(false, rule.autoPost)
    }

    // --- FR-REC-03 ------------------------------------------------------------

    @Test
    fun evaluating_twice_on_the_same_day_generates_once() = runBlocking {
        // "Rule evaluation MUST be idempotent — repeated evaluation for the same
        // due date MUST NOT produce duplicates." This is every launch of the app.
        rentRule(from = LocalDate.of(2026, 8, 1))

        assertEquals(1, fx.recurring.evaluate(LocalDate.of(2026, 8, 14)).total)
        assertEquals(0, fx.recurring.evaluate(LocalDate.of(2026, 8, 14)).total)
        assertEquals(0, fx.recurring.evaluate(LocalDate.of(2026, 8, 20)).total)
        assertEquals(1, pendingCount())
    }

    @Test
    fun a_rule_whose_bookkeeping_was_reset_still_does_not_duplicate() = runBlocking {
        // The second guard: even with `next_due_day` wound back by hand — a
        // corrupted row, or an imported one — the ledger is checked for a
        // matching entry before another is written.
        rentRule(from = LocalDate.of(2026, 8, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        assertEquals(1, pendingCount())

        fx.db.openHelper.writableDatabase.execSQL(
            "UPDATE recurring_rule SET next_due_day = ${LocalDate.of(2026, 8, 1).toEpochDay()}," +
                " last_run_day = NULL",
        )
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals("still one", 1, pendingCount())
    }

    // --- FR-REC-04 ------------------------------------------------------------

    @Test
    fun every_missed_month_is_generated_on_the_next_launch() = runBlocking {
        // "Missed due dates accumulated while the app was unopened MUST all be
        // generated on next launch, each individually confirmable."
        rentRule(anchor = 1, from = LocalDate.of(2026, 5, 1))

        val result = fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        // May, June, July and August.
        assertEquals(4, result.pending)
        assertEquals(4, pendingCount())
        assertEquals(
            listOf(
                LocalDate.of(2026, 5, 1).toEpochDay(),
                LocalDate.of(2026, 6, 1).toEpochDay(),
                LocalDate.of(2026, 7, 1).toEpochDay(),
                LocalDate.of(2026, 8, 1).toEpochDay(),
            ),
            fx.recurring.observePendingExpenses().first().map { it.expense.spentOn },
        )
    }

    @Test
    fun each_caught_up_entry_is_confirmable_on_its_own() = runBlocking {
        rentRule(anchor = 1, from = LocalDate.of(2026, 6, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        val rows = fx.recurring.observePendingExpenses().first()
        assertEquals(3, rows.size)
        fx.recurring.confirmExpense(rows.first().expense.id)

        assertEquals("two still waiting", 2, pendingCount())
        assertEquals(1, postedCount())
    }

    @Test
    fun a_paused_rule_generates_nothing() = runBlocking {
        val id = rentRule(from = LocalDate.of(2026, 5, 1))
        fx.recurring.setActive(id, active = false)

        assertEquals(0, fx.recurring.evaluate(LocalDate.of(2026, 8, 14)).total)
        assertEquals(0, pendingCount())
    }

    @Test
    fun a_weekly_rule_catches_up_week_by_week() = runBlocking {
        rentRule(frequency = Frequency.WEEKLY, from = LocalDate.of(2026, 7, 17))
        val result = fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        // 17, 24, 31 July, then 7 and 14 August.
        assertEquals(5, result.pending)
    }

    // --- FR-REC-05 ------------------------------------------------------------

    @Test
    fun an_anchor_of_thirty_one_clamps_and_then_recovers() = runBlocking {
        // The requirement's own case, end to end: January's 31st, February's
        // 28th, and March's 31st again — not the 28th for ever after.
        rentRule(anchor = 31, from = LocalDate.of(2026, 1, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 3, 31))

        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31).toEpochDay(),
                LocalDate.of(2026, 2, 28).toEpochDay(),
                LocalDate.of(2026, 3, 31).toEpochDay(),
            ),
            fx.recurring.observePendingExpenses().first().map { it.expense.spentOn },
        )
    }

    @Test
    fun the_generated_period_follows_the_generated_date() = runBlocking {
        // 03 §4.3 — `period_ym` is derived by the application from the date on
        // every write path, and this is one.
        rentRule(anchor = 1, from = LocalDate.of(2026, 7, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals(
            listOf(202607L, 202608L),
            fx.recurring.observePendingExpenses().first().map { it.expense.periodYm.toLong() },
        )
    }

    // --- the manager's list ---------------------------------------------------

    @Test
    fun a_rule_lists_with_the_name_of_whatever_it_posts_to() = runBlocking {
        rentRule()
        val salary = fx.db.incomeDao().observeAllSources().first().first { it.name == "Salary" }
        fx.recurring.createRule(
            target = RuleTarget.INCOME,
            targetId = salary.id,
            amount = Money.ofTaka(30_000),
            frequency = Frequency.MONTHLY,
            anchorDay = 1,
        )

        val names = fx.recurring.observeRules().first().map { it.targetName }
        assertTrue("expected both targets resolved, got $names", names.containsAll(listOf("House Rent", "Salary")))
    }

    @Test
    fun deleting_a_rule_leaves_the_entries_it_generated() = runBlocking {
        // They are the user's transactions now, not the rule's.
        val id = rentRule(from = LocalDate.of(2026, 8, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        val pending = fx.recurring.observePendingExpenses().first().single()
        fx.recurring.confirmExpense(pending.expense.id)

        fx.recurring.deleteRule(id)

        assertEquals(null, fx.recurring.ruleById(id))
        assertEquals(1, postedCount())
        assertNotNull(fx.expenses.byId(pending.expense.id))
    }

    // --- A3: a rule does not outlive its target ------------------------------

    @Test
    fun a_rule_whose_category_was_archived_generates_nothing() = runBlocking {
        // FR-CAT-08 puts an archived category out of the entry pickers. A rule
        // still posting into one would create entries the user could not create
        // by hand.
        rentRule(from = LocalDate.of(2026, 6, 1))
        fx.categories.archive(fx.leafId("House Rent"))

        assertEquals(0, fx.recurring.evaluate(LocalDate.of(2026, 8, 14)).total)
        assertEquals(0, pendingCount())
    }

    @Test
    fun un_archiving_the_category_resumes_the_rule() = runBlocking {
        // Skipped, not cancelled: the rule is untouched, so restoring the
        // category restores it too.
        val leaf = fx.leafId("House Rent")
        rentRule(from = LocalDate.of(2026, 8, 1))
        fx.categories.archive(leaf)
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        assertEquals(0, pendingCount())

        fx.categories.restore(leaf)
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals(1, pendingCount())
    }

    @Test
    fun an_archived_target_is_reported_on_the_rule_row() = runBlocking {
        // A rule that looks live and does nothing is worse than one that says
        // why, so the manager row carries the reason.
        rentRule()
        fx.categories.archive(fx.leafId("House Rent"))

        val row = fx.recurring.observeRules().first().single()
        assertTrue(row.targetArchived)
        assertTrue("and the rule itself is untouched", row.rule.isActive)
    }

    @Test
    fun archiving_one_target_leaves_other_rules_running() = runBlocking {
        rentRule(from = LocalDate.of(2026, 8, 1))
        fx.recurring.createRule(
            target = RuleTarget.EXPENSE,
            targetId = fx.leafId("Internet"),
            amount = Money.ofTaka(1_200),
            frequency = Frequency.MONTHLY,
            anchorDay = 1,
            startingFrom = LocalDate.of(2026, 8, 1),
        )
        fx.categories.archive(fx.leafId("House Rent"))

        assertEquals(1, fx.recurring.evaluate(LocalDate.of(2026, 8, 14)).total)
        assertEquals(
            listOf("Internet"),
            fx.recurring.observePendingExpenses().first().map { it.categoryName },
        )
    }

    // --- A4: NFR-USE-03 ------------------------------------------------------

    @Test
    fun deleting_a_rule_hands_the_row_back_for_undo() = runBlocking {
        val id = rentRule(anchor = 31, from = LocalDate.of(2026, 6, 1))
        val original = fx.recurring.ruleById(id)!!

        val deleted = fx.recurring.deleteRule(id)
        assertNotNull(deleted)
        assertEquals(null, fx.recurring.ruleById(id))

        fx.recurring.restoreRule(deleted!!)

        val restored = fx.recurring.observeRules().first().single().rule
        assertEquals(original.uuid, restored.uuid)
        assertEquals("the schedule comes back too", original.nextDueDay, restored.nextDueDay)
        assertEquals(original.anchorDay, restored.anchorDay)
        assertEquals(original.amountMinor, restored.amountMinor)
    }

    @Test
    fun deleting_a_rule_that_is_already_gone_returns_nothing_rather_than_throwing() = runBlocking {
        assertEquals(null, fx.recurring.deleteRule(9_999L))
    }
    // --- FR-REC-03, the two guards that were not there ------------------------

    @Test
    fun logging_the_rent_by_hand_does_not_switch_the_rent_rule_off() = runBlocking {
        // The ledger check was unfiltered on `status`, and a rule's occurrence
        // is indistinguishable from an entry the user makes themselves: same
        // category, same day, same amount. So somebody who paid the rent and
        // logged it before opening the app suppressed that month's generation
        // — and because they do the same thing every month, the rule never
        // generated again. It looked like the feature simply not working.
        rentRule(anchor = 1, from = LocalDate.of(2026, 8, 1))
        fx.expenses.insert(
            amount = Money.ofTaka(15_000),
            categoryId = fx.leafId("House Rent"),
            spentOn = LocalDate.of(2026, 8, 1),
        )

        val result = fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals("the rule should still have generated its occurrence", 1, result.pending)
        assertEquals(1, pendingCount())
        // And the user's own entry is untouched and still counted.
        assertEquals(1, postedCount())
        assertEquals(15_000_00L, rollupTotal())
    }

    @Test
    fun a_pending_occurrence_still_blocks_a_second_one_of_the_same_shape() = runBlocking {
        // The narrowing must not have taken the guard away. Two rules of the
        // same shape are a real case — a landlord and a garage at the same
        // amount — but a *single* rule evaluated twice against a corrupted
        // schedule must still not double up.
        val id = rentRule(anchor = 1, from = LocalDate.of(2026, 8, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        assertEquals(1, pendingCount())

        // Wind `next_due_day` back onto a day already generated, which is what
        // editing a rule's anchor can do, and re-run.
        fx.db.openHelper.writableDatabase.execSQL(
            "UPDATE recurring_rule SET next_due_day = ${LocalDate.of(2026, 8, 1).toEpochDay()} " +
                "WHERE id = $id",
        )
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals("a second occurrence was generated", 1, pendingCount())
    }

    @Test
    fun last_run_day_stops_an_auto_posting_rule_regenerating_a_day_it_has_done() = runBlocking {
        // The column's own KDoc says "generation proceeds only when
        // next_due_day > last_run_day", and nothing read it — it was written on
        // every evaluation and never consulted. For an auto-posting rule that
        // matters more than for a pending one, because auto-posted rows are
        // `status = 0` and the ledger check (now narrowed to pending rows)
        // cannot see them at all. This is the only guard left, so it has to
        // actually exist.
        val id = rentRule(anchor = 1, autoPost = true, from = LocalDate.of(2026, 8, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        assertEquals(1, postedCount())
        assertEquals(15_000_00L, rollupTotal())

        fx.db.openHelper.writableDatabase.execSQL(
            "UPDATE recurring_rule SET next_due_day = ${LocalDate.of(2026, 8, 1).toEpochDay()} " +
                "WHERE id = $id",
        )
        val again = fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals(0, again.posted)
        assertEquals("the rent was posted twice", 1, postedCount())
        assertEquals(15_000_00L, rollupTotal())
    }

    @Test
    fun catch_up_still_generates_every_missed_month() = runBlocking {
        // FR-REC-04, re-asserted because `last_run_day` is now a gate the
        // catch-up loop has to pass through on every iteration. A rule that
        // advances correctly must not be slowed by its own guard.
        rentRule(anchor = 1, from = LocalDate.of(2026, 4, 1))

        val result = fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals(5, result.pending)
        assertEquals(5, pendingCount())
    }

    // --- the income half, and the auto-post switch ---------------------------

    @Test
    fun a_pending_income_entry_is_in_no_figure_until_it_is_confirmed() = runBlocking {
        // The mirror of the expense case, and it had no test of its own —
        // `confirmIncome`, `dismissIncome` and `restoreIncome` were all at zero
        // coverage. Income is the side where a rule most often *is* auto-posted
        // (a salary), so the pending path is the less-travelled one and
        // therefore the one worth pinning.
        salaryRule()
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        val pending = fx.db.recurringDao().observePendingIncome().first().single()
        assertEquals(0L, incomeRollupTotal())

        assertTrue(fx.recurring.confirmIncome(pending.entry.id))
        assertEquals(45_000_00L, incomeRollupTotal())
    }

    @Test
    fun dismissing_a_pending_income_entry_can_be_undone() = runBlocking {
        // NFR-USE-03, and here the usual escape hatch is closed: the rule has
        // moved past that due date and will not generate it again, so the held
        // row is the only copy there is.
        salaryRule()
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        val pending = fx.db.recurringDao().observePendingIncome().first().single()

        val removed = fx.recurring.dismissIncome(pending.entry.id)
        assertNotNull(removed)
        assertTrue(fx.db.recurringDao().observePendingIncome().first().isEmpty())
        assertEquals("a dismissed entry must be in no figure", 0L, incomeRollupTotal())

        fx.recurring.restoreIncome(removed!!)
        assertEquals(1, fx.db.recurringDao().observePendingIncome().first().size)
        assertEquals("restoring must not post it either", 0L, incomeRollupTotal())
    }

    @Test
    fun confirming_a_row_that_is_already_posted_changes_nothing() = runBlocking {
        // Two taps on the same Confirm, which a slow frame makes ordinary.
        // `confirmIncome` filters on `status = 1`, so the second is a no-op —
        // and it has to be, because the rollup trigger fires on the status
        // change and a second one would double the figure.
        salaryRule()
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
        val pending = fx.db.recurringDao().observePendingIncome().first().single()

        assertTrue(fx.recurring.confirmIncome(pending.entry.id))
        assertFalse("the second confirm reported success", fx.recurring.confirmIncome(pending.entry.id))
        assertEquals(45_000_00L, incomeRollupTotal())
    }

    @Test
    fun turning_auto_post_on_makes_the_next_occurrence_post_itself() = runBlocking {
        // FR-REC-02's switch. `setAutoPost` was at zero coverage, and it is the
        // difference between a row the user must confirm and a row that lands
        // in the month's total unattended.
        val id = rentRule(anchor = 1, autoPost = false, from = LocalDate.of(2026, 7, 1))
        fx.recurring.evaluate(LocalDate.of(2026, 7, 14))
        assertEquals(1, pendingCount())
        assertEquals(0L, rollupTotal())

        fx.recurring.setAutoPost(id, autoPost = true)
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        assertEquals("August should have posted itself", 1, postedCount())
        assertEquals(15_000_00L, rollupTotal())
        assertEquals("July must still be waiting", 1, pendingCount())
    }

    private fun incomeRollupTotal() =
        scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_income_month")

    private suspend fun salaryRule(
        anchor: Int = 1,
        autoPost: Boolean = false,
        from: LocalDate = LocalDate.of(2026, 8, 1),
    ): Long = (
        fx.recurring.createRule(
            target = RuleTarget.INCOME,
            targetId = fx.db.incomeDao().observeAllSources().first().first { it.name == "Salary" }.id,
            amount = Money.ofTaka(45_000),
            frequency = Frequency.MONTHLY,
            anchorDay = anchor,
            autoPost = autoPost,
            startingFrom = from,
        ) as SaveOutcome.Saved
        ).id

}
