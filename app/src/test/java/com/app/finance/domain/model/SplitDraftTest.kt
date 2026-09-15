package com.app.finance.domain.model

import com.app.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SplitDraft] — the split sheet's transitions, on their own.
 *
 * `QuickAddViewModelTest` drives these through a ViewModel and a database; this
 * pins them where both the entry sheet and the repeating-entry editor
 * (FR-REC-06) now take them from, so a defect cannot be fixed on one screen
 * and survive on the other.
 */
class SplitDraftTest {

    private fun taka(n: Long) = Money.ofTaka(n)
    private val bill = taka(1_000)

    @Test
    fun an_untouched_draft_is_not_shared_and_leaves_the_whole_bill_to_you() {
        val draft = SplitDraft.NONE
        assertEquals(Split.NONE, draft.split(bill))
        assertEquals(bill, draft.yourShare(bill))
        assertNull(draft.yourShare(null))
    }

    @Test
    fun toggling_people_in_divides_the_bill_evenly_and_every_paisa_is_placed() {
        val draft = SplitDraft.NONE.toggle(1L).toggle(2L)

        assertEquals(SplitMode.EVEN, draft.mode)
        val split = draft.split(bill) as Split.YouPaid
        val yours = draft.yourShare(bill)!!
        assertEquals(bill.paisa, yours.paisa + split.owed.sumOf { it.amount.paisa })
        // Your share absorbs the rounding: ৳333.34 against two of ৳333.33.
        assertEquals(33_334L, yours.paisa)
    }

    @Test
    fun toggling_the_last_person_out_is_no_split_at_all() {
        val draft = SplitDraft.NONE.toggle(1L).toggle(1L)
        assertEquals(SplitMode.NONE, draft.mode)
        assertEquals(Split.NONE, draft.split(bill))
    }

    @Test
    fun switching_to_by_amount_starts_from_the_even_shares_and_keeps_membership() {
        val draft = SplitDraft.NONE.toggle(1L).toggle(2L).evenly(false, bill)

        assertEquals(SplitMode.CUSTOM, draft.mode)
        assertEquals(listOf(1L, 2L), draft.members)
        assertEquals(taka(1_000).paisa / 3, draft.owedBy(1L, bill)!!.paisa)
    }

    @Test
    fun clearing_a_typed_amount_keeps_the_person_in_the_split() {
        val draft = SplitDraft.NONE.toggle(1L).share(1L, taka(400)).share(1L, null)

        assertEquals(listOf(1L), draft.members)
        assertNull(draft.owedBy(1L, bill))
        assertEquals("nobody owes anything yet", Split.NONE, draft.split(bill))
    }

    @Test
    fun a_late_joiner_does_not_turn_a_typed_split_back_into_an_even_one() {
        val draft = SplitDraft.NONE.toggle(1L).share(1L, taka(400)).toggle(2L)
        assertEquals(SplitMode.CUSTOM, draft.mode)
        assertEquals(taka(400), draft.owedBy(1L, bill))
    }

    @Test
    fun choosing_someone_else_paid_drops_the_people_who_owed_you() {
        val draft = SplitDraft.NONE.toggle(1L).toggle(2L).paidByOther(true)

        assertEquals(SplitMode.THEY_PAID, draft.mode)
        assertTrue(draft.members.isEmpty())
        assertNull("who is asked second", draft.payerId)
        assertEquals(Split.NONE, draft.split(bill))
    }

    @Test
    fun re_choosing_the_arm_already_chosen_changes_nothing() {
        val paid = SplitDraft.NONE.paidBy(7L)
        assertSame(paid, paid.paidByOther(true))
    }

    @Test
    fun when_someone_else_paid_the_figure_typed_is_your_share() {
        val draft = SplitDraft.NONE.paidBy(7L)
        assertEquals(Split.TheyPaid(7L), draft.split(taka(300)))
        assertEquals(taka(300), draft.yourShare(taka(300)))
    }

    @Test
    fun tapping_the_payer_again_unpicks_them_and_keeps_the_arm() {
        val draft = SplitDraft.NONE.paidBy(7L).paidBy(7L)
        assertEquals(SplitMode.THEY_PAID, draft.mode)
        assertNull(draft.payerId)
    }

    @Test
    fun somebody_added_by_name_is_selected_on_whichever_arm_is_open() {
        assertEquals(listOf(5L), SplitDraft.NONE.including(5L).members)
        assertEquals(5L, SplitDraft.NONE.paidByOther(true).including(5L).payerId)
        val already = SplitDraft.NONE.toggle(5L)
        assertSame(already, already.including(5L))
    }

    @Test
    fun participants_name_everybody_on_either_arm() {
        assertEquals(setOf(1L, 2L), SplitDraft.NONE.toggle(1L).toggle(2L).participants)
        assertEquals(setOf(9L), SplitDraft.NONE.paidBy(9L).participants)
    }
}
