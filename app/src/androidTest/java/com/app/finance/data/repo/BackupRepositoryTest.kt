package com.app.finance.data.repo

import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.data.backup.FakeBackupStore
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.ImportMode
import com.app.finance.data.export.ImportOutcome
import com.app.finance.domain.model.BackupInterval
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The automatic backup — FR-DAT-07 … FR-DAT-12.
 *
 * Against a real in-memory database with the canonical schema, and a fake
 * folder. The database half has to be real for the same reason every other
 * repository suite's is: what a backup contains comes out of triggers and
 * constraints, not out of Kotlin. The folder half is fake because a document
 * tree cannot be granted without a human tapping a picker, and the arithmetic
 * being checked here — when to run, what to delete — is precisely where a bug
 * would eat somebody's history with nothing appearing to go wrong.
 */
class BackupRepositoryTest {

    private lateinit var fx: TestFixture
    private lateinit var store: FakeBackupStore
    private lateinit var backup: BackupRepository
    private var now: Instant = Instant.parse("2026-08-14T10:30:00Z")

    private val pass = "amar daybook".toCharArray()

    @Before
    fun setUp() {
        fx = TestFixture()
        store = FakeBackupStore()
        backup = BackupRepository(
            db = fx.db,
            exporter = fx.exporter,
            importer = fx.importer,
            settings = fx.settings,
            clock = object : Clock() {
                override fun getZone(): ZoneId = ZoneId.of("UTC")
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = now
            },
            storeFor = { store },
        )
    }

    @After
    fun tearDown() = fx.close()

    // --- FR-DAT-08: when it runs ---------------------------------------------

    @Test
    fun nothing_is_written_until_a_folder_and_a_schedule_are_chosen() = runBlocking {
        assertEquals(BackupOutcome.Skipped, backup.runIfDue())

        // A folder alone is not consent to start copying a financial ledger
        // somewhere. NFR-SEC-01 is about the user's explicit act, and choosing
        // where is only half of it.
        arm(interval = BackupInterval.OFF)
        assertEquals(BackupOutcome.Skipped, backup.runIfDue())
        assertTrue(store.names.isEmpty())
    }

    @Test
    fun the_first_launch_after_arming_writes_a_backup() = runBlocking {
        spend(120_00)
        arm()

        val outcome = backup.runIfDue()

        assertTrue(outcome is BackupOutcome.Done)
        assertEquals(listOf("daybook-backup-2026-08-14-1030.daybook"), store.names)
        assertTrue((outcome as BackupOutcome.Done).rows > 0)
    }

    @Test
    fun a_second_launch_the_same_day_writes_nothing() = runBlocking {
        arm()
        backup.runIfDue()

        assertEquals(BackupOutcome.Skipped, backup.runIfDue())
        assertEquals(1, store.names.size)
    }

    @Test
    fun a_day_later_with_nothing_changed_writes_nothing() = runBlocking {
        // The check worth having. Without it, a phone opened every morning
        // writes an identical copy each time and rotates a real backup out of
        // the folder to make room -- retention destroying history in the name of
        // keeping it.
        arm()
        backup.runIfDue()

        now = now.plusSeconds(3 * DAY)
        assertEquals(BackupOutcome.Skipped, backup.runIfDue())
        assertEquals(1, store.names.size)
    }

    @Test
    fun a_day_later_with_a_new_expense_writes_again() = runBlocking {
        arm()
        backup.runIfDue()

        now = now.plusSeconds(DAY)
        spend(340_00)

        assertTrue(backup.runIfDue() is BackupOutcome.Done)
        assertEquals(2, store.names.size)
    }

    @Test
    fun the_same_day_with_a_new_expense_still_waits_for_the_interval() = runBlocking {
        arm()
        backup.runIfDue()

        now = now.plusSeconds(3600)
        spend(55_00)

        assertEquals(BackupOutcome.Skipped, backup.runIfDue())
    }

    @Test
    fun a_clock_that_moved_backwards_does_not_freeze_the_schedule() = runBlocking {
        arm()
        backup.runIfDue()

        // A timezone change or a corrected date. Waiting for the clock to catch
        // up could mean months without a backup.
        now = now.minusSeconds(30 * DAY)
        spend(80_00)

        assertTrue(backup.runIfDue() is BackupOutcome.Done)
    }

    @Test
    fun back_up_now_runs_even_with_the_schedule_off() = runBlocking {
        arm(interval = BackupInterval.OFF)

        assertTrue(backup.runNow() is BackupOutcome.Done)
        assertEquals(1, store.names.size)
    }

    @Test
    fun back_up_now_without_a_folder_says_so() = runBlocking {
        assertEquals(BackupOutcome.Failure.NO_FOLDER, backup.runNow())
    }

