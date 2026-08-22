package com.app.finance.ui.feature.backup

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.data.backup.SafBackupStore
import com.app.finance.data.export.BackupCodec
import com.app.finance.data.export.ImportMode
import com.app.finance.data.export.ImportOutcome
import com.app.finance.data.repo.BackupOutcome
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.BackupInterval
import com.app.finance.ui.common.ActionRow
import com.app.finance.ui.common.KhataChip
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.openBackup
import com.app.finance.ui.lock.LocalLockController
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Backup — FR-DAT-07 … FR-DAT-12.
 *
 * The screen PRD §6.6's claim needs to be true rather than merely available:
 *
 * > "Users do not trust an app with their financial history until they have
 * > proof they can extract it. It is also the only backup mechanism in a
 * > no-server product."
 *
 * Export already produced the artifact. What this adds is that it happens
 * without being remembered, lands somewhere the user chose once, keeps more than
 * one generation, and says out loud when it last ran.
 *
 * **The two sentences at the top of the folder section are the design.** They
 * say what survives an uninstall (these files, because the folder is the user's
 * and not the app's), what does not (a lost phone, unless a copy went
 * elsewhere), and when backups actually happen (on launch — 05 §12 has no
 * notification and 04 §6 no background service, so "every day" alone would be a
 * promise the app cannot keep). 05 §9 asks for the fact and then the action;
 * an app that let someone believe they were covered when they were not would be
 * worse than one with no backup at all.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val vm: BackupViewModel = viewModel(
        factory = viewModelFactory {
            BackupViewModel(backups = container.backupRepo, settings = container.settingsRepo)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val lock = LocalLockController.current

    BackHandler(onBack = onBack)

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val tree = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && tree != null) {
            // Taking the grant is the Android half and stays out of the
            // ViewModel; what crosses is a string.
            SafBackupStore.persist(context, tree, state.settings.treeUri)
                ?.let(vm::onFolderChosen)
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val source = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && source != null) {
            vm.offerRestore { resolver.openInputStream(source) }
        }
    }

    state.message?.let { message ->
        val text = backupMessage(message)
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            vm.dismissMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.backup_title),
            style = KhataTheme.type.screenTitle,
            color = KhataTheme.colors.ink,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s3),
        )

        // 04 §5.3 — "foreground with progress". Writing five years of ledger and
        // deriving a passphrase both take real seconds on the reference device.
        if (state.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = KhataTheme.colors.indigo,
                trackColor = KhataTheme.colors.rule,
            )
        }

        LastBackup(state)

        SectionHeader(stringResource(R.string.backup_folder))
        Explainer(
            listOf(
                stringResource(R.string.backup_when),
                stringResource(R.string.backup_survives),
            ),
        )
        ActionRow(
            title = state.folderLabel ?: stringResource(R.string.backup_folder_none),
            hint = when {
                state.folderMissing -> stringResource(R.string.backup_folder_gone)
                state.hasFolder -> stringResource(R.string.backup_folder_change)
                else -> stringResource(R.string.backup_folder_pick)
            },
            enabled = !state.busy,
            destructive = state.folderMissing,
            onClick = {
                // Or the app-lock gate fires when the picker backgrounds us.
                lock.suppressNextBackground()
                folderLauncher.launch(SafBackupStore.pickFolder())
            },
        )

        SectionHeader(stringResource(R.string.backup_how_often))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            BackupInterval.entries.forEach { interval ->
                KhataChip(
                    label = stringResource(
                        when (interval) {
                            BackupInterval.OFF -> R.string.backup_off
                            BackupInterval.DAILY -> R.string.backup_daily
                            BackupInterval.WEEKLY -> R.string.backup_weekly
                        },
                    ),
                    selected = state.settings.interval == interval,
                    onClick = { vm.setInterval(interval) },
                )
            }
        }

        SectionHeader(stringResource(R.string.backup_keep))
        Explainer(listOf(stringResource(R.string.backup_keep_hint)))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            KEEP_CHOICES.forEach { keep ->
                KhataChip(
                    label = keep.toString(),
                    selected = state.settings.keep == keep,
                    onClick = { vm.setKeep(keep) },
                )
            }
        }

        SectionHeader(stringResource(R.string.backup_encrypt))
        ActionRow(
            title = stringResource(
                if (state.settings.encrypted) R.string.toggle_on else R.string.toggle_off,
            ),
            hint = stringResource(R.string.backup_encrypt_hint),
            enabled = !state.busy,
            onClick = { if (state.settings.encrypted) vm.clearPassphrase() else vm.openPassphrase() },
        )

        SectionHeader(stringResource(R.string.settings_data))
        ActionRow(
            title = stringResource(R.string.backup_now),
            hint = null,
            enabled = !state.busy && state.hasFolder,
            onClick = vm::backUpNow,
        )
        ActionRow(
            title = stringResource(R.string.backup_send_copy),
            hint = stringResource(R.string.backup_send_copy_hint),
            enabled = !state.busy,
            onClick = {
                val id = state.newestId
                if (id == null) {
                    vm.reportNothingToSend()
                } else {
                    lock.suppressNextBackground()
                    context.startActivity(shareBackup(Uri.parse(id), state.newestName.orEmpty()))
                }
            },
        )
        ActionRow(
            title = stringResource(R.string.backup_restore),
            hint = stringResource(R.string.backup_restore_hint),
            enabled = !state.busy,
            onClick = {
                lock.suppressNextBackground()
                fileLauncher.launch(openBackup())
            },
        )

        Box(Modifier.height(Space.s5))
    }

    state.passphrase?.let { draft ->
        PassphraseSheet(
            draft = draft,
            onFirst = vm::onPassphraseTyped,
            onSecond = vm::onPassphraseRepeated,
            onSave = vm::savePassphrase,
            onDismiss = vm::cancelPassphrase,
        )
    }

    state.restore?.let { draft ->
        RestoreSheet(
            draft = draft,
            onTyped = vm::onRestorePassphraseTyped,
            onConfirm = vm::confirmRestore,
            onDismiss = vm::cancelRestore,
        )
    }
}

