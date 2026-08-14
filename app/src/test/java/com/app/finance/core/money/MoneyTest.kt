package com.app.finance.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 04-system-architecture.md §9 targets ≥90% coverage on `core/`. Money is where
 * a silent bug is most expensive, so the cases below are deliberately literal.
 */
class MoneyTest {

    private val bd = Locale.Builder().setLanguage("en").setRegion("BD").build()
    private val us = Locale.US

    // --- arithmetic ---------------------------------------------------------

    @Test
    fun `addition and subtraction operate on paisa`() {
        assertEquals(Money(300), Money(100) + Money(200))
        assertEquals(Money(-100), Money(100) - Money(200))
    }

    @Test
    fun `negation flips sign`() {
        assertEquals(Money(-125075), -Money(125075))
    }

    @Test
    fun `zero is the additive identity`() {
        assertEquals(Money(4200), Money(4200) + Money.ZERO)
    }

    @Test
    fun `ofTaka scales by one hundred`() {
        assertEquals(Money(125000), Money.ofTaka(1250))
    }

    @Test
    fun `comparison orders by paisa`() {
        assertTrue(Money(100) < Money(200))
        assertTrue(Money(-100) < Money.ZERO)
    }

    /**
     * Safe-to-spend divides a remaining limit by days left. Rounding must go
     * toward zero: telling the user they may spend more than they may is the
     * wrong direction to err.
     */
    @Test
    fun `divideBy rounds toward zero`() {
        assertEquals(Money(333), Money(1000).divideBy(3))
        assertEquals(Money(-333), Money(-1000).divideBy(3))
    }

    // --- formatting (05 §4.3) ----------------------------------------------

    @Test
    fun `decimals are hidden when the value is whole taka`() {
        assertEquals("৳1,250", Money(125000).format(us))
    }

    @Test
    fun `paisa are shown only when non-zero`() {
        assertEquals("৳1,250.75", Money(125075).format(us))
    }

    @Test
    fun `bangladeshi locale uses south asian grouping`() {
        // The mock in 05 §5.7 reads ৳5,84,000 — not ৳584,000.
        assertEquals("৳5,84,000", Money.ofTaka(584_000).format(bd))
    }

    @Test
    fun `western locale groups in threes`() {
        assertEquals("৳584,000", Money.ofTaka(584_000).format(us))
    }

    @Test
    fun `negatives use a true minus sign not a hyphen`() {
        val formatted = Money.ofTaka(-340).format(us)
        assertEquals("−৳340", formatted)
        assertTrue("must not contain a hyphen-minus", !formatted.contains('-'))
    }

    @Test
    fun `south asian grouping is three digits then twos`() {
        // DecimalFormat cannot express this — it supports one grouping size —
        // which is why the grouping is done by hand.
        assertEquals("৳999", Money.ofTaka(999).format(bd))
        assertEquals("৳1,000", Money.ofTaka(1_000).format(bd))
        assertEquals("৳99,999", Money.ofTaka(99_999).format(bd))
        assertEquals("৳1,00,000", Money.ofTaka(100_000).format(bd))
        assertEquals("৳1,23,45,678", Money.ofTaka(12_345_678).format(bd))
    }

    @Test
    fun `western grouping stays in threes at the same magnitudes`() {
        assertEquals("৳100,000", Money.ofTaka(100_000).format(us))
        assertEquals("৳12,345,678", Money.ofTaka(12_345_678).format(us))
    }

    @Test
    fun `grouping and paisa combine`() {
        assertEquals("৳5,84,000.50", Money(58_400_050).format(bd))
    }

    @Test
    fun `large values are never abbreviated`() {
        // "Never abbreviate to 1.2k. In a ledger, precision is the product."
        assertEquals("৳12,00,000", Money.ofTaka(1_200_000).format(bd))
    }

    // --- spoken form (05 §10) ----------------------------------------------

    @Test
    fun `spoken form reads the sample from the guide`() {
        assertEquals("one thousand two hundred fifty taka", Money(125000).spokenForm(us))
    }

    @Test
    fun `spoken form uses the south asian scale where grouping does`() {
        assertEquals(
            "five lakh eighty four thousand taka",
            Money.ofTaka(584_000).spokenForm(bd),
        )
    }

    @Test
    fun `spoken form uses the western scale elsewhere`() {
        assertEquals("five hundred eighty four thousand taka", Money.ofTaka(584_000).spokenForm(us))
    }

    @Test
    fun `spoken form announces sign and paisa`() {
        assertEquals("minus three hundred forty taka fifty paisa", Money(-34050).spokenForm(us))
    }

    @Test
    fun `spoken form of zero`() {
        assertEquals("zero taka", Money.ZERO.spokenForm(us))
    }

    @Test
    fun `spoken form handles the teens`() {
        assertEquals("nineteen taka", Money.ofTaka(19).spokenForm(us))
        assertEquals("seventeen lakh taka", Money.ofTaka(1_700_000).spokenForm(bd))
    }

    // --- parsing (custom keypad input) -------------------------------------

    @Test
    fun `parses plain digits`() {
        assertEquals(Money(125000), Money.parseOrNull("1250"))
    }

    @Test
    fun `parses a decimal`() {
        assertEquals(Money(125075), Money.parseOrNull("1250.75"))
    }

    @Test
    fun `pads a single decimal place`() {
        assertEquals(Money(125070), Money.parseOrNull("1250.7"))
    }

    @Test
    fun `truncates beyond two decimal places`() {
        assertEquals(Money(125078), Money.parseOrNull("1250.789"))
    }

    @Test
    fun `parses a leading true minus for refunds`() {
        assertEquals(Money(-125000), Money.parseOrNull("−1250"))
    }

    @Test
    fun `partial input during typing returns null rather than throwing`() {
        assertNull(Money.parseOrNull(""))
        assertNull(Money.parseOrNull("."))
        assertNull(Money.parseOrNull("−"))
        assertNull(Money.parseOrNull("12.3.4"))
        assertNull(Money.parseOrNull("12a"))
    }
}