    // --- FR-DAT-09: rotation --------------------------------------------------

    @Test
    fun rotation_keeps_the_newest_and_deletes_the_rest() = runBlocking {
        arm(keep = 3)

        repeat(6) { day ->
            now = Instant.parse("2026-08-14T10:30:00Z").plusSeconds(day.toLong() * DAY)
            spend(100_00L + day)
            backup.runIfDue()
        }

        assertEquals(
            listOf(
                "daybook-backup-2026-08-17-1030.daybook",
                "daybook-backup-2026-08-18-1030.daybook",
                "daybook-backup-2026-08-19-1030.daybook",
            ),
            store.names.sorted(),
        )
    }

    @Test
    fun rotation_leaves_everything_that_is_not_ours_alone() = runBlocking {
        // A backup folder is a folder the user chose, and may well be one they
        // keep other things in.
        store.put("taxes-2025.pdf")
        store.put("daybook-export.json")
        arm(keep = 1)

        repeat(3) { day ->
            now = Instant.parse("2026-08-14T10:30:00Z").plusSeconds(day.toLong() * DAY)
            spend(10_00L + day)
            backup.runIfDue()
        }

        assertTrue("taxes-2025.pdf" in store.names)
        assertTrue("daybook-export.json" in store.names)
        assertEquals(1, store.names.count(BackupCodec::isBackupName))
    }

    @Test
    fun a_part_file_left_by_a_killed_write_is_swept_up() = runBlocking {
        store.put(BackupCodec.fileName("2026-08-01-0900") + BackupCodec.PARTIAL)
        arm()

        backup.runIfDue()

        assertFalse(store.names.any { it.endsWith(BackupCodec.PARTIAL) })
    }

    // --- failures leave what was there ----------------------------------------

    @Test
    fun an_unreachable_folder_reports_and_keeps_the_setting() = runBlocking {
        arm()
        store.reachable = false

        assertEquals(BackupOutcome.Failure.UNREACHABLE, backup.runIfDue())
        // The card goes back in tomorrow. Forgetting the folder because it was
        // absent once would make the user set it up again for nothing.
        assertEquals(TREE, fx.settings.backupSettings().treeUri)
    }

    @Test
    fun a_write_that_dies_part_way_leaves_no_usable_file_and_no_record() = runBlocking {
        arm()
        spend(500_00)
        store.failWriteAfter = 64

        assertEquals(BackupOutcome.Failure.WRITE_FAILED, backup.runIfDue())

        assertTrue(store.names.isEmpty())
        // Not recorded, so the next launch tries again rather than believing it
        // has a backup it does not have.
        assertFalse(fx.settings.backupSettings().hasEverRun)
    }

    @Test
    fun a_failed_write_does_not_disturb_the_backups_already_there() = runBlocking {
        arm(keep = 2)
        backup.runIfDue()
        val before = store.names.toList()

        now = now.plusSeconds(DAY)
        spend(70_00)
        store.failWriteAfter = 32
        assertEquals(BackupOutcome.Failure.WRITE_FAILED, backup.runIfDue())

        assertEquals(before, store.names)
    }

    @Test
    fun a_backup_that_cannot_be_given_its_final_name_is_not_reported_as_one() = runBlocking {
        // The content is written and valid, but a file still called `.part` is
        // one `isBackupName` will not match: rotation ignores it, `list()` never
        // shows it, and the user would be told they have a backup nothing in the
        // app can find. Failing honestly and retrying next launch is the only
        // answer that stays true.
        arm()
        spend(150_00)
        store.refuseRename = true

        assertEquals(BackupOutcome.Failure.WRITE_FAILED, backup.runIfDue())

        assertTrue("a half-named file was left behind", store.names.isEmpty())
        assertFalse("a backup nobody can find was recorded", fx.settings.backupSettings().hasEverRun)
    }

    @Test
    fun a_folder_that_refuses_the_file_is_reported_not_thrown() = runBlocking {
        arm()
        store.refuseCreate = true

        assertEquals(BackupOutcome.Failure.WRITE_FAILED, backup.runIfDue())
    }

    // --- FR-DAT-11: encryption ------------------------------------------------

    @Test
    fun an_encrypted_backup_is_not_readable_without_the_passphrase() = runBlocking {
        spend(999_00)
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        arm()

        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        assertFalse("the ledger is in the file in the clear", String(file).contains("amount_minor"))
        assertTrue(BackupCodec.needsPassphrase(ByteArrayInputStream(file)))
    }

    @Test
    fun an_encrypted_backup_restores_with_the_passphrase() = runBlocking {
        spend(410_00)
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        arm()
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        fx.settings.deleteAllData()
        assertEquals(0, expenseCount())

        val outcome = backup.restore({ ByteArrayInputStream(file) }, pass, ImportMode.REPLACE)

        assertTrue(outcome is RestoreOutcome.Done)
        assertEquals(1, expenseCount())
    }

