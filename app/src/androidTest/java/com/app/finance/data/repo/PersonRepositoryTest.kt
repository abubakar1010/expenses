package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.domain.model.EntryError
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

/**
 * FR-SHR-01 — the people you split with.
 *
 * Against real in-memory SQLite with the canonical schema, like every other
 * repository suite: the behaviour that matters here is the unique index on
 * `name_key` and the `ON DELETE RESTRICT` foreign keys, and neither exists in a
 * mock.
 */
@RunWith(AndroidJUnit4::class)
class PersonRepositoryTest {

    private lateinit var fx: TestFixture

    @Before
    fun setUp() {
        fx = TestFixture()
    }

    @After
    fun tearDown() = fx.close()

    private suspend fun create(name: String): Long =
        (fx.people.findOrCreate(name) as SaveOutcome.Saved).id

    @Test
    fun three_spellings_of_one_name_resolve_to_a_single_person() = runBlocking {
        // The same rule income sources have (FR-IS-02), and it matters more
        // here: two Rahims means one balance split in half, each of them wrong,
        // and neither obviously so.
        val first = create("Rahim")
        val second = create(" rahim ")
        val third = create("RAHIM")

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, fx.people.all().size)
        // The first spelling is the one kept — later mentions find them, they
        // do not rename them.
        assertEquals("Rahim", fx.people.byId(first)?.name)
    }

    @Test
    fun a_blank_name_is_refused() = runBlocking {
        val outcome = fx.people.findOrCreate("   ")
        assertEquals(SaveOutcome.Rejected(EntryError.BLANK_NAME), outcome)
        assertTrue(fx.people.all().isEmpty())
    }

    @Test
    fun renaming_keeps_the_identity_and_moves_the_key() = runBlocking {
        val id = create("Rahim")
        assertTrue(fx.people.rename(id, "Rahim Uddin") is SaveOutcome.Saved)

        assertEquals("Rahim Uddin", fx.people.byId(id)?.name)
        // Finding by the old spelling now creates somebody new, which is right:
        // the name key moved with the rename.
        assertTrue(create("Rahim") != id)
    }

    @Test
    fun renaming_onto_an_existing_name_is_refused_rather_than_merging() = runBlocking {
        create("Rahim")
        val karim = create("Karim")

        // Merging two people would mean merging two balances, silently. The
        // unique index refuses and the repository says why.
        assertEquals(
            SaveOutcome.Rejected(EntryError.DUPLICATE_NAME),
            fx.people.rename(karim, "Rahim"),
        )
        assertEquals("Karim", fx.people.byId(karim)?.name)
    }

    @Test
    fun renaming_somebody_who_is_not_there_is_refused() = runBlocking {
        assertEquals(
            SaveOutcome.Rejected(EntryError.PERSON_NOT_FOUND),
            fx.people.rename(9_999L, "Ghost"),
        )
    }

    @Test
    fun archiving_hides_somebody_from_the_picker_without_losing_them() = runBlocking {
        val id = create("Rahim")
        assertTrue(fx.people.setArchived(id, archived = true) is SaveOutcome.Saved)

        assertTrue(fx.people.observeActive().first().none { it.id == id })
        // Still present, still findable, still carrying whatever they owe.
        assertNotNull(fx.people.byId(id))
        assertTrue(fx.people.observeAll().first().any { it.id == id })

        assertTrue(fx.people.setArchived(id, archived = false) is SaveOutcome.Saved)
        assertTrue(fx.people.observeActive().first().any { it.id == id })
    }

    @Test
    fun somebody_added_by_mistake_can_be_deleted() = runBlocking {
        // FR-IS-06's shape: the one delete the app allows is the one that
        // cannot rewrite history, because there is no history yet.
        val id = create("Typo")
        assertTrue(fx.people.delete(id) is SaveOutcome.Saved)
        assertNull(fx.people.byId(id))
    }

    @Test
    fun somebody_who_paid_for_something_cannot_be_deleted() = runBlocking {
        val rahim = create("Rahim")
        fx.db.openHelper.writableDatabase.execSQL(
            "UPDATE expense SET payer_person_id = $rahim WHERE id = " +
                insertExpense(250),
        )

        assertEquals(
            SaveOutcome.Rejected(EntryError.PERSON_HAS_HISTORY),
            fx.people.delete(rahim),
        )
        assertNotNull("the person was deleted out from under an expense", fx.people.byId(rahim))
    }

    @Test
    fun somebody_who_owes_you_cannot_be_deleted() = runBlocking {
        val rahim = create("Rahim")
        val expenseId = insertExpense(500)
        fx.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO expense_share (uuid, expense_id, person_id, share_minor, " +
                "created_at, updated_at) VALUES ('s1', $expenseId, $rahim, 25000, 1, 1)",
        )

        assertEquals(
            SaveOutcome.Rejected(EntryError.PERSON_HAS_HISTORY),
            fx.people.delete(rahim),
        )
    }

    @Test
    fun a_person_with_no_history_is_reported_as_having_none() = runBlocking {
        val id = create("Rahim")
        assertFalse(fx.db.personDao().hasHistory(id))
    }

    /** A posted expense to hang a share or a payer on. */
    private suspend fun insertExpense(taka: Long): Long =
        (
            fx.expenses.insert(
                amount = Money.ofTaka(taka),
                categoryId = fx.leafId("Grocery"),
                spentOn = fx.today,
            ) as SaveOutcome.Saved
            ).id
}
