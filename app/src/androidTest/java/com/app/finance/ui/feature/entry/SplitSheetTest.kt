package com.app.finance.ui.feature.entry

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.core.money.Money
import com.app.finance.data.db.entity.PersonEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Split
import com.app.finance.ui.theme.DayBookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The split sheet — FR-SHR-01, FR-SHR-02, FR-SHR-03.
 *
 * Rendered directly rather than through `QuickAddSheet`, because what is being
 * tested is what this sheet *offers*, and every state worth testing is a
 * `QuickAddUiState` that can simply be constructed.
 *
 * Several of these are here because driving the feature by hand found the
 * defect they describe and nothing automated would have: every layer under the
 * sheet was tested and passing while the sheet itself was unusable. The sheet
 * is stateless, so a test drives it by moving [current] — which is also the
 * fix under test, since the state these controls change used to be local to
 * the sheet and out of everybody's reach, including its own.
 */
@RunWith(AndroidJUnit4::class)
class SplitSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val today: LocalDate = LocalDate.of(2026, 8, 14)

    /** The rendered state, movable mid-test. Set by [show]. */
    private lateinit var current: MutableState<QuickAddUiState>

    private fun state(people: List<PersonEntity> = emptyList()) = QuickAddUiState(
        input = "1000",
        date = today,
        today = today,
        people = people,
    )

    private fun person(id: Long, name: String, archived: Boolean = false) = PersonEntity(
        id = id,
        uuid = "u$id",
        name = name,
        nameKey = name.lowercase(),
        isArchived = archived,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun moveTo(next: QuickAddUiState) {
        compose.runOnUiThread { current.value = next }
        compose.waitForIdle()
    }

    private fun show(
        state: QuickAddUiState,
        onTogglePerson: (Long) -> Unit = {},
        onPaidBy: (Long) -> Unit = {},
        onPaidByOther: (Boolean) -> Unit = {},
        onSplitEvenly: (Boolean) -> Unit = {},
        onShare: (Long, Money?) -> Unit = { _, _ -> },
        onClear: () -> Unit = {},
        onAddPerson: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            val held = remember { mutableStateOf(state) }
            current = held
            DayBookTheme {
                SplitSheet(
                    state = held.value,
                    onTogglePerson = onTogglePerson,
                    onPaidBy = onPaidBy,
                    onPaidByOther = onPaidByOther,
                    onSplitEvenly = onSplitEvenly,
                    onShare = onShare,
                    onClear = onClear,
                    onAddPerson = onAddPerson,
                    onDismiss = onDismiss,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun an_empty_list_offers_a_way_out_of_itself() {
        // The defect this test exists for, found by driving the feature on a
        // device: the sheet said "add a person to start" and offered no way to.
        // The only route was backing out to the People screen, which loses the
        // amount already typed — and every test under this passed, because the
        // dead end was in what the sheet did *not* render.
        //
        // FR-IS-03 made the same argument for income sources: created inline,
        // "without a separate navigation step".
        show(state())

        compose.onNodeWithText("Nobody to split with yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun typing_a_name_and_adding_reports_it() {
        var added: String? = null
        show(state(), onAddPerson = { added = it })

        compose.onNodeWithText("Name").performTextInput("Rahim")
        compose.onNodeWithText("Add").performClick()

        assertEquals("Rahim", added)
    }

    @Test
    fun the_add_control_is_there_even_when_people_already_are() {
        // Not only on the empty state: the person you need is most often the
        // one you have not added yet, and that is as true on the fourth split
        // as on the first.
        show(state(listOf(person(1, "Rahim"))))

        compose.onNodeWithText("Rahim").assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun a_refused_name_says_so_on_this_sheet() {
        // The entry sheet renders `state.error`, and it is behind a modal
        // scrim while this one is open — so a blank name, or the name of
        // somebody archived, was refused in silence.
        show(state().copy(error = EntryError.PERSON_ARCHIVED))

        compose.onNodeWithText("archived", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }

    @Test
    fun done_closes_the_sheet() {
        // It was a bare `Text` with no `clickable` on it: it looked exactly
        // like the button every other sheet here closes with, and did nothing.
        var dismissed = false
        show(state(listOf(person(1, "Rahim"))), onDismiss = { dismissed = true })

        compose.onNodeWithText("Done").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun un_sharing_costs_one_tap_and_is_offered_only_when_there_is_something_to_undo() {
        // `LedgerFilterSheet`'s Clear/Done pair. Picking four people and then
        // deciding the dinner was yours meant four more taps to unpick them,
        // one row at a time.
        var cleared = false
        val people = listOf(person(1, "Rahim"))
        show(state(people), onClear = { cleared = true })

        compose.onNodeWithText("Not shared").assertDoesNotExist()

        moveTo(state(people).copy(splitMode = SplitMode.EVEN, splitWith = listOf(1L)))

        compose.onNodeWithText("Not shared").performClick()
        assertTrue(cleared)
    }

    @Test
    fun who_paid_is_one_choice_and_it_changes_the_question_below() {
        // trg_payer_excludes_shares makes the two mutually exclusive in the
        // database, so the sheet must not present them as independent toggles.
        val people = listOf(person(1, "Rahim"))
        show(state(people))
        compose.onNodeWithText("Who owes you", ignoreCase = true).assertIsDisplayed()

        moveTo(state(people).copy(splitMode = SplitMode.THEY_PAID))

        compose.onNodeWithText("Who paid", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Who owes you", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun choosing_someone_else_paid_reports_it_rather_than_undoing_itself() {
        // The defect: the arm was a `remember(state.splitMode)` local, and
        // choosing it called `onClear`, which moved `splitMode` and rebuilt the
        // local as `false` in the same frame. From any split that already had
        // people in it, "Someone else paid" was unreachable — the chip flashed
        // and snapped straight back to "I paid".
        var theyPaid: Boolean? = null
        show(
            state(listOf(person(1, "Rahim")))
                .copy(splitMode = SplitMode.EVEN, splitWith = listOf(1L)),
            onPaidByOther = { theyPaid = it },
        )

        compose.onNodeWithText("Someone else paid").performClick()

        assertEquals(true, theyPaid)
    }

    @Test
    fun the_arm_survives_the_state_change_it_causes() {
        // The other half of the same defect, from the sheet's side: once the
        // ViewModel has moved to THEY_PAID, the question below must stay
        // "Who paid" rather than reverting.
        val people = listOf(person(1, "Rahim"))
        show(state(people).copy(splitMode = SplitMode.EVEN, splitWith = listOf(1L)))

        moveTo(state(people).copy(splitMode = SplitMode.THEY_PAID))
        moveTo(state(people).copy(splitMode = SplitMode.THEY_PAID, payerId = 1L))

        compose.onNodeWithText("Who paid", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("You owe them").assertIsDisplayed()
    }

    @Test
    fun selecting_people_divides_the_bill_between_them_and_you() {
        // ৳1,000 three ways, through the sheet rather than the allocator: the
        // two others take ৳333.33 and the odd paisa is left for your share.
        var toggled: Long? = null
        val people = listOf(person(1, "Rahim"), person(2, "Karim"))

        show(
            state(people).copy(splitMode = SplitMode.EVEN, splitWith = listOf(1L, 2L)),
            onTogglePerson = { toggled = it },
        )

        // One caption per chosen person. `onAllNodes`, because the section
        // header above them reads "WHO OWES YOU" and a substring match finds
        // that too.
        compose.onAllNodesWithText("Owes you").assertCountEquals(2)
        // Tapping a chosen person removes them, which is what makes the row a
        // toggle rather than a one-way selection.
        compose.onNodeWithText("Rahim").performClick()
        assertEquals(1L, toggled)
    }

    @Test
    fun the_two_styles_are_offered_once_somebody_is_in_the_split() {
        // FR-SHR-02: "either evenly or by an amount typed per person". The
        // second half had no control at all — `SplitMode.CUSTOM` existed on the
        // ViewModel and nothing in the app could reach it.
        var evenly: Boolean? = null
        val people = listOf(person(1, "Rahim"))
        show(state(people), onSplitEvenly = { evenly = it })

        compose.onNodeWithText("By amount").assertDoesNotExist()

        moveTo(state(people).copy(splitMode = SplitMode.EVEN, splitWith = listOf(1L)))

        compose.onNodeWithText("By amount").performClick()
        assertEquals(false, evenly)
    }

    @Test
    fun a_hand_typed_share_reports_the_money_it_parses_to() {
        var got: Pair<Long, Money?>? = null
        show(
            state(listOf(person(1, "Rahim"))).copy(
                splitMode = SplitMode.CUSTOM,
                splitWith = listOf(1L),
                customOwed = listOf(Split.Owed(1L, Money.ofTaka(400))),
            ),
            onShare = { id, amount -> got = id to amount },
        )

        compose.onNodeWithText("400").performTextReplacement("250")

        assertEquals(1L, got?.first)
        assertEquals(Money.ofTaka(250), got?.second)
    }

    @Test
    fun clearing_a_hand_typed_share_reports_nothing_rather_than_zero() {
        // `CHECK (share_minor > 0)` refuses a zero share, so a half-cleared
        // field must leave the person in the split without an amount rather
        // than put a zero one on them.
        var got: Money? = Money.ofTaka(1)
        show(
            state(listOf(person(1, "Rahim"))).copy(
                splitMode = SplitMode.CUSTOM,
                splitWith = listOf(1L),
                customOwed = listOf(Split.Owed(1L, Money.ofTaka(400))),
            ),
            onShare = { _, amount -> got = amount },
        )

        compose.onNodeWithText("400").performTextClearance()

        assertNull(got)
    }

    @Test
    fun somebody_archived_is_hidden_unless_this_split_already_names_them() {
        // FR-CAT-08's rule, applied to people — and its exception. Archiving
        // does not unpick the expenses somebody is in, so reopening one has to
        // keep showing them or the save rewrites a share the user never saw.
        val people = listOf(person(1, "Rahim"), person(2, "Karim", archived = true))
        show(state(people))

        compose.onNodeWithText("Karim").assertDoesNotExist()

        moveTo(state(people).copy(splitMode = SplitMode.EVEN, splitWith = listOf(2L)))

        compose.onNodeWithText("Karim").assertIsDisplayed()
    }
}
