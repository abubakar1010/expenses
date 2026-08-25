package com.app.finance.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.app.finance.R
import com.app.finance.data.db.AppDatabase
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shown when the database cannot be opened — 04-system-architecture.md §8.
 *
 * > "Migration failure | Release builds never fall back to destructive
 * > migration. Failure surfaces a recovery screen offering export of the raw
 * > database file"
 *
 * Release builds deliberately have no destructive-migration fallback, because
 * losing a user's financial history to a schema change is unrecoverable in a
 * product with no server backup. The consequence is that a bad migration would
 * otherwise crash on launch with no message and no way out — so this screen is
 * the other half of that decision, not a nicety.
 *
 * `ACTION_CREATE_DOCUMENT` needs no permission and writes wherever the user
 * chooses. It is also the mechanism M5's export will use.
 */
@Composable
fun RecoveryScreen() {
    val context = LocalContext.current
    val colors = KhataTheme.colors
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.recovery_saved)
    val failedText = stringResource(R.string.recovery_failed)

    // `rememberSaveable`, all three. This screen is reached by a database that
    // will not open, so there is nowhere to persist anything -- but a rotation
    // is not process death, and losing the interlock below to one would put the
    // user back in front of a disabled button having already saved their copy.
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    // FR-DAT-10's interlock: the destructive button below stays disabled until
    // the broken ledger has actually been copied somewhere.
    var copied by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val target = result.data?.data
        // Off the main thread. This zips the database and both sidecars -- five
        // years of ledger is megabytes, and it ran inside the result callback,
        // which is the main thread: seconds of blocking I/O and an ANR on the
        // one screen that exists to rescue data. StrictMode's `penaltyDeath`
        // does not catch it because this path is unreachable in a debug build
        // with a healthy database.
        saving = true
        scope.launch {
            val ok = target != null && withContext(Dispatchers.IO) { copyDatabase(context, target) }
            copied = copied || ok
            message = if (ok) savedText else failedText
            saving = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.s3, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.recovery_title),
            style = KhataTheme.type.screenTitle,
            color = colors.ink,
        )
        Text(
            // §9: state the problem and the fix, without apology. The first
            // thing the user needs to know is that their data is not gone.
            text = stringResource(R.string.recovery_body),
            style = KhataTheme.type.body,
            color = colors.inkSoft,
        )
        Button(
            onClick = {
                saveLauncher.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, BACKUP_NAME)
                    },
                )
            },
            enabled = !saving,
            shape = RoundedCornerShape(Radius.input),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.indigo,
                contentColor = colors.card,
                disabledContainerColor = colors.rule,
                disabledContentColor = colors.inkSoft,
            ),
            modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
        ) {
            Text(stringResource(R.string.recovery_save), style = KhataTheme.type.body)
        }

        // The other half of the recovery, added once backups existed.
        //
        // Before this the screen could only preserve a broken ledger; getting
        // back to a working app meant clearing the app's data through Android
        // settings, which nobody discovers at the moment they need it. Deleting
        // the file here and reopening lands on [WelcomeScreen], where a backup
        // can be brought straight in.
        //
        // Disabled until the copy above succeeded. 05 §8 asks for a typed
        // confirmation where there is no undo, and this is a better instrument
        // than a typed word for the same job: it makes saving a copy a
        // precondition of discarding the original rather than a suggestion
        // printed beside it.
        Button(
            onClick = { discardAndReopen(context) },
            enabled = copied && !saving,
            shape = RoundedCornerShape(Radius.input),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.vermilion,
                contentColor = colors.card,
                disabledContainerColor = colors.rule,
                disabledContentColor = colors.inkSoft,
            ),
            modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
        ) {
            Text(stringResource(R.string.recovery_start_over), style = KhataTheme.type.body)
        }
        Text(
            text = stringResource(
                if (copied) R.string.recovery_start_over_hint else R.string.recovery_save_first,
            ),
            style = KhataTheme.type.caption,
            color = colors.inkSoft,
        )

        message?.let {
            Text(it, style = KhataTheme.type.caption, color = colors.inkSoft)
        }
    }
}

/**
 * Deletes the database that will not open and restarts into a fresh one.
 *
 * All three files, not just the main one: a `-wal` left beside a deleted
 * database is the next launch's corruption, because SQLite will try to replay
 * it against a file it does not belong to.
 *
 * The process is ended rather than the activity recreated. `AppContainer` holds
 * the `AppDatabase` in a `by lazy` that has already resolved by the time this
 * screen is on top of it, and Room caches the failed open besides -- so nothing
 * short of a new process reopens the file. It is blunt, and it is the honest
 * option: the alternative is an app that says it has started over and has not.
 */
private fun discardAndReopen(context: Context) {
    val main = context.getDatabasePath(AppDatabase.NAME)
    listOf(
        main,
        File(main.parentFile, AppDatabase.NAME + "-wal"),
        File(main.parentFile, AppDatabase.NAME + "-shm"),
    ).forEach { runCatching { it.delete() } }

    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    Process.killProcess(Process.myPid())
}

/**
 * Writes the database and its sidecars into a single zip.
 *
 * A zip rather than a bare file copy, because the database is in WAL mode: the
 * `-wal` sidecar holds transactions not yet folded into the main file, so a copy
 * of `khata.db` alone can be missing the most recent expenses — exactly the ones
 * the user is most likely to care about. Concatenating them would be worse
 * still: the result is not a valid database at all. Keeping all three as
 * separate entries lets SQLite reassemble them.
 *
 * A checkpoint would be the tidier fix, but this screen exists precisely because
 * the database could not be opened, so there is nothing to checkpoint through.
 */
private fun copyDatabase(context: Context, target: Uri): Boolean = runCatching {
    val main = context.getDatabasePath(AppDatabase.NAME)
    if (!main.exists()) return false

    val parts = listOf(
        main,
        File(main.parentFile, "${AppDatabase.NAME}-wal"),
        File(main.parentFile, "${AppDatabase.NAME}-shm"),
    ).filter(File::exists)

    context.contentResolver.openOutputStream(target)?.use { out ->
        ZipOutputStream(out.buffered()).use { zip ->
            parts.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    } ?: return false
    true
}.getOrDefault(false)

private const val BACKUP_NAME = "khata-backup.zip"
