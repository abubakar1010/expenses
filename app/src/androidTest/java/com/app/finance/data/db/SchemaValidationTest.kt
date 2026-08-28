package com.app.finance.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that Room accepts the hand-written canonical schema.
 *
 * This is the gate on the one genuinely risky decision in the persistence
 * layer. [AppDatabase.CanonicalSchema] drops the tables Room generated and
 * rebuilds them from [Schema] so that `CHECK` constraints, `WITHOUT ROWID`
 * storage and the functional index on `category` all exist. Room then validates
 * the schema it finds against the schema its entities describe on **every**
 * subsequent open — so a mismatch would not fail here in a test, it would fail
 * on the user's second launch, with their data already in the file.
 *
 * An in-memory database cannot catch this: it is discarded on close and never
 * reopened. This one is file-backed and deliberately opened twice.
 */
@RunWith(AndroidJUnit4::class)
class SchemaValidationTest {

    private val name = "schema-validation-test.db"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = context.deleteDatabase(name).let { }

    @After
    fun tearDown() = context.deleteDatabase(name).let { }

    @Test
    fun room_reopens_the_canonical_schema_without_complaint() = runBlocking {
        val first = AppDatabase.named(context, name)
        // Force the file to be created and the callback to run.
        val seeded = first.categoryDao().roots().size
        assertEquals(3, seeded)
        first.close()

        // The identity check and TableInfo validation both run here. If the
        // canonical DDL disagreed with the entity declarations in any way Room
        // inspects — column affinity, nullability, default, primary key,
        // foreign key or index — this line throws IllegalStateException.
        val second = AppDatabase.named(context, name)
        assertEquals(3, second.categoryDao().roots().size)
        // Against `Schema.VERSION`, not a literal: this assertion read "1" and
        // had to be edited by hand the first time the schema was ever versioned
        // up, which is a test that fails for bookkeeping rather than for a
        // defect.
        assertEquals(Schema.VERSION.toString(), second.appMetaDao().get("schema_version"))

        // And the canonical extras are still in place after the round trip.
        val categorySql = second.openHelper.writableDatabase
            .query("SELECT sql FROM sqlite_master WHERE type='index' AND name='ux_category_parent_key'")
            .use { if (it.moveToFirst()) it.getString(0) else null }
        assertTrue(
            "the functional unique index must survive reopen, got: $categorySql",
            categorySql?.contains("IFNULL") == true,
        )

        val triggers = second.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'")
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        // Counted from `Schema.TRIGGERS` rather than written out, for the same
        // reason as the version above: a literal here fails whenever a trigger
        // is legitimately added, which trains the reader to edit the number.
        assertEquals(
            "every trigger must survive reopen",
            Schema.TRIGGERS.size,
            triggers,
        )

        second.close()
    }

    @Test
    fun pragmas_are_applied_on_every_connection() = runBlocking {
        val db = AppDatabase.named(context, name)
        db.categoryDao().roots() // force open

        fun pragma(name: String): Long = db.openHelper.writableDatabase
            .query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else -1 }

        // 03 §4.1. foreign_keys in particular is off by default in SQLite, and
        // "archive, never delete" is only a guarantee while it is on.
        assertEquals("foreign_keys", 1L, pragma("foreign_keys"))
        assertEquals("synchronous NORMAL", 1L, pragma("synchronous"))
        assertEquals("cache_size", -2000L, pragma("cache_size"))

        val journal = db.openHelper.writableDatabase
            .query("PRAGMA journal_mode").use { if (it.moveToFirst()) it.getString(0) else "" }
        assertEquals("wal", journal.lowercase())

        db.close()
    }
}
