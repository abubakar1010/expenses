package com.app.finance.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.ImportMode
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.ui.feature.backup.BackupMessage
import com.app.finance.ui.feature.backup.BackupViewModel
import com.app.finance.ui.feature.backup.SecretField
import com.app.finance.ui.lock.LocalLockController
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
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
fun WelcomeScreen(container: AppContainer) {
    val vm: BackupViewModel = viewModel(
        factory = viewModelFactory {
            BackupViewModel(backups = container.backupRepo, settings = container.settingsRepo)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = KhataTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock = LocalLockController.current

    fun answered() = scope.launch { container.settingsRepo.setOnboarded() }

    val fileLauncher = rememberLauncherForActivityResult(
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
    LaunchedEffect(state.message) {
        if (state.message is BackupMessage.Restored) answered()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.s3, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.welcome_title),
            style = KhataTheme.type.screenTitle,
            color = colors.ink,
        )
        Text(
            text = stringResource(R.string.welcome_body),
            style = KhataTheme.type.body,
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
                lock.suppressNextBackground()
                fileLauncher.launch(openBackup())
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
            Text(stringResource(R.string.welcome_restore), style = KhataTheme.type.body)
        }

        TextButton(
            onClick = { answered() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.welcome_fresh), color = colors.inkSoft)
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
                    style = KhataTheme.type.screenTitle,
                    color = colors.ink,
                )
                if (draft.locked) {
                    Text(
                        text = stringResource(
                            if (draft.wrong) R.string.backup_wrong_passphrase
                            else R.string.backup_needs_passphrase,
                        ),
                        style = KhataTheme.type.body,
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
                        text = stringResource(if (draft.locked) R.string.backup_unlock else R.string.welcome_restore),
                        style = KhataTheme.type.body,
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
 * An encrypted `.khata`, a plain one, and a `khata-export.json` from before
 * either existed. A picker filtered to one of them would hide the others from a
 * user who cannot be expected to know which they have.
 */
internal fun openBackup() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(BackupCodec.MIME, "application/json", "*/*"))
}
