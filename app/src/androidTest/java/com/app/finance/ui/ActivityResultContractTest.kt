package com.app.finance.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.MainActivity
import com.app.finance.data.backup.SafBackupStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard for §21.7 — every document picker in the app crashing on tap.
 *
 * `androidx.biometric:1.1.0` resolves `androidx.fragment` to 1.2.5, whose
 * `FragmentActivity.startActivityForResult` packs a fragment index into the
 * upper 16 bits of the request code and rejects anything that does not fit.
 * `ActivityResultRegistry` allocates from `0x10000` upwards, so **every**
 * `rememberLauncherForActivityResult` launch threw
 * `IllegalArgumentException: Can only use lower 16 bits for requestCode` —
 * FR-DAT-01, FR-DAT-02, FR-DAT-03 and the recovery screen's raw copy, which is
 * the last resort when the ledger will not open.
 *
 * `MainActivity` became a `FragmentActivity` for FR-APP-04's biometric gate and
 * nothing afterwards tapped a picker. Four test layers each had a good reason
 * not to notice: the ViewModel suites drive the export path with a
 * `ByteArrayOutputStream` *by design*, so they never touch a launcher; there was
 * no `SettingsScreenTest`; `RecoveryPathTest` asserts the screen appears but
 * does not press the button on it; and §20.8's release walkthrough covered the
 * dashboard, ledger, entry and lock.
 *
 * **This is a guard, not an end-to-end test, and the difference is worth being
 * clear about.** Driving the system picker needs UI Automator against a
 * provider that varies by ROM. What is asserted here is narrower and is exactly
 * what broke: that the activity class the app actually uses does not reimpose a
 * 16-bit ceiling on request codes. A dependency bump that reintroduces it fails
 * here rather than on somebody's phone.
 */
@RunWith(AndroidJUnit4::class)
class ActivityResultContractTest {

    @Test
    fun nothing_in_the_activity_hierarchy_narrows_request_codes_to_sixteen_bits() {
        // `checkForValidRequestCode` is the method that threw, and its presence
        // anywhere between MainActivity and Activity is the bug — merely
        // *declaring* `startActivityForResult` is not, because
        // `androidx.activity.ComponentActivity` declares a deprecated override
        // that does nothing but call super. Asserting on the declaration was the
        // first version of this test and it failed for that reason: it named a
        // symptom that is also present when everything is fine.
        val offenders = generateSequence<Class<*>>(MainActivity::class.java) { it.superclass }
            .takeWhile { it != Any::class.java }
            .filter { klass -> klass.declaredMethods.any { it.name == "checkForValidRequestCode" } }
            .map { it.name }
            .toList()

        assertEquals(
            "androidx.fragment has regressed to a version that packs a fragment index " +
                "into the request code, which breaks every document picker in the app. " +
                "See libs.versions.toml and §21.7. Offending classes:",
            emptyList<String>(),
            offenders,
        )
    }

    // --- the intent the whole feature rests on --------------------------------

    @Test
    fun the_folder_picker_asks_for_a_grant_that_outlives_the_process() {
        // Without FLAG_GRANT_PERSISTABLE_URI_PERMISSION the grant dies with the
        // process and the user re-chooses a folder on every launch — which is
        // the manual export they already have. Nothing else in the app would
        // fail visibly; backups would simply stop after a reboot.
        val intent = SafBackupStore.pickFolder()

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue(
            "the grant would not survive the process",
            intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0,
        )
        assertTrue(
            "the folder would be read-only",
            intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
        )
        assertTrue(
            "the folder could not be listed",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    @Test
    fun the_folder_picker_is_a_tree_request_and_not_a_file_one() {
        // ACTION_OPEN_DOCUMENT would hand back a single file, and the app would
        // then be writing every backup over the same document.
        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT, SafBackupStore.pickFolder().action)
    }
}