/**
 * When it last ran, and how much of the ledger it caught.
 *
 * The row that makes the feature trustworthy. "Never backed up" is the state
 * this app has shipped in until now, and it is worth saying rather than leaving
 * to be inferred from a blank space.
 */
@Composable
private fun LastBackup(state: BackupUiState) {
    val colors = KhataTheme.colors
    val at = state.settings.lastAt
    val rows = state.settings.lastCount

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Text(
            text = if (at == null) {
                stringResource(R.string.backup_never)
            } else {
                stringResource(R.string.backup_last, friendly(at))
            },
            style = KhataTheme.type.sectionFigure,
            color = if (at == null) colors.inkSoft else colors.ink,
        )
        if (at != null && rows != null) {
            Text(
                text = pluralStringResource(R.plurals.backup_last_rows, rows, rows, state.newestName.orEmpty()),
                style = KhataTheme.type.caption,
                color = colors.inkSoft,
            )
        }
    }
}

/** Prose under a section header. Not a row — nothing here is tappable. */
@Composable
private fun Explainer(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s1),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        lines.forEach {
            Text(it, style = KhataTheme.type.caption, color = KhataTheme.colors.inkSoft)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseSheet(
    draft: PassphraseDraft,
    onFirst: (String) -> Unit,
    onSecond: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KhataTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                text = stringResource(R.string.backup_encrypt),
                style = KhataTheme.type.screenTitle,
                color = colors.ink,
            )
            // Said again here, at the moment the passphrase is chosen, because
            // this is the last point at which the warning can still be acted on.
            Text(
                text = stringResource(R.string.backup_encrypt_hint),
                style = KhataTheme.type.body,
                color = colors.inkSoft,
            )
            SecretField(draft.first, stringResource(R.string.backup_passphrase), onFirst)
            SecretField(draft.second, stringResource(R.string.backup_passphrase_again), onSecond)

            draft.error?.let {
                Text(
                    text = stringResource(
                        when (it) {
                            PassphraseError.TOO_SHORT -> R.string.backup_passphrase_short
                            PassphraseError.DIFFERS -> R.string.backup_passphrase_differs
                        },
                    ),
                    style = KhataTheme.type.caption,
                    color = colors.vermilion,
                )
            }

            Button(
                onClick = onSave,
                enabled = draft.armed,
                shape = RoundedCornerShape(Radius.input),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.indigo,
                    contentColor = colors.card,
                    disabledContainerColor = colors.rule,
                    disabledContentColor = colors.inkSoft,
                ),
                modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
            ) {
                Text(stringResource(R.string.backup_passphrase_save), style = KhataTheme.type.body)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel), color = colors.inkSoft)
            }
        }
    }
}

