package com.app.finance.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations, walked end to end from a version-1 file.
 *
 * The risk here is not shared by the rest of the app. Everything else meets
 * only fresh databases; this runs against a file that already holds somebody's
 * five years, and 03 §8 removes the escape hatch — a release build never falls
 * back to destructive migration, so a mistake lands the user on
 * `RecoveryScreen` rather than being papered over by a wipe.
 *
 * **Opening through Room is the assertion, not the setup.** Room compares every
 * table against its entities after a migration — and *only* after a migration;
 * an ordinary open checks an identity hash and skips the comparison entirely.
 * That asymmetry is why the `notnull` mismatch [Migrations.MIGRATION_1_2]
 * repairs survived from M1 to here without anything noticing.
 *
 * **The v1 fixture.** [Schema.TABLES] minus what version 3 added, with
 * `payer_person_id` stripped and `NOT NULL` taken back off the keys — the two
 * shapes this pair of migrations actually turns on are stated explicitly, while
 * the untouched tables come from the schema so the fixture cannot rot into
 * something Room rejects for unrelated reasons.
 */
@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {

    private val name = "schema-migration-test.db"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = context.deleteDatabase(name).let { }

    @After
    fun tearDown() = context.deleteDatabase(name).let { }

    @Test
    fun a_v1_ledger_survives_both_migrations_intact() {
        withV1Database { db ->
            db.execSQL(
                "INSERT INTO category (id, uuid, parent_id, name, name_key, nature, " +
                    "is_system, is_archived, sort_order, created_at, updated_at) " +
                    "VALUES (1, 'cat-uuid', NULL, 'Food', 'food', 1, 1, 0, 0, 100, 100)",
            )
            db.execSQL(
                "INSERT INTO expense (id, uuid, category_id, amount_minor, spent_on, " +
                    "period_ym, payment_method, note, status, created_at, updated_at) " +
                    "VALUES (7, 'exp-uuid', 1, 125000, 20680, 202608, 0, 'dinner', 0, 100, 100)",
            )
        }

        val migrated = AppDatabase.named(context, name)
        try {
            val db = migrated.openHelper.writableDatabase

            db.query("SELECT amount_minor, note, payer_person_id FROM expense WHERE id = 7")
                .use { c ->
                    assertTrue("the v1 expense did not survive", c.moveToFirst())
                    assertEquals(125_000L, c.getLong(0))
                    assertEquals("dinner", c.getString(1))
                    // Null, and rightly: every expense taken before this feature
                    // existed was one you paid for yourself.
                    assertTrue("payer should be null", c.isNull(2))
                }
            assertEquals("the category was lost", 1, db.count("SELECT COUNT(*) FROM category"))

            // v2's repair.
            assertTrue("the key is still nullable", db.idIsNotNull("expense"))
            assertTrue(db.idIsNotNull("income_source"))
            assertTrue(db.idIsNotNull("category"))

            // v3's additions.
            assertTrue("person missing", db.has("table", "person"))
            assertTrue("expense_share missing", db.has("table", "expense_share"))
            assertTrue("settlement missing", db.has("table", "settlement"))
            assertTrue(db.has("trigger", "trg_share_only_when_i_paid"))
            assertTrue(db.has("trigger", "trg_payer_excludes_shares"))
            assertTrue(db.has("index", "ix_share_person"))
            assertTrue(db.has("index", "ix_expense_payer"))

            // v1's own objects have to come back from the rebuild. The rollup
            // triggers are the ones that matter: dropped and not recreated,
            // every figure in the app would freeze at its pre-upgrade value
            // while the ledger underneath went on being correct.
            assertTrue(db.has("trigger", "trg_rollup_exp_ins"))
            assertTrue(db.has("trigger", "trg_rollup_inc_upd"))
            assertTrue(db.has("trigger", "trg_expense_leaf_only"))
            assertTrue(db.has("index", "ux_category_parent_key"))
            assertTrue(db.has("index", "ix_expense_date"))
            assertEquals(
                "a rebuild table was left behind",
                0,
                db.count("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%\\_new' ESCAPE '\\'"),
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun the_rebuild_keeps_the_rollups_working_afterwards() {
        // The rebuild drops and recreates every trigger. A trigger that came
        // back subtly wrong would not fail the migration — it would quietly
        // stop maintaining an aggregate, which is the failure 03 §1 exists to
        // prevent and the one a schema check cannot see.
        withV1Database { db ->
            db.execSQL(
                "INSERT INTO category (id, uuid, parent_id, name, name_key, nature, " +
                    "is_system, is_archived, sort_order, created_at, updated_at) " +
                    "VALUES (1, 'cat-uuid', NULL, 'Food', 'food', 1, 1, 0, 0, 100, 100)",
            )
        }

        val migrated = AppDatabase.named(context, name)
        try {
            val db = migrated.openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO expense (uuid, category_id, amount_minor, spent_on, period_ym, " +
                    "payment_method, status, created_at, updated_at) " +
                    "VALUES ('post-migration', 1, 5000, 20680, 202608, 0, 0, 1, 1)",
            )

            assertEquals(
                "the expense rollup trigger did not survive the rebuild",
                5_000L,
                db.count(
                    "SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month " +
                        "WHERE period_ym = 202608 AND category_id = 1",
                ),
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun each_migration_can_be_run_twice() {
        // An interrupted migration is retried on the next launch, so neither
        // may depend on running exactly once. The `ALTER` is the only
        // non-idempotent statement in the pair — SQLite has no
        // `ADD COLUMN IF NOT EXISTS` — and this is what proves its guard works
        // rather than the comment claiming it does.
        withV1Database { }

        val helper = rawHelper()
        try {
            val db = helper.writableDatabase
            Migrations.MIGRATION_1_2.migrate(db)
            Migrations.MIGRATION_1_2.migrate(db)
            Migrations.MIGRATION_2_3.migrate(db)
            Migrations.MIGRATION_2_3.migrate(db)

            assertEquals("the column was added twice", 1, db.countColumns("expense", "payer_person_id"))
            assertTrue(db.idIsNotNull("expense"))
            assertTrue(
                "person's key: " + db.sqlOf("person"),
                db.idIsNotNull("person"),
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun a_migrated_database_refuses_a_share_on_an_expense_someone_else_paid() {
        // The rule a fresh install enforces, asserted on the other path. A
        // guard present on one of the two and not the other is worse than one
        // present on neither: a new phone and an upgraded one would disagree
        // about what a balance is.
        withV1Database { db ->
            db.execSQL(
                "INSERT INTO category (id, uuid, parent_id, name, name_key, nature, " +
                    "is_system, is_archived, sort_order, created_at, updated_at) " +
                    "VALUES (1, 'cat-uuid', NULL, 'Food', 'food', 1, 1, 0, 0, 100, 100)",
            )
        }

        val migrated = AppDatabase.named(context, name)
        try {
            val db = migrated.openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO person (id, uuid, name, name_key, sort_order, is_archived, " +
                    "created_at, updated_at) VALUES (1, 'p-uuid', 'Rahim', 'rahim', 0, 0, 100, 100)",
            )
            db.execSQL(
                "INSERT INTO expense (id, uuid, category_id, amount_minor, spent_on, " +
                    "period_ym, payment_method, status, payer_person_id, created_at, updated_at) " +
                    "VALUES (9, 'e-uuid', 1, 25000, 20680, 202608, 0, 0, 1, 100, 100)",
            )

            val refused = runCatching {
                db.execSQL(
                    "INSERT INTO expense_share (uuid, expense_id, person_id, share_minor, " +
                        "created_at, updated_at) VALUES ('s-uuid', 9, 1, 25000, 100, 100)",
                )
            }.exceptionOrNull()

            assertTrue("a share was accepted on a friend-paid expense", refused != null)
            assertNull(
                "nothing should have been written",
                db.query("SELECT id FROM expense_share")
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else null },
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun the_v1_fixture_really_is_the_shape_the_migration_repairs() {
        // Without this the suite could pass by building a fixture that was
        // already correct — the migration would be verified against the world
        // it creates rather than the world it finds.
        withV1Database { }

        val helper = rawHelper()
        try {
            val db = helper.writableDatabase
            assertFalse("the fixture is already fixed", db.idIsNotNull("expense"))
            // Absent, and worth asserting rather than assuming. The fixture
            // subtracts the version-3 tables from `Schema.TABLES`, and when
            // that subtraction silently matched nothing it created all three —
            // so `MIGRATION_2_3` found them already present, skipped its
            // `CREATE ... IF NOT EXISTS`, and the run failed several tests away
            // from the cause.
            assertFalse("person should not exist yet", db.has("table", "person"))
            assertFalse("expense_share should not exist yet", db.has("table", "expense_share"))
            assertFalse("settlement should not exist yet", db.has("table", "settlement"))
            assertEquals(0, db.countColumns("expense", "payer_person_id"))
        } finally {
            helper.close()
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun rawHelper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build(),
        )

    /** Builds the database as version 1 shipped it, then closes at `user_version = 1`. */
    private fun withV1Database(populate: (SupportSQLiteDatabase) -> Unit) {
        val helper = rawHelper()
        try {
            val db = helper.writableDatabase
            v1Ddl().forEach(db::execSQL)
            (Schema.INDICES - Schema.SHARED_INDICES.toSet()).forEach(db::execSQL)
            // v1 had the functional index in the schema proper, which is
            // exactly the state the migration has to get out of.
            Schema.ROOM_INVISIBLE_INDICES.forEach(db::execSQL)
            (Schema.TRIGGERS - Schema.SHARED_TRIGGERS.toSet()).forEach(db::execSQL)
            populate(db)
            db.version = 1
        } finally {
            helper.close()
        }
    }

    private fun v1Ddl(): List<String> =
        (Schema.TABLES - Schema.SHARED_TABLES.toSet()).map { ddl ->
            ddl.lines()
                .filterNot { it.contains("payer_person_id") }
                .joinToString("\n")
                .replace("AUTOINCREMENT NOT NULL", "AUTOINCREMENT")
        }

    private fun SupportSQLiteDatabase.has(type: String, name: String): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type='$type' AND name='$name'")
            .use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.sqlOf(table: String): String =
        query("SELECT sql FROM sqlite_master WHERE name='$table'")
            .use { if (it.moveToFirst()) it.getString(0) else "(absent)" }

    private fun SupportSQLiteDatabase.count(sql: String): Long =
        query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun SupportSQLiteDatabase.countColumns(table: String, column: String): Int =
        query("PRAGMA table_info($table)").use { c ->
            val n = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(n) else null }.count { it == column }
        }

    /** What Room compares: `PRAGMA table_info`'s `notnull` flag for `id`. */
    private fun SupportSQLiteDatabase.idIsNotNull(table: String): Boolean =
        query("PRAGMA table_info($table)").use { c ->
            val n = c.getColumnIndex("name")
            val nn = c.getColumnIndex("notnull")
            generateSequence { if (c.moveToNext()) c.getString(n) to c.getInt(nn) else null }
                .any { (name, notNull) -> name == "id" && notNull == 1 }
        }
}
