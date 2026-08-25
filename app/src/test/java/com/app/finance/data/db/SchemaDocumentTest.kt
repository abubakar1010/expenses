package com.app.finance.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/schema_v1.sql` says of itself:
 *
 * > "Generated from app/src/main/java/com/app/finance/data/db/Schema.kt, which
 * > is what actually creates the database at runtime. **Regenerate both
 * > together.**"
 *
 * There is no generator, there never was, and nothing checked. The file is
 * normative — `03-database-design.md` points at it, and CLAUDE.md tells anyone
 * changing the schema to regenerate it — so a drift between the two is the
 * documentation being wrong about the database in the one place a reader would
 * trust it most. §22's own trigger additions had to be applied to it by hand,
 * which is exactly the moment such a file starts to rot.
 *
 * This is a JVM test and not an instrumented one on purpose. [Schema] is plain
 * Kotlin with a single import (`NameKey`), so the comparison costs milliseconds
 * and runs on every `testDebugUnitTest` — including in the pre-merge gate, where
 * the instrumented suite does not run at all.
 *
 * **What is compared is the DDL, not the file.** The document is half prose:
 * a header explaining the conventions, per-section commentary, and the rebuild
 * queries at the end left commented out as an oracle. Comparing bytes would
 * force that commentary to be deleted to make a test pass, which is the wrong
 * trade. So every `CREATE TABLE`, `CREATE INDEX` and `CREATE TRIGGER` in the
 * file is parsed out, whitespace-normalised, and matched against [Schema] as a
 * set.
 */
class SchemaDocumentTest {

    private val document: String by lazy {
        // `testDebugUnitTest` runs with `app/` as the working directory, but
        // that is a Gradle detail rather than a promise; walking up until the
        // repository root appears is stable under both Gradle and an IDE.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/schema_v1.sql") }
            .firstOrNull { it.isFile }
        requireNotNull(found) { "docs/schema_v1.sql not found from ${File("").absolutePath}" }
        found.readText()
    }

    @Test
    fun every_table_in_the_schema_is_in_the_document_exactly_as_written() {
        assertMatches("CREATE TABLE", Schema.TABLES)
    }

    @Test
    fun every_index_in_the_schema_is_in_the_document_exactly_as_written() {
        assertMatches("CREATE INDEX", Schema.INDICES, alsoAccept = "CREATE UNIQUE INDEX")
    }

    @Test
    fun every_trigger_in_the_schema_is_in_the_document_exactly_as_written() {
        // The set that matters most. Triggers are the sole writer of the rollup
        // tables (03 §1), so a trigger the document does not describe is an
        // aggregate nobody reading the document knows is maintained.
        assertMatches("CREATE TRIGGER", Schema.TRIGGERS)
    }

    @Test
    fun the_document_records_the_schema_version_the_code_declares() {
        assertTrue(
            "the document does not name version ${Schema.VERSION}",
            document.contains("version ${Schema.VERSION}"),
        )
    }

    @Test
    fun the_parser_is_actually_finding_statements() {
        // A parser that silently matched nothing would make all four tests
        // above pass while comparing empty set to empty set — which is the way
        // a gate like this usually fails to be one.
        assertEquals(Schema.TABLES.size, statementsOf("CREATE TABLE").size)
        assertEquals(Schema.TRIGGERS.size, statementsOf("CREATE TRIGGER").size)
        assertTrue(Schema.TABLES.isNotEmpty() && Schema.TRIGGERS.isNotEmpty())
    }

    // --- internals ------------------------------------------------------------

    private fun assertMatches(kind: String, expected: List<String>, alsoAccept: String? = null) {
        val documented = statementsOf(kind, alsoAccept).map(::normalise).toSet()
        val declared = expected.map(::normalise).toSet()

        val missing = (declared - documented).sorted()
        val extra = (documented - declared).sorted()

        assertEquals(
            "docs/schema_v1.sql has drifted from Schema.kt.\n" +
                "In Schema.kt but not documented:\n" + missing.joinToString("\n\n") + "\n\n" +
                "Documented but not in Schema.kt:\n" + extra.joinToString("\n\n") + "\n",
            declared,
            documented,
        )
    }

    private fun statementsOf(kind: String, alsoAccept: String? = null): List<String> =
        parse(document).filter { statement ->
            statement.startsWith(kind, ignoreCase = true) ||
                (alsoAccept != null && statement.startsWith(alsoAccept, ignoreCase = true))
        }

    /**
     * Splits the document into statements.
     *
     * A trigger body holds semicolons of its own, so a naive split on `;` would
     * cut every trigger into pieces and then compare the pieces — passing for
     * the wrong reason. Inside a `CREATE TRIGGER`, the statement ends at `END;`
     * and nowhere else.
     */
    private fun parse(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = mutableListOf<String>()
        var inTrigger = false

        sql.lineSequence().forEach { raw ->
            val line = raw.substringBefore("--").trim()
            if (line.isEmpty()) return@forEach
            current += line
            val joined = current.joinToString(" ")
            if (joined.startsWith("CREATE TRIGGER", ignoreCase = true)) inTrigger = true

            val complete = if (inTrigger) END.containsMatchIn(line) else line.endsWith(";")
            if (complete) {
                statements += joined
                current.clear()
                inTrigger = false
            }
        }
        return statements
    }

    /**
     * Reduces a statement to the object it defines.
     *
     * `IF NOT EXISTS` is normalised away, and that is the only difference the
     * two are allowed to have. [Schema] needs it because `AppDatabase` replays
     * this DDL over tables Room has just created; the document is the schema as
     * a reader would write it out, and 03-database-design.md quotes from it. A
     * gate that failed on that would be pushing a build detail into a document
     * about the database — but a gate that normalised more than this would stop
     * being one.
     */
    private fun normalise(statement: String): String =
        statement
            .replace(WHITESPACE, " ")
            .replace(IF_NOT_EXISTS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .removeSuffix(";")
            .trim()

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        val IF_NOT_EXISTS = Regex("""\bIF\s+NOT\s+EXISTS\b""", RegexOption.IGNORE_CASE)
        val END = Regex("""\bEND\s*;$""", RegexOption.IGNORE_CASE)
    }
}