    @Test
    fun the_wrong_passphrase_changes_nothing() = runBlocking {
        spend(410_00)
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        arm()
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!
        fx.settings.deleteAllData()

        val outcome = backup.restore({ ByteArrayInputStream(file) }, "wrong".toCharArray(), ImportMode.REPLACE)

        assertEquals(RestoreOutcome.WrongPassphrase, outcome)
        assertEquals(0, expenseCount())
    }

    @Test
    fun an_encrypted_backup_with_no_passphrase_asks_rather_than_fails() = runBlocking {
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        arm()
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        assertEquals(
            RestoreOutcome.NeedsPassphrase,
            backup.restore({ ByteArrayInputStream(file) }, null, ImportMode.REPLACE),
        )
        assertTrue(backup.needsPassphrase { ByteArrayInputStream(file) })
    }

    @Test
    fun a_tampered_backup_is_refused_in_full() = runBlocking {
        spend(410_00)
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        arm()
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!.copyOf()
        file[file.size - 8] = (file[file.size - 8].toInt() xor 0x01).toByte()

        fx.settings.deleteAllData()
        val outcome = backup.restore({ ByteArrayInputStream(file) }, pass, ImportMode.REPLACE)

        assertTrue(outcome is RestoreOutcome.Refused)
        assertEquals(0, expenseCount())
    }

    @Test
    fun a_damaged_backup_and_a_stranger_get_different_sentences() = runBlocking {
        // The codec goes to some trouble to tell a DayBook file that has been
        // altered from a file that was never one. Collapsing both into
        // "this isn't a DayBook backup" at the last step would throw that away and
        // send somebody with a truncated backup off to find a different file.
        arm()
        backup.runIfDue()
        val good = store.bytesOf(store.names.single())!!

        val truncated = good.copyOf(good.size - 30)
        assertEquals(
            RestoreOutcome.Refused(ImportOutcome.Failure.REJECTED),
            backup.restore({ ByteArrayInputStream(truncated) }, null, ImportMode.MERGE),
        )

        val stranger = "not a backup at all".toByteArray()
        assertEquals(
            RestoreOutcome.Refused(ImportOutcome.Failure.UNREADABLE),
            backup.restore({ ByteArrayInputStream(stranger) }, null, ImportMode.MERGE),
        )
    }

    @Test
    fun a_backup_from_a_later_release_says_so_rather_than_calling_it_broken() = runBlocking {
        // FR-DAT-05 already extends this courtesy to a newer schema. A container
        // this build does not know is the same situation and the same sentence:
        // update, then import again.
        arm()
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!.copyOf()
        file[BackupCodec.MAGIC.size] = 9

        assertEquals(
            RestoreOutcome.Refused(ImportOutcome.Failure.NEWER_SCHEMA),
            backup.restore({ ByteArrayInputStream(file) }, null, ImportMode.MERGE),
        )
    }

    // --- FR-DAT-12: what a backup must not carry ------------------------------

    @Test
    fun the_file_does_not_carry_this_phones_folder_or_its_key() = runBlocking {
        // Carrying the grant would leave a restored phone pointing at a folder it
        // cannot write to; carrying the key would put it inside the very files it
        // protects, and in a plain backup, in the clear.
        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        arm()
        backup.runIfDue()

        val json = String(BackupCodec.decode(ByteArrayInputStream(store.bytesOf(store.names.single())!!), pass).readBytes())

        AppMetaDao.TRANSIENT_KEYS.forEach { key ->
            assertFalse("$key is in the backup file", json.contains(key))
        }
        // The user's own choices do travel -- they would otherwise have to be
        // made again on a new phone.
        assertTrue(json.contains(AppMetaDao.KEY_BACKUP_INTERVAL))
        assertTrue(json.contains(AppMetaDao.KEY_BACKUP_KEEP))
    }

    @Test
    fun a_restore_keeps_the_folder_and_the_key_this_phone_already_had() = runBlocking {
        // A REPLACE wipes app_meta and refills it from the file, and the file
        // deliberately carries neither of these. Without the save-and-reapply the
        // app would come back with its ledger intact and its backups silently
        // switched off.
        spend(210_00)
        arm()
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        fx.settings.setBackupSecret(BackupCodec.secretFrom(pass))
        backup.restore({ ByteArrayInputStream(file) }, null, ImportMode.REPLACE)

        val after = fx.settings.backupSettings()
        assertEquals(TREE, after.treeUri)
        assertTrue(after.encrypted)
        assertNotNull(fx.settings.backupSecret())
        // ...but not the record of what this phone had already written, so the
        // restored ledger gets a backup of its own at the next opportunity.
        assertFalse(after.hasEverRun)
    }

