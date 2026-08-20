package com.app.finance.data.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RFC-4180 quoting — FR-DAT-02.
 *
 * Not academic. Notes are free text somebody typed, and "Rice, dal, oil" is
 * exactly the note they type. A naive join would shift every column after it by
 * one, in a file whose entire purpose is that the user can trust what came out.
 */
class CsvWriterTest {

    private fun escape(field: String?) = CsvWriter.escape(field)

    @Test
    fun `an ordinary field is written as it is`() {
        assertEquals("Grocery", escape("Grocery"))
        assertEquals("34000", escape("34000"))
    }

    @Test
    fun `a comma forces quotes`() {
        assertEquals("\"Rice, dal, oil\"", escape("Rice, dal, oil"))
    }

    @Test
    fun `a quote is doubled inside quotes`() {
        assertEquals("\"He said \"\"cheap\"\"\"", escape("""He said "cheap""""))
    }

    @Test
    fun `a bare quote with no comma still forces quotes`() {
        // Otherwise a reader hits an unbalanced quote mid-field and gives up on
        // the rest of the file.
        assertEquals("\"5\"\" pipe\"", escape("""5" pipe"""))
    }

    @Test
    fun `a newline forces quotes`() {
        assertEquals("\"two\nlines\"", escape("two\nlines"))
        assertEquals("\"two\r\nlines\"", escape("two\r\nlines"))
    }

    @Test
    fun `an absent value is an empty field, not the word null`() {
        assertEquals("", escape(null))
    }

    @Test
    fun `an empty string is an empty field`() {
        assertEquals("", escape(""))
    }

    // --- rows -----------------------------------------------------------------

    @Test
    fun `a row is comma separated and ends with CRLF`() {
        // Windows line endings, which is what every spreadsheet expects.
        assertEquals("1,Grocery,34000\r\n", CsvWriter.row(listOf("1", "Grocery", "34000")))
    }

    @Test
    fun `a row with a null leaves the column in place`() {
        assertEquals("1,,34000\r\n", CsvWriter.row(listOf("1", null, "34000")))
    }

    @Test
    fun `a row survives a note containing every troublesome character`() {
        val row = CsvWriter.row(listOf("1", """Rice, "good" quality
second line""", "34000"))
        assertEquals(
            "1,\"Rice, \"\"good\"\" quality\nsecond line\",34000\r\n",
            row,
        )
        // Three fields still, once a reader honours the quotes.
        assertEquals(2, row.count { it == ',' } - 1)
    }
}
