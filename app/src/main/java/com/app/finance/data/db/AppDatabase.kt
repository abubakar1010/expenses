package com.app.finance.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.app.finance.BuildConfig
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.dao.BackupDao
import com.app.finance.data.db.dao.BudgetDao
import com.app.finance.data.db.dao.CategoryDao
import com.app.finance.data.db.dao.ExpenseDao
import com.app.finance.data.db.dao.ExpenseShareDao
import com.app.finance.data.db.dao.IncomeDao
import com.app.finance.data.db.dao.PersonDao
import com.app.finance.data.db.dao.RecurringDao
import com.app.finance.data.db.dao.RollupDao
import com.app.finance.data.db.dao.SettlementDao
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.ExpenseShareEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.db.entity.PersonEntity
import com.app.finance.data.db.entity.RecurringRuleEntity
import com.app.finance.data.db.entity.RollupExpenseMonthEntity
import com.app.finance.data.db.entity.RollupIncomeMonthEntity
import com.app.finance.data.db.entity.SettlementEntity

@Database(
    entities = [
        IncomeSourceEntity::class,
        IncomeEntryEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        ExpenseEntity::class,
        RollupExpenseMonthEntity::class,
        RollupIncomeMonthEntity::class,
        RecurringRuleEntity::class,
        AppMetaEntity::class,
        PersonEntity::class,
        ExpenseShareEntity::class,
        SettlementEntity::class,
    ],
    version = Schema.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun incomeDao(): IncomeDao
    abstract fun budgetDao(): BudgetDao
    abstract fun rollupDao(): RollupDao
    abstract fun appMetaDao(): AppMetaDao

    /** Whole-table access, for export, import and delete-all (M5). */
    abstract fun backupDao(): BackupDao

    abstract fun recurringDao(): RecurringDao

    abstract fun personDao(): PersonDao

    abstract fun expenseShareDao(): ExpenseShareDao

    abstract fun settlementDao(): SettlementDao

    companion object {
        const val NAME = "daybook.db"

        /**
         * In-memory instance carrying the identical canonical schema, for the
         * DAO and trigger suites. 04 §9 calls those the highest-value tests in
         * the project, because the trigger-maintained rollups are the only
         * place the same fact is stored twice.
         */
        internal fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addCallback(CanonicalSchema)
                .build()

        /** File-backed, for the tests that must close and reopen. */
        internal fun named(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(CanonicalSchema)
                .addMigrations(*Migrations.ALL)
                .openHelperFactory(preserveOnCorruption())
                .build()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // 03 §4.1. WAL gives concurrent reads during writes and fewer
                // fsyncs; it is set through the builder because issuing
                // `PRAGMA journal_mode` by hand would fight Room for ownership.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(CanonicalSchema)
                .addMigrations(*Migrations.ALL)
                .openHelperFactory(preserveOnCorruption())
                .apply {
                    // 03 §8: release builds never fall back to destructive
                    // migration. Losing a user's financial history to a schema
                    // change is unrecoverable in a product with no server
                    // backup — a migration failure must surface a recovery
                    // screen instead, never silently wipe the ledger.
                    if (BuildConfig.DEBUG) fallbackToDestructiveMigration(dropAllTables = true)
                }
                .build()

        /**
         * Keeps a corrupt ledger on disk instead of deleting it — FR-DAT-10.
         *
         * **[com.app.finance.ui.RecoveryScreen] was unreachable for the case it
         * most exists for, and this is what made it so.** 04 §8 says a database
         * that will not open must surface the recovery screen "offering export
         * of the raw database file", and `fallbackToDestructiveMigration` is
         * correctly withheld from release builds to that end — but that only
         * governs a *migration* failure. Corruption never reaches Room's
         * migration path at all: SQLite raises `SQLITE_NOTADB`, and
         * androidx.sqlite's default `onCorruption` **deletes the file**.
         * `SQLiteDatabase.open()` then retries, Room creates an empty database
         * in its place, and `verifyDatabase()` *succeeds* — so `databaseFailed`
         * stays false and the user lands on the welcome screen. Onboarding,
         * with the ledger already gone.
         *
         * Driven on a device rather than reasoned about: a 155 MB unreadable
         * `daybook.db` came back as a fresh 4 KB one across a single launch,
         * logging only `W SupportSQLite: deleting the database file` (§26.3).
         * Nothing about that path is debug-only; release behaves identically.
         *
         * So the corruption hook does nothing at all. The open goes on failing,
         * which is the point: `verifyDatabase()` reports it, the recovery screen
         * appears, and the bytes are still there to copy. Deleting them is a
         * decision only the user makes, through "Start over", which already
         * removes all three files deliberately.
         *
         * Everything else about the configuration is copied across unchanged —
         * `allowDataLossOnRecovery` included, which governs a *different*
         * androidx deletion (the silent `deleteDatabase` retry) that Room
         * already leaves off. Only the one callback is replaced.
         */
        private fun preserveOnCorruption(): SupportSQLiteOpenHelper.Factory =
            SupportSQLiteOpenHelper.Factory { configuration ->
                val room = configuration.callback
                val guarded = object : SupportSQLiteOpenHelper.Callback(room.version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
                    override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
                    override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) =
                        room.onUpgrade(db, old, new)
                    override fun onDowngrade(db: SupportSQLiteDatabase, old: Int, new: Int) =
                        room.onDowngrade(db, old, new)

                    /** Deliberately empty; see above. */
                    override fun onCorruption(db: SupportSQLiteDatabase) = Unit
                }
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                        .name(configuration.name)
                        .callback(guarded)
                        .noBackupDirectory(configuration.useNoBackupDirectory)
                        .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                        .build(),
                )
            }

        /**
         * Replaces Room's generated tables with the canonical schema.
         *
         * Room creates its own tables first; this callback then drops and
         * recreates them from [Schema] inside the same transaction, which is
         * what lands the `CHECK` constraints, the `WITHOUT ROWID` storage
         * classes, the functional index on `category`, all fourteen triggers,
         * and the seed data — none of which Room can express.
         *
         * The two views stay compatible because Room's `TableInfo` validator
         * compares column names, affinities, nullability, defaults, primary
         * keys and foreign keys, and reads neither `CHECK` constraints nor
         * `WITHOUT ROWID`.
         */
        internal object CanonicalSchema : RoomDatabase.Callback() {

            override fun onCreate(db: SupportSQLiteDatabase) {
                // Triggers first: a trigger whose table is about to be dropped
                // would otherwise be dropped implicitly and silently.
                Schema.DROP_TRIGGERS.forEach(db::execSQL)
                // Children before parents, so no drop violates a foreign key.
                Schema.DROP_TABLES.forEach(db::execSQL)

                Schema.TABLES.forEach(db::execSQL)
                Schema.INDICES.forEach(db::execSQL)
                Schema.ROOM_INVISIBLE_INDICES.forEach(db::execSQL)
                Schema.TRIGGERS.forEach(db::execSQL)

                // 03 §7 — seeded in the same transaction as schema creation, so
                // there is no observable state in which the app has a schema
                // but no categories to spend against.
                Schema.SEED.forEach(db::execSQL)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Before the pragmas, and that order is load-bearing. This runs
                // after Room has validated — see [Schema.ROOM_INVISIBLE_INDICES]
                // — and a DDL statement here changes the schema, which makes
                // Android's pool reconfigure the connection and drops
                // `foreign_keys` back to its default of off. Applying the
                // pragmas afterwards is what makes them stick, and
                // `pragmas_are_applied_on_every_connection` is what caught it.
                Schema.ROOM_INVISIBLE_INDICES.forEach(db::execSQL)
                Schema.PRAGMAS.forEach(db::execSQL)
            }
        }
    }
}