/**
 * The file is picked; the mode is not.
 *
 * The same two buttons and the same words `SettingsScreen` uses for an import,
 * because it is the same operation and a second vocabulary for it would be a
 * second thing to learn. The passphrase field appears only for a file that
 * wants one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreSheet(
    draft: RestoreDraft,
    onTyped: (String) -> Unit,
    onConfirm: (ImportMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KhataTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                text = stringResource(R.string.import_choose_mode),
                style = KhataTheme.type.screenTitle,
                color = colors.ink,
            )

            if (draft.locked) {
                Text(
                    text = stringResource(
                        if (draft.wrong) R.string.backup_wrong_passphrase else R.string.backup_needs_passphrase,
                    ),
                    style = KhataTheme.type.body,
                    color = if (draft.wrong) colors.vermilion else colors.inkSoft,
                )
                SecretField(draft.typed, stringResource(R.string.backup_passphrase), onTyped)
            }

            val ready = !draft.locked || draft.typed.isNotEmpty()
            ModeButton(
                title = stringResource(R.string.import_replace),
                hint = stringResource(R.string.import_replace_hint),
                enabled = ready,
                onClick = { onConfirm(ImportMode.REPLACE) },
            )
            ModeButton(
                title = stringResource(R.string.import_merge),
                hint = stringResource(R.string.import_merge_hint),
                enabled = ready,
                onClick = { onConfirm(ImportMode.MERGE) },
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel), color = colors.inkSoft)
            }
        }
    }
}

@Composable
private fun ModeButton(title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = KhataTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowPlain)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { role = Role.Button }
            .padding(vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Text(title, style = KhataTheme.type.body, color = if (enabled) colors.ink else colors.inkSoft)
        Text(hint, style = KhataTheme.type.caption, color = colors.inkSoft)
    }
}

@Composable
internal fun SecretField(value: String, hint: String, onChange: (String) -> Unit) {
    val colors = KhataTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        textStyle = KhataTheme.type.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.indigo),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .drawBehind {
                drawLine(
                    color = if (value.isEmpty()) colors.rule else colors.indigo,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2f,
                )
            },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(hint, style = KhataTheme.type.body, color = colors.inkSoft)
                }
                inner()
            }
        },
    )
}

@Composable
private fun backupMessage(message: BackupMessage): String = when (message) {
    is BackupMessage.Done -> stringResource(R.string.backup_done, message.name)
    is BackupMessage.Failed -> stringResource(
        when (message.failure) {
            BackupOutcome.Failure.NO_FOLDER -> R.string.backup_no_folder
            BackupOutcome.Failure.UNREACHABLE -> R.string.backup_folder_gone
            BackupOutcome.Failure.WRITE_FAILED -> R.string.backup_write_failed
        },
    )
    is BackupMessage.Restored -> stringResource(
        R.string.import_done,
        message.counts.inserted,
        message.counts.updated,
        message.counts.skipped,
    )
    is BackupMessage.RestoreRefused -> stringResource(
        when (message.failure) {
            ImportOutcome.Failure.NEWER_SCHEMA -> R.string.import_newer
            ImportOutcome.Failure.UNREADABLE -> R.string.import_unreadable
            ImportOutcome.Failure.DANGLING_REFERENCE -> R.string.import_dangling
            ImportOutcome.Failure.REJECTED -> R.string.import_rejected
        },
    )
    BackupMessage.PassphraseSet -> stringResource(R.string.backup_passphrase_set)
    BackupMessage.PassphraseCleared -> stringResource(R.string.backup_passphrase_cleared)
    BackupMessage.NothingToSend -> stringResource(R.string.backup_send_none)
}

/**
 * Hands the newest backup to whatever the user has that can carry it — Drive,
 * email, a chat app.
 *
 * **This is the whole of the "off the device" story, and it is deliberate.**
 * FR-APP-01 forbids the `INTERNET` permission outright and the release gate
 * greps the merged manifest for it, so Khata cannot upload anything and never
 * will. What it can do is pass a file to an app that already has that
 * permission and the user's account, which is the same trade `ACTION_SEND` was
 * built for. The tap is the user's, which is also what keeps NFR-SEC-01 true.
 */
private fun shareBackup(document: Uri, name: String): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = BackupCodec.MIME
            putExtra(Intent.EXTRA_STREAM, document)
            // Both: the extra is what receivers read, and the ClipData is what
            // the framework uses to work out which URI the grant applies to.
            clipData = ClipData.newRawUri(name, document)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        null,
    )

private fun friendly(at: Long): String =
    FRIENDLY.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

private val FRIENDLY: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private val KEEP_CHOICES = listOf(3, 5, 10, 20)

