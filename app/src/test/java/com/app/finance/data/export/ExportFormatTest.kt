package com.app.finance.data.export

import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file format itself — FR-DAT-01, and DR-01's reach into it.
 *
 * The round trip is the point: a file this app writes is a file it must be able
 * to read back years later, and every assertion here is about that promise
 * rather than about the serialiser.
 */
class ExportFormatTest {

    private val codec = DayBookExport.CODEC

    private fun sample() = DayBookExport(
        schemaVersion = 1,
        exportedAt = 1_755_000_000_000L,
        categories = listOf(
            CategoryDto(1, "cat-1", null, "Variable Expenses", "variable expenses", 1, isSystem = true, createdAt = 1, updatedAt = 1),
            CategoryDto(2, "cat-2", 1, "Grocery", "grocery", 1, createdAt = 1, updatedAt = 1),
        ),
        sources = listOf(SourceDto(1, "src-1", "Salary", "salary", 0, createdAt = 1, updatedAt = 1)),
        budgets = listOf(BudgetDto(1, "bud-1", 2, 202608, 1_800_000, 1, 1)),
        expenses = listOf(
            ExpenseDto(
                id = 1, uuid = "exp-1", categoryId = 2, amountMinor = 34_000,
                spentOn = 20_678, periodYm = 202608, paymentMethod = 0,
                note = "Rice, dal", status = 0, createdAt = 1, updatedAt = 1,
            ),
        ),
        incomeEntries = listOf(
            IncomeEntryDto(1, "inc-1", 1, 3_000_000, 20_666, 202608, null, 0, 1, 1),
        ),
        rules = listOf(
            RuleDto(1, "rule-1", 0, 2, null, 1_500_000, 0, 31, 20_700, null, false, true, null, 1, 1),
        ),
        meta = listOf(MetaDto("schema_version", "1", 1)),
    )

    // --- the round trip -------------------------------------------------------

    @Test
    fun `everything written comes back identical`() {
        val original = sample()
        val text = codec.encodeToString(DayBookExport.serializer(), original)
        assertEquals(original, codec.decodeFromString(DayBookExport.serializer(), text))
    }

    @Test
    fun `the row count covers every entity`() {
        assertEquals(2 + 1 + 1 + 1 + 1 + 1 + 1, sample().rowCount)
    }

    // --- DR-01, which does not stop at the database ---------------------------

    @Test
    fun `no amount is written as a floating-point number`() {
        // "Floating-point representation of money is prohibited anywhere in the
        // system, **including export files**." A JSON number that round-trips
        // through a Double loses taka at scale, and an export that quietly
        // changes the figures is worse than no export at all.
        val text = codec.encodeToString(DayBookExport.serializer(), sample())

        listOf("amount_minor", "limit_minor").forEach { field ->
            Regex("\"$field\":([^,}]+)").findAll(text).forEach { match ->
                val value = match.groupValues[1]
                assertFalse("$field was written as $value", value.contains('.'))
                assertFalse("$field was written as $value", value.contains('e', ignoreCase = true))
            }
        }
    }

    @Test
    fun `paisa survive a value larger than a Double can hold exactly`() {
        // 2^53 + 1. A serialiser that went through Double would give back an
        // even number.
        val odd = 9_007_199_254_740_993L
        val text = codec.encodeToString(
            DayBookExport.serializer(),
            sample().copy(expenses = listOf(sample().expenses.first().copy(amountMinor = odd))),
        )
        val back = codec.decodeFromString(DayBookExport.serializer(), text)
        assertEquals(odd, back.expenses.single().amountMinor)
    }

    // --- forward compatibility ------------------------------------------------

    @Test
    fun `a file from a newer build with extra fields still reads`() {
        // FR-DAT-05 refuses a newer *schema version*; a same-version file with
        // a column this build has never heard of is a file it can still read,
        // and refusing it would make every future release break the last one.
        val text = """
            {"schema_version":1,"exported_at":1,"invented_by_a_later_build":true,
             "expenses":[{"id":1,"uuid":"e","category_id":2,"amount_minor":100,
                          "spent_on":1,"period_ym":202608,"created_at":1,
                          "updated_at":1,"colour_the_user_picked":"blue"}]}
        """.trimIndent()

        val back = codec.decodeFromString(DayBookExport.serializer(), text)
        assertEquals(1, back.expenses.size)
        assertEquals(100L, back.expenses.single().amountMinor)
    }

    @Test
    fun `absent entities decode as empty rather than failing`() {
        val back = codec.decodeFromString(
            DayBookExport.serializer(),
            """{"schema_version":1,"exported_at":1}""",
        )
        assertEquals(0, back.rowCount)
    }

    @Test
    fun `a file that is not an export is refused rather than half-read`() {
        val strict = Json { ignoreUnknownKeys = true }
        val failed = runCatching {
            strict.decodeFromString(DayBookExport.serializer(), """{"hello":"world"}""")
        }
        assertTrue("a file with no schema_version must not parse", failed.isFailure)
    }

    // --- the file stays small -------------------------------------------------

    @Test
    fun `defaults and nulls are left out`() {
        // On nine thousand expenses this is most of the file: `status`,
        // `payment_method` and an absent note are the common case.
        val text = codec.encodeToString(
            DayBookExport.serializer(),
            sample().copy(
                expenses = listOf(
                    ExpenseDto(
                        id = 1, uuid = "e", categoryId = 2, amountMinor = 100,
                        spentOn = 1, periodYm = 202608, paymentMethod = 0,
                        note = null, status = 0, createdAt = 1, updatedAt = 1,
                    ),
                ),
            ),
        )
        assertFalse(text.contains("\"note\""))
        assertFalse(text.contains("\"status\":0"))
    }

    // --- the natural key (A1) -------------------------------------------------

    @Test
    fun `a category's natural key is what its unique index enforces`() {
        // `ux_category_parent_key` indexes IFNULL(parent_id, -1) and name_key,
        // so two roots may not share a name and neither may two children of one
        // parent — but a root and a child may.
        val root = sample().categories.first()
        val child = sample().categories.last()

        assertEquals("-1/variable expenses", root.naturalKey)
        assertEquals("1/grocery", child.naturalKey)
    }

    @Test
    fun `a source's natural key is its name key`() {
        assertEquals("salary", sample().sources.single().naturalKey)
    }

    @Test
    fun `a budget's natural key is its category and period`() {
        // FR-BUD-02 — one limit per (category, period) — which is exactly what
        // `ux_budget_cat_period` enforces.
        assertEquals("2/202608", sample().budgets.single().naturalKey)
    }

    @Test
    fun `transactional rows have no natural key at all`() {
        // And that is a fact about the data rather than an omission: two
        // identical expenses on one day are two expenses, and FR-IE-02 says the
        // same of income. Only the uuid can identify them.
        assertNull(sample().expenses.single().naturalKey)
        assertNull(sample().incomeEntries.single().naturalKey)
        assertNull(sample().rules.single().naturalKey)
    }

    @Test
    fun `two phones agree on a category even when their uuids differ`() {
        // The whole point. Every seeded row's uuid comes from `randomblob` at
        // install, so this is the ordinary cross-device case rather than an
        // exotic one.
        val here = sample().categories.last()
        val there = here.copy(id = 77, uuid = "a-different-install")

        assertNotEquals(here.uuid, there.uuid)
        assertEquals(here.naturalKey, there.naturalKey)
    }
}
