package com.app.finance

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.ImportMode
import com.app.finance.data.export.ImportOutcome
import com.app.finance.data.export.Importer
import com.app.finance.data.export.Exporter
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.DashboardRepository
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.dev.SeedFiveYears
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.measureTimeMillis

/**
 * The two NFR-PERF targets no macrobenchmark reaches, measured on a device
 * against 02 §3.1's corpus.
 *
 * `StartupBenchmark` and `DashboardBenchmark` cover what happens between
 * process start and a drawn frame. NFR-PERF-03 ("expense save committed") and
 * NFR-PERF-07 ("full JSON export") are neither — they are one operation each,
 * timed against a database with five years in it, which is what makes them
 * worth measuring at all. Both had gone unmeasured since the SRS was written.
 *
 * **On what the assertions mean.** Every target in 02 §3.1 is defined on the
 * reference device — a 1.4 GHz Cortex-A53 — and this is not that device, so
 * meeting a target here is not evidence of compliance. It is still worth
 * asserting: the phone this runs on is several times faster than the reference,
 * so **failing** a target here would disprove compliance outright. A necessary
 * condition, checked; not a sufficient one, and §20.6 says so.
 *
 * The database is file-backed rather than in-memory, because both figures are
 * about I/O and an in-memory measurement would be answering a different
 * question.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceProbeTest {

    @Test
    fun an_expense_commits_inside_the_hundred_millisecond_budget() = runBlocking {
        // NFR-PERF-03. The write fires four rollup triggers, so this is the
        // whole cost of a Quick Add reaching the database at five-year scale —
        // which is the scale at which a trigger-per-row design would show up if
        // it were going to.
        val grocery = categories.leaves().first { it.name == "Grocery" }.id
        val samples = (1..SAMPLES).map { i ->
            measureTimeMillis {
                expenses.insert(
                    amount = Money.ofTaka(100L + i),
                    categoryId = grocery,
                    spentOn = LocalDate.of(2026, 8, 14),
                )
            }
        }.sorted()

        val median = samples[samples.size / 2]
        Log.i(TAG, "NFR-PERF-03 expense commit: median ${median}ms, worst ${samples.last()}ms")
        assertTrue(
            "expense commit median was ${median}ms against a 100ms budget",
            median <= 100,
        )
    }

    @Test
    fun the_dashboard_reads_stay_inside_the_budget_at_five_years() = runBlocking {
        // The database half of NFR-PERF-04. The macrobenchmark measures the
        // whole launch; this isolates the dashboard's reads, so a regression in
        // the rollup strategy is attributable rather than lost inside
        // composition and inflation.
        val aug = Period(202608)
        val elapsed = measureTimeMillis {
            dashboard.observeBars(aug).first()
            dashboard.observeCategoryCells(aug).first()
            dashboard.observeIncomeCells(aug).first()
            dashboard.observeExpenseSeries(aug).first()
            dashboard.observeDailySpend(aug).first()
            dashboard.observeLargestExpenses(aug).first()
            dashboard.observeTotalLimits(aug).first()
            dashboard.observeTrailingIncome(aug).first()
        }
        Log.i(TAG, "dashboard rollup reads at five years: ${elapsed}ms")
        assertTrue("dashboard reads took ${elapsed}ms", elapsed <= 300)
    }

    @Test
    fun a_full_json_export_finishes_inside_three_seconds() = runBlocking {
        // NFR-PERF-07, against every row rather than a fixture: 22,000
        // expenses, 400 income entries, sixty periods of budgets and rollups.
        val out = ByteArrayOutputStream()
        val elapsed = measureTimeMillis { exporter.writeJson(out, clock.millis()) }
        val bytes = out.size()
        Log.i(TAG, "NFR-PERF-07 export: ${elapsed}ms for $bytes bytes")
        assertTrue("export wrote nothing", bytes > 100_000)
        assertTrue("export took ${elapsed}ms against a 3s budget", elapsed <= 3_000)
    }

    @Test
    fun a_full_restore_finishes_inside_ten_seconds() = runBlocking {
        // NFR-PERF-10, which did not exist until backup did — there was no
        // import budget at all, only NFR-PERF-07 for export, and a restore is
        // the slower half by some way: decrypt, decompress, wipe, bulk insert
        // every row, and rebuild both rollup tables, all in one transaction.
        //
        // Encrypted on purpose. The unencrypted path is strictly cheaper, so
        // measuring it would be measuring the easy case and calling the budget
        // met.
        val file = ByteArrayOutputStream()
        BackupCodec.encode(file, BackupCodec.secretFrom(PASSPHRASE)).use {
            exporter.writeJson(it, clock.millis())
        }
        val bytes = file.toByteArray()
        Log.i(TAG, "NFR-PERF-10 backup is ${bytes.size} bytes for five years")
        assertTrue("the backup is empty", bytes.size > 10_000)

        // Into a *second*, empty database rather than over the shared corpus.
        // Two reasons, and both matter: the corpus is seeded once for the whole
        // class and a REPLACE would rewrite it under every probe that runs
        // after, and an empty database is the honest target anyway — NFR-PERF-10
        // is the budget for a restore after a reinstall, which is the only kind
        // there is.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(RESTORE_DB)
        val fresh = AppDatabase.named(context, RESTORE_DB)
        try {
            val into = Importer(fresh)
            val elapsed = measureTimeMillis {
                val outcome = BackupCodec.decode(ByteArrayInputStream(bytes), PASSPHRASE)
                    .use { into.import(it, ImportMode.REPLACE) }
                assertTrue("restore failed: $outcome", outcome is ImportOutcome.Done)
            }

            Log.i(TAG, "NFR-PERF-10 restore: ${elapsed}ms for ${bytes.size} bytes")
            assertTrue("restore took ${elapsed}ms against a 10s budget", elapsed <= 10_000)

            // A restore that met the budget by dropping rows would be worse than
            // a slow one, so the ledger is counted afterwards.
            val restored = fresh.backupDao().allExpenses().size
            val original = db.backupDao().allExpenses().size
            assertEquals("the restore lost rows", original, restored)
        } finally {
            fresh.close()
            context.deleteDatabase(RESTORE_DB)
        }
    }

    @Test
    fun five_years_of_data_fits_the_database_budget() = runBlocking {
        // NFR-SIZE-05 — "database size for 5 years of data <= 6 MB", and 03 §9
        // sizes it at roughly 3.8 MB with indexes. Never measured until now,
        // though it is the one budget in the SRS a schema decision could blow on
        // its own: `period_ym` is denormalised, every entity carries a UUID, and
        // both rollup tables duplicate the ledger by design.
        val file = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getDatabasePath(DB_NAME)
        // WAL and the shared-memory file are part of what sits on disk.
        val wal = java.io.File(file.path + "-wal")
        val shm = java.io.File(file.path + "-shm")
        val total = file.length() + wal.length() + shm.length()

        Log.i(
            TAG,
            "NFR-SIZE-05 database: main ${file.length() / 1024}KB, " +
                "wal ${wal.length() / 1024}KB, shm ${shm.length() / 1024}KB, " +
                "total ${total / 1024}KB",
        )
        assertTrue("database was not created", file.length() > 0)
        assertTrue(
            "five years took ${total / 1024}KB against a 6 MB budget",
            total <= 6L * 1024 * 1024,
        )
    }

    // Not private: JUnit calls `@BeforeClass` reflectively and needs it public
    // and static, which a private companion cannot give it.
    companion object {
        const val TAG = "DayBookPerf"
        const val SAMPLES = 21
        const val DB_NAME = "perf-probe.db"

        /** NFR-PERF-10 restores into its own database; see the probe for why. */
        const val RESTORE_DB = "perf-restore.db"
        val PASSPHRASE = "shob kichu fire ashuk".toCharArray()

        lateinit var db: AppDatabase
        lateinit var expenses: ExpenseRepository
        lateinit var categories: CategoryRepository
        lateinit var dashboard: DashboardRepository
        lateinit var exporter: Exporter

        val clock: Clock = Clock.fixed(
            LocalDate.of(2026, 8, 14).atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault(),
        )

        /**
         * Seeded once for the class, not per test: building the corpus is the
         * expensive part and every probe wants the same one.
         */
        @JvmStatic
        @BeforeClass
        fun seedOnce(): Unit = runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            context.deleteDatabase(DB_NAME)
            db = AppDatabase.named(context, DB_NAME)
            expenses = ExpenseRepository(db, clock)
            categories = CategoryRepository(db, clock)
            dashboard = DashboardRepository(db)
            exporter = Exporter(db)

            val counts = SeedFiveYears.into(db, Period(202608), SeedFiveYears.Scale.BENCHMARK)
            Log.i(TAG, "probe corpus: ${counts.expenses} expenses, ${counts.income} income")
        }

        @JvmStatic
        @AfterClass
        fun tearDownOnce() {
            db.close()
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .deleteDatabase(DB_NAME)
        }
    }
}
