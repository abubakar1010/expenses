package com.app.finance.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Locale

/**
 * These assertions are what the unique indexes `ux_income_source_key` and
 * `ux_category_parent_key` actually rest on. If normalisation is wrong, the
 * indexes are decoration.
 */
class NameKeyTest {

    @Test
    fun `case and surrounding whitespace collapse together`() {
        // FR-IS-02: "Salary", "salary " and "Salary" are one source.
        val expected = "salary"
        assertEquals(expected, NameKey.of("Salary"))
        assertEquals(expected, NameKey.of("salary "))
        assertEquals(expected, NameKey.of("  SALARY  "))
    }

    @Test
    fun `internal whitespace collapses to a single space`() {
        assertEquals("house rent", NameKey.of("House    Rent"))
        assertEquals("house rent", NameKey.of("House\tRent"))
        assertEquals("house rent", NameKey.of("House\n Rent"))
    }

    @Test
    fun `distinct names stay distinct`() {
        assertFalse(NameKey.sameAs("Grocery", "Groceries"))
    }

    // --- the reason this is not done in SQL --------------------------------

    @Test
    fun `bengali names normalise rather than passing through untouched`() {
        // SQLite's LOWER() is ASCII-only, so it would leave these alone and let
        // both through the unique index. 03-database-design.md §4.2.
        assertEquals("বাড়ি ভাড়া", NameKey.of("  বাড়ি   ভাড়া  "))
        assertTrue(NameKey.sameAs("বাড়ি ভাড়া", " বাড়ি  ভাড়া "))
    }

    @Test
    fun `composed and decomposed bengali forms produce the same key`() {
        // These render identically but encode differently; without NFC they
        // would be two rows the index believes are unrelated.
        val composed = "আমার বাড়ি"
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertTrue(
            "NFD input must fold onto the same key as NFC",
            NameKey.sameAs(composed, decomposed),
        )
    }

    @Test
    fun `folding does not depend on the device locale`() {
        // Turkish lowercases 'I' to a dotless 'ı'. Folding with the device
        // locale would make the same name key differently on a Turkish phone
        // and break the constraint after a locale change or an import.
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("internet", NameKey.of("INTERNET"))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `blank detection sees through whitespace`() {
        assertTrue(NameKey.isBlank("   "))
        assertTrue(NameKey.isBlank(""))
        assertFalse(NameKey.isBlank(" Salary "))
    }

    @Test
    fun `the same leaf name under two roots is not a collision here`() {
        // FR-CAT-07 permits both "Fixed → Misc" and "Variable → Misc"; scoping
        // is the index's job via IFNULL(parent_id, -1), not this function's.
        assertEquals(NameKey.of("Misc"), NameKey.of("misc"))
    }
}
