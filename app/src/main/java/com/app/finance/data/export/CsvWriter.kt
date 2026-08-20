package com.app.finance.data.export

/**
 * RFC-4180 field quoting — FR-DAT-02's half of the export.
 *
 * A helper rather than a dependency. The whole problem is three rules: a field
 * containing a comma, a quote or a line break is wrapped in quotes, and a quote
 * inside a quoted field is doubled. A library to do that would be more bytes
 * than the app's entire domain layer, against a 6 MB budget (NFR-SIZE-01).
 *
 * The rules are not academic here. Notes are free text the user typed, and
 * "Rice, dal, oil" is exactly the note somebody writes — a naive join would
 * silently shift every column after it by one, in a file whose whole purpose is
 * that the user can trust what came out.
 */
object CsvWriter {

    /** Windows line endings, which is what every spreadsheet expects. */
    const val EOL = "\r\n"

    fun row(fields: List<String?>): String =
        fields.joinToString(separator = ",", postfix = EOL) { escape(it) }

    fun escape(field: String?): String {
        // An absent value is an empty field, not the four letters "null".
        if (field == null) return ""
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return field
        return buildString(field.length + 2) {
            append('"')
            field.forEach { c ->
                if (c == '"') append('"')
                append(c)
            }
            append('"')
        }
    }
}
