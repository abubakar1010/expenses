package com.app.finance.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.app.finance.BuildConfig
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.dao.BackupDao
import com.app.finance.data.db.dao.BudgetDao
import com.app.finance.data.db.dao.CategoryDao
import com.app.finance.data.db.dao.ExpenseDao
import com.app.finance.data.db.dao.IncomeDao
import com.app.finance.data.db.dao.RecurringDao
import com.app.finance.data.db.dao.RollupDao
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.db.entity.RecurringRuleEntity
import com.app.finance.data.db.entity.RollupExpenseMonthEntity
import com.app.finance.data.db.entity.RollupIncomeMonthEntity

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

    companion object {
        const val NAME = "khata.db"

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
                .build()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // 03 §4.1. WAL gives concurrent reads during writes and fewer
                // fsyncs; it is set through the builder because issuing
                // `PRAGMA journal_mode` by hand would fight Room for ownership.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(CanonicalSchema)
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
                Schema.TRIGGERS.forEach(db::execSQL)

                // 03 §7 — seeded in the same transaction as schema creation, so
                // there is no observable state in which the app has a schema
                // but no categories to spend against.
                Schema.SEED.forEach(db::execSQL)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                Schema.PRAGMAS.forEach(db::execSQL)
            }
        }
    }
}