    @Test
    fun a_restore_is_followed_by_a_fresh_backup() = runBlocking {
        spend(210_00)
        arm()
        backup.runIfDue()
        val file = store.bytesOf(store.names.single())!!

        now = now.plusSeconds(DAY)
        backup.restore({ ByteArrayInputStream(file) }, null, ImportMode.REPLACE)

        assertTrue(backup.runIfDue() is BackupOutcome.Done)
    }

    // --- listing --------------------------------------------------------------

    @Test
    fun the_newest_backup_is_the_one_offered_for_sending() = runBlocking {
        arm(keep = 5)
        repeat(3) { day ->
            now = Instant.parse("2026-08-14T10:30:00Z").plusSeconds(day.toLong() * DAY)
            spend(30_00L + day)
            backup.runIfDue()
        }

        assertEquals("daybook-backup-2026-08-16-1030.daybook", backup.newest()?.name)
        assertEquals(3, backup.list().size)
    }

    @Test
    fun there_is_nothing_to_list_without_a_folder() = runBlocking {
        assertTrue(backup.list().isEmpty())
        assertNull(backup.newest())
        assertNull(backup.folderLabel())
    }

    // --- helpers --------------------------------------------------------------

    // --- FR-DAT-09: rotation, and the clock it depends on --------------------

    @Test
    fun a_clock_that_has_moved_backwards_does_not_make_rotation_eat_the_newest_backup() =
        runBlocking {
            // Two behaviours that are each right on their own. `intervalElapsed`
            // treats a negative elapsed as *due* — a phone whose date was
            // corrected should be backed up rather than wait months for the
            // clock to catch up. And rotation sorts by name, because more than
            // one document provider reports a last-modified of zero for
            // everything it holds.
            //
            // The name was stamped in local time, so after a backwards change
            // the new file sorts *below* the ones already there. It was the
            // last of six with `keep = 5`, so it was deleted — by its own
            // rotation, moments after `Done` was reported for it.
            arm(keep = 2)
            fx.expenses.insert(Money.ofTaka(100), fx.leafId("Grocery"), fx.today)
            backup.runNow()

            now = now.plusSeconds(3600)
            fx.expenses.insert(Money.ofTaka(200), fx.leafId("Grocery"), fx.today)
            backup.runNow()

            // The clock goes back a day. Both existing backups now sort above
            // anything this one can be called.
            now = now.minusSeconds(24 * 3600)
            fx.expenses.insert(Money.ofTaka(300), fx.leafId("Grocery"), fx.today)
            val outcome = backup.runNow()

            val written = (outcome as BackupOutcome.Done).name
            assertTrue(
                "the backup just written was deleted by its own rotation: $written not in ${store.names}",
                store.names.contains(written),
            )
            assertEquals("the generation budget was not honoured", 2, store.names.size)
        }

    @Test
    fun the_name_is_stamped_in_a_fixed_zone_so_the_order_survives_a_move() = runBlocking {
        // The stamp used `clock.zone`. Two backups an hour apart, taken either
        // side of a six-hour timezone change, must still sort in the order they
        // were taken — which is the only thing rotation has to go on.
        arm(keep = 5)
        fx.expenses.insert(Money.ofTaka(100), fx.leafId("Grocery"), fx.today)
        backup.runNow()
        val first = store.names.single()

        now = now.plusSeconds(3600)
        fx.expenses.insert(Money.ofTaka(200), fx.leafId("Grocery"), fx.today)
        backup.runNow()
        val second = store.names.first { it != first }

        assertTrue("$second should sort after $first", second > first)
    }

    @Test
    fun rotation_keeps_exactly_the_number_of_generations_asked_for() = runBlocking {
        // The retention budget has to stay exact now that the file just written
        // is excluded from the list before the count is taken.
        arm(keep = 3)
        repeat(5) { i ->
            now = now.plusSeconds(3600L * (i + 1))
            fx.expenses.insert(Money.ofTaka(100L + i), fx.leafId("Grocery"), fx.today)
            backup.runNow()
        }

        assertEquals(3, store.names.size)
    }

    private suspend fun arm(
        interval: BackupInterval = BackupInterval.DAILY,
        keep: Int = 5,
    ) {
        fx.settings.setBackupFolder(TREE)
        fx.settings.setBackupInterval(interval)
        fx.settings.setBackupKeep(keep)
    }

    private suspend fun spend(paisa: Long) {
        fx.expenses.insert(Money(paisa), fx.leafId("Grocery"), fx.today)
    }

    private suspend fun expenseCount(): Int = fx.db.backupDao().allExpenses().size

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        const val DAY = 24L * 60 * 60
    }
}
