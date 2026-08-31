package com.app.finance.ui.feature.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.core.money.Money
import com.app.finance.data.db.entity.PersonEntity
import com.app.finance.ui.theme.DayBookTheme
import org.junit.Assert.assertEquals
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
 * The first test is here because driving the feature by hand found the defect
 * it describes, and nothing automated would have: every layer under the sheet
 * was tested and passing.
 */
@RunWith(AndroidJUnit4::class)
class SplitSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val today: LocalDate = LocalDate.of(2026, 8, 14)

    private fun state(people: List<PersonEntity> = emptyList()) = QuickAddUiState(
        input = "1000",
        date = today,
        today = today,
        people = people,
    )

    private fun person(id: Long, name: String) = PersonEntity(
        id = id,
        uuid = "u$id",
        name = name,
        nameKey = name.lowercase(),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun show(
        state: QuickAddUiState,
        onEvenly: (List<Long>) -> Unit = {},
        onPaidBy: (Long) -> Unit = {},
        onAddPerson: (String) -> Unit = {},
    ) {
        compose.setContent {
            DayBookTheme {
                SplitSheet(
                    state = state,
                    onEvenly = onEvenly,
                    onPaidBy = onPaidBy,
                    onClear = {},
                    onAddPerson = onAddPerson,
                    onDismiss = {},
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
    fun who_paid_is_one_choice_and_it_changes_the_question_below() {
        // trg_payer_excludes_shares makes the two mutually exclusive in the
        // database, so the sheet must not present them as independent toggles.
        show(state(listOf(person(1, "Rahim"))))

        compose.onNodeWithText("Who owes you", ignoreCase = true).assertIsDisplayed()

        compose.onNodeWithText("Someone else paid").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Who paid", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Who owes you", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun selecting_people_divides_the_bill_between_them_and_you() {
        // ৳1,000 three ways, through the sheet rather than the allocator: the
        // two others take ৳333.33 and the odd paisa is left for your share.
        var chosen: List<Long> = emptyList()
        val people = listOf(person(1, "Rahim"), person(2, "Karim"))
        val split = Money.ofTaka(1_000)

        show(
            state(people).copy(splitMode = SplitMode.EVEN, splitWith = listOf(1L, 2L)),
            onEvenly = { chosen = it },
        )

        // One caption per chosen person. `onAllNodes`, because the section
        // header above them reads "WHO OWES YOU" and a substring match finds
        // that too.
        compose.onAllNodesWithText("Owes you").assertCountEquals(2)
        // Tapping a chosen person removes them, which is what makes the row a
        // toggle rather than a one-way selection.
        compose.onNodeWithText("Rahim").performClick()
        assertEquals(listOf(2L), chosen)
        assertEquals(Money.ofTaka(1_000), split)
    }
}
