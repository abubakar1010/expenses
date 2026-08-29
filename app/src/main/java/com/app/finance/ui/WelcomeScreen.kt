package com.app.finance.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.data.backup.SafBackupStore
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.ImportMode
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.ui.feature.backup.BackupMessage
import com.app.finance.ui.feature.backup.BackupViewModel
import com.app.finance.ui.feature.backup.SecretField
import com.app.finance.ui.feature.backup.backupMessage
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import com.app.finance.ui.lock.rememberHandoffLauncher
import kotlinx.coroutines.launch

/**
 * The first launch after an install — FR-DAT-10.
 *
 * The piece that makes a backup worth having. Everything else in this feature
 * writes files; this is the one screen that reads one back, and without it a
 * user who has reinstalled lands on an empty dashboard with no indication that
 * their five years are recoverable at all. They would have to know to go looking
 * in Settings for it, which is knowledge nobody has at the one moment they need
 * it most.
 *
 * **Restore is REPLACE, and there is no mode to choose.** `06 §18.1` is why: a
 * fresh install re-seeds `category` and `income_source` with **new random
 * UUIDs**, so a backup arriving here is a file from another install as far as
 * the merge is concerned. REPLACE reproduces the file's integer ids exactly and
 * is what FR-DAT-04's losslessness is measured against; MERGE would resolve the
 * seeded categories by name key, keep the local rows, and quietly file a year of
 * groceries against a different id. Offering the choice would only be offering
 * the wrong one.
 *
 * There is no typed confirmation either, and that is not an oversight of `05
 * §8`'s rule. The rule protects against destroying something. Here there is
 * nothing to destroy — the ledger is empty, which is exactly the condition
 * `SettingsRepository.observeNeedsWelcome` checks before this screen is shown at
 * all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(container: AppContainer, onDone: () -> Unit) {
    val vm: BackupViewModel = viewModel(
        factory = viewModelFactory {
            BackupViewModel(backups = container.backupRepo, settings = container.settingsRepo)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = DayBookTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Guards the two ways out of this screen against being taken twice, and
    // against being taken while the flag is still being written.
    var answering by remember { mutableStateOf(false) }

    // The flag and the latch above it, together. The flag stops the question
    // being asked on the next launch; the latch stops it disappearing during
    // this one.
    //
    // `onDone()` is called *after* the write, not beside it. It was
    // `scope.launch { setOnboarded() }` followed immediately by `onDone()`,
    // which flips the latch, removes this screen from the composition and
    // cancels the very scope the write is running in -- a coin-toss the write
    // usually lost. "Start fresh" then asked again on every launch until the
    // first expense happened to satisfy `observeNeedsWelcome` some other way,
    // and a fresh install that was left alone asked forever.
    fun answered() {
        if (answering) return
        answering = true
        scope.launch {
            runCatching { container.settingsRepo.setOnboarded() }
                .onFailure { error -> Log.w("DayBook", "could not record onboarding", error) }
            onDone()
        }
    }

    val fileLauncher = rememberHandoffLauncher(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val source = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && source != null) {
            vm.offerRestore { context.contentResolver.openInputStream(source) }
        }
    }

    // The flag is written on the way out rather than on the way in, so a restore
    // that failed leaves the offer standing. Being dropped into an empty ledger
    // because the first attempt used the wrong passphrase would be the worst
    // possible moment to hide this screen.
    //
    // A restore that worked does not finish here, though. The file carried the
    // user's schedule -- `backup_interval` travels -- but it could not carry the
    // folder grant, because that names a permission *this* phone does not hold
    // (`AppMetaDao.TRANSIENT_KEYS`). So the ledger is back and nothing is
    // backing it up, and the only person who could notice has just been shown a
    // dashboard that looks entirely healthy. Asking for a folder here is not a
    // nag under 05 §12: somebody who has this second restored from a backup has
    // said as clearly as anyone can that they want one.
    var restoredUnprotected by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        if (state.message is BackupMessage.Restored) {
            if (state.settings.treeUri == null) restoredUnprotected = true else answered()
        }
    }

    val folderLauncher = rememberHandoffLauncher(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val tree = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && tree != null) {
            val granted = SafBackupStore.persist(context, tree, null)
            if (granted == null) {
                // Not moved on. Being dropped onto a healthy-looking dashboard
                // believing backups are set up, when the grant never stuck, is
                // the same failure this screen exists to prevent — one step
                // further along.
                vm.reportFolderRefused()
            } else {
                vm.onFolderChosen(granted)
                answered()
            }
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
            text = stringResource(
                if (restoredUnprotected) R.string.welcome_protect_title else R.string.welcome_title,
            ),
            style = DayBookTheme.type.screenTitle,
            color = colors.ink,
        )
        Text(
            text = stringResource(
                if (restoredUnprotected) R.string.welcome_protect_body else R.string.welcome_body,
            ),
            style = DayBookTheme.type.body,
            color = colors.inkSoft,
        )

        if (state.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.indigo,
                trackColor = colors.rule,
            )
        }

        Button(
            onClick = {
                if (restoredUnprotected) folderLauncher(SafBackupStore.pickFolder())
                else fileLauncher(openBackup())
            },
            enabled = !state.busy,
            shape = RoundedCornerShape(Radius.input),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.indigo,
                contentColor = colors.card,
                disabledContainerColor = colors.rule,
                disabledContentColor = colors.inkSoft,
            ),
            modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
        ) {
            Text(
                text = stringResource(
                    if (restoredUnprotected) R.string.backup_folder_none else R.string.welcome_restore,
                ),
                style = DayBookTheme.type.body,
            )
        }

        // No snackbar host out here — this screen sits above the NavHost — so
        // anything worth saying is said in place.
        //
        // *Every* message, through the same renderer the Backup screen uses.
        // Only `FolderRefused` was handled here, so a restore that was refused
        // — the wrong passphrase, a truncated file, a backup from a newer
        // release — closed the sheet and left the screen exactly as it was.
        // Six carefully distinguished failure reasons (§21.9 F, G) reached the
        // one screen that had no way to say any of them.
        state.message?.let { message ->
            Text(
                text = backupMessage(message),
                style = DayBookTheme.type.caption,
                color = if (message.isFailure) colors.vermilion else colors.inkSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = vm::dismissMessage),
            )
        }

        TextButton(
            onClick = { answered() },
            enabled = !state.busy && !answering,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (restoredUnprotected) R.string.welcome_later else R.string.welcome_fresh,
                ),
                color = colors.inkSoft,
            )
        }
    }

    state.restore?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = vm::cancelRestore,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.card,
            shape = Radius.sheetTop,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(Space.gutter),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Text(
                    text = stringResource(R.string.welcome_restore),
                    style = DayBookTheme.type.screenTitle,
                    color = colors.ink,
                )
                Text(
                    text = stringResource(R.string.welcome_restore_body),
                    style = DayBookTheme.type.body,
                    color = colors.inkSoft,
                )
                if (draft.locked) {
                    Text(
                        text = stringResource(
                            if (draft.wrong) R.string.backup_wrong_passphrase
                            else R.string.backup_needs_passphrase,
                        ),
                        style = DayBookTheme.type.body,
                        color = if (draft.wrong) colors.vermilion else colors.inkSoft,
                    )
                    SecretField(draft.typed, stringResource(R.string.backup_passphrase), vm::onRestorePassphraseTyped)
                }
                Button(
                    onClick = { vm.confirmRestore(ImportMode.REPLACE) },
                    enabled = !draft.locked || draft.typed.isNotEmpty(),
                    shape = RoundedCornerShape(Radius.input),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.indigo,
                        contentColor = colors.card,
                        disabledContainerColor = colors.rule,
                        disabledContentColor = colors.inkSoft,
                    ),
                    modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
                ) {
                    Text(
                        text = stringResource(if (draft.locked) R.string.backup_unlock else R.string.welcome_restore_confirm),
                        style = DayBookTheme.type.body,
                    )
                }
                TextButton(onClick = vm::cancelRestore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel), color = colors.inkSoft)
                }
            }
        }
    }
}

/**
 * FR-DAT-12 — all three kinds of file the app has ever written.
 *
 * An encrypted `.daybook`, a plain one, and a `daybook-export.json` from before
 * either existed. A picker filtered to one of them would hide the others from a
 * user who cannot be expected to know which they have.
 */
internal fun openBackup() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(BackupCodec.MIME, "application/json", "*/*"))
}
