package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettleUpCycles] — which of a person's entries are settled (FR-SHR-06).
 *
 * Signs follow the balance: a share of a bill you paid is positive, a bill they
 * paid and a repayment from them are negative.
 */
class SettleUpCyclesTest {

    private fun expense(id: Long, day: Long, taka: Long, createdAt: Long = day) =
        HistoryEvent(HistoryEvent.Kind.EXPENSE, id, day, createdAt, Money.ofTaka(taka))

    private fun settlement(id: Long, day: Long, taka: Long, createdAt: Long = day) =
        HistoryEvent(HistoryEvent.Kind.SETTLEMENT, id, day, createdAt, Money.ofTaka(taka))

    @Test
    fun an_empty_history_has_nothing_settled_and_nothing_open() {
        val history = SettleUpCycles.of(emptyList())
        assertTrue(history.cycles.isEmpty())
        assertTrue(history.openExpenseIds.isEmpty())
        assertTrue(history.openSettlementIds.isEmpty())
    }

    @Test
    fun settling_in_full_closes_one_cycle_holding_everything() {
        val history = SettleUpCycles.of(
            listOf(expense(1, day = 10, taka = 300), expense(2, day = 11, taka = 200), settlement(9, day = 12, taka = -500)),
        )

        val cycle = history.cycles.single()
        assertEquals(setOf(1L, 2L), cycle.expenseIds)
        assertEquals(setOf(9L), cycle.settlementIds)
        assertEquals(12L, cycle.closedOnDay)
        assertEquals(3, cycle.entryCount)
        assertTrue(history.openExpenseIds.isEmpty())
        assertTrue(history.openSettlementIds.isEmpty())
    }

    @Test
    fun a_partial_repayment_closes_nothing() {
        // ৳200 of ৳500. Choosing which dinner that paid for would be a guess,
        // and a dinner shown as settled while money is still owed on it is the
        // one thing this must never show.
        val history = SettleUpCycles.of(listOf(expense(1, 10, 500), settlement(9, 11, -200)))

        assertTrue(history.cycles.isEmpty())
        assertEquals(setOf(1L), history.openExpenseIds)
        assertEquals(setOf(9L), history.openSettlementIds)
    }

    @Test
    fun what_came_after_the_last_zero_is_open() {
        val history = SettleUpCycles.of(
            listOf(expense(1, 10, 500), settlement(9, 11, -500), expense(2, 12, 300)),
        )

        assertEquals(setOf(1L), history.cycles.single().expenseIds)
        assertEquals(setOf(2L), history.openExpenseIds)
    }

    @Test
    fun each_settle_up_is_its_own_cycle_and_the_newest_comes_first() {
        val history = SettleUpCycles.of(
            listOf(
                expense(1, 10, 400), settlement(8, 11, -400),
                expense(2, 20, 250), settlement(9, 21, -250),
            ),
        )

        assertEquals(listOf(21L, 11L), history.cycles.map { it.closedOnDay })
        assertEquals(setOf(2L), history.cycles[0].expenseIds)
        assertEquals(setOf(1L), history.cycles[1].expenseIds)
        assertEquals("settlement-9", history.cycles[0].key)
    }

    @Test
    fun two_debts_that_cancel_are_settled_without_a_repayment() {
        // Lunch you paid, the cinema they paid — square is square.
        val history = SettleUpCycles.of(listOf(expense(1, 10, 500), expense(2, 11, -500)))

        assertEquals(setOf(1L, 2L), history.cycles.single().expenseIds)
        assertTrue(history.cycles.single().settlementIds.isEmpty())
    }

    @Test
    fun events_are_taken_in_date_order_not_the_order_given() {
        val history = SettleUpCycles.of(
            listOf(expense(2, 12, 300), settlement(9, 11, -500), expense(1, 10, 500)),
        )

        assertEquals(setOf(1L), history.cycles.single().expenseIds)
        assertEquals(setOf(2L), history.openExpenseIds)
    }

    @Test
    fun a_repayment_backdated_before_an_expense_does_not_settle_it() {
        // Recorded last, dated first: it settles what was owed on its own date.
        val history = SettleUpCycles.of(
            listOf(
                expense(1, day = 10, taka = 500, createdAt = 100),
                expense(2, day = 12, taka = 300, createdAt = 200),
                settlement(9, day = 11, taka = -500, createdAt = 300),
            ),
        )

        assertEquals(setOf(1L), history.cycles.single().expenseIds)
        assertEquals(11L, history.cycles.single().closedOnDay)
        assertEquals(setOf(2L), history.openExpenseIds)
    }

    @Test
    fun on_the_same_day_the_order_they_were_recorded_in_decides() {
        val history = SettleUpCycles.of(
            listOf(
                settlement(9, day = 10, taka = -500, createdAt = 50),
                expense(1, day = 10, taka = 500, createdAt = 10),
                expense(2, day = 10, taka = 200, createdAt = 90),
            ),
        )

        assertEquals(setOf(1L), history.cycles.single().expenseIds)
        assertEquals(setOf(2L), history.openExpenseIds)
    }
}
