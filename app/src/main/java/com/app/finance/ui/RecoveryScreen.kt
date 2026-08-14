package com.app.finance.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.remember
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
    var message by remember { mutableStateOf<String?>(null) }
    val savedText = stringResource(R.string.recovery_saved)
    val failedText = stringResource(R.string.recovery_failed)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val target = result.data?.data
        message = if (target != null && copyDatabase(context, target)) savedText else failedText
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
            shape = RoundedCornerShape(Radius.input),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.indigo,
                contentColor = colors.card,
            ),
            modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
        ) {
            Text(stringResource(R.string.recovery_save), style = KhataTheme.type.body)
        }
        message?.let {
            Text(it, style = KhataTheme.type.caption, color = colors.inkSoft)
        }
    }
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
