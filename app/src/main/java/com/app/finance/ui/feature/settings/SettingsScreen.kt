package com.app.finance.ui.feature.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.data.export.ImportMode
import com.app.finance.data.export.ImportOutcome
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.ThemeChoice
import com.app.finance.ui.common.ActionRow
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.ToggleRow
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import com.app.finance.ui.lock.LocalLockController
import com.app.finance.ui.lock.LockAvailability
import com.app.finance.ui.lock.lockAvailability
import com.app.finance.ui.lock.rememberHandoffLauncher
import androidx.compose.runtime.remember

/**
 * Settings — 04 §7: "Export, import, rebuild aggregates, delete all data, theme."
 *
 * The screen M4 deferred the dashboard's ⚙ to, and the home of the only feature
 * PRD §6.6 argues for on grounds of trust rather than utility:
 *
 * > "Users do not trust an app with their financial history until they have
 * > proof they can extract it. It is also the only backup mechanism in a
 * > no-server product."
 *
 * Every file operation goes through `ACTION_CREATE_DOCUMENT` or
 * `ACTION_OPEN_DOCUMENT`, which need no permission and write where the user
 * says. That is what makes NFR-SEC-01 — "no data leaves the device except by
 * explicit user-initiated export" — structural rather than a promise: there is
 * no code path here that can choose a destination on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onManageRecurring: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenBackup: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            SettingsViewModel(
                exporter = container.exporter,
                importer = container.importer,
                settings = container.settingsRepo,
                clock = container.clock,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver

    BackHandler(onBack = onBack)

    // FR-APP-04's two pieces of context: whether this phone can gate at all,
    // and the controller that knows the app is about to background itself.
    val lock = LocalLockController.current
    val lockAvailable = remember(context) { lockAvailability(context) }

    val jsonLauncher = rememberHandoffLauncher(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            vm.exportJson { resolver.openOutputStream(target) }
        }
    }
    val csvLauncher = rememberHandoffLauncher(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            vm.exportCsv { resolver.openOutputStream(target) }
        }
    }
    val importLauncher = rememberHandoffLauncher(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val source = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && source != null) {
            vm.offerImport { resolver.openInputStream(source) }
        }
    }

    state.message?.let { message ->
        val text = settingsMessage(message)
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
            text = stringResource(R.string.settings_title),
            style = DayBookTheme.type.screenTitle,
            color = DayBookTheme.colors.ink,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s3),
        )

        // 05 §8 — a skeleton or a bar, never a spinner over a frozen screen.
        // These are the only operations in the app the user waits for.
        if (state.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = DayBookTheme.colors.indigo,
                trackColor = DayBookTheme.colors.rule,
            )
        }

        SectionHeader(stringResource(R.string.settings_data))
        // Above the manual three deliberately: this is the one that happens
        // without being remembered, and PRD §6.6 calls export "the only backup
        // mechanism in a no-server product" -- a mechanism nobody runs is not
        // one. The three below stay exactly as they were (FR-DAT-01 … 03).
        ActionRow(
            title = stringResource(R.string.settings_backup),
            hint = stringResource(R.string.settings_backup_hint),
            enabled = !state.busy,
            onClick = onOpenBackup,
        )
        ActionRow(
            title = stringResource(R.string.export_json),
            hint = stringResource(R.string.export_json_hint),
            enabled = !state.busy,
            onClick = { jsonLauncher(createDocument(MIME_JSON, JSON_NAME)) },
        )
        ActionRow(
            title = stringResource(R.string.export_csv),
            hint = stringResource(R.string.export_csv_hint),
            enabled = !state.busy,
            onClick = { csvLauncher(createDocument(MIME_ZIP, CSV_NAME)) },
        )
        ActionRow(
            title = stringResource(R.string.import_backup),
            hint = stringResource(R.string.import_backup_hint),
            enabled = !state.busy,
            onClick = { importLauncher(openDocument(MIME_JSON)) },
        )

        SectionHeader(stringResource(R.string.settings_maintenance))
        ActionRow(
            title = stringResource(R.string.open_reports),
            hint = stringResource(R.string.open_reports_hint),
            enabled = !state.busy,
            onClick = onOpenReports,
        )
        ActionRow(
            title = stringResource(R.string.manage_recurring),
            hint = null,
            enabled = true,
            onClick = onManageRecurring,
        )
        ActionRow(
            title = stringResource(R.string.rebuild_aggregates),
            hint = stringResource(R.string.rebuild_aggregates_hint),
            enabled = !state.busy,
            onClick = vm::rebuildAggregates,
        )

        SectionHeader(stringResource(R.string.settings_appearance))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            ThemeChoice.entries.forEach { choice ->
                DayBookChip(
                    label = stringResource(
                        when (choice) {
                            ThemeChoice.SYSTEM -> R.string.theme_system
                            ThemeChoice.LIGHT -> R.string.theme_light
                            ThemeChoice.DARK -> R.string.theme_dark
                        },
                    ),
                    selected = state.theme == choice,
                    onClick = { vm.setTheme(choice) },
                )
            }
        }

        // NFR-SEC-04 and FR-APP-04. Both are P1 in the SRS and both say
        // "optional" in the requirement itself, so both are off until asked
        // for and neither is a surprise.
        SectionHeader(stringResource(R.string.settings_privacy))
        ToggleRow(
            title = stringResource(R.string.secure_screen),
            hint = stringResource(R.string.secure_screen_hint),
            checked = state.secureScreen,
            onChange = vm::setSecureScreen,
        )
        ToggleRow(
            title = stringResource(R.string.app_lock),
            hint = when (lockAvailable) {
                LockAvailability.AVAILABLE -> stringResource(R.string.app_lock_hint)
                // FR-IS-05's shape, reused: a disabled control with the reason
                // beside it beats one that fails when tapped.
                LockAvailability.NO_CREDENTIAL -> stringResource(R.string.app_lock_no_credential)
                LockAvailability.UNSUPPORTED -> stringResource(R.string.app_lock_unsupported)
            },
            checked = state.appLock,
            enabled = lockAvailable == LockAvailability.AVAILABLE,
            onChange = { on ->
                vm.setAppLock(on)
                // Turning it off must not leave the gate standing until the
                // next launch, and turning it on must not lock the user out of
                // the screen they are standing on.
                if (!on) lock.release() else lock.unlock()
            },
        )

        SectionHeader(stringResource(R.string.delete_all))
        ActionRow(
            title = stringResource(R.string.delete_all),
            hint = stringResource(R.string.delete_all_hint),
            enabled = !state.busy,
            destructive = true,
            onClick = vm::openDeleteAll,
        )

        Box(Modifier.height(Space.s5))
    }

    // FR-DAT-03's choice, asked once the file is picked.
    if (state.importPicker != null) {
        ModalBottomSheet(
            onDismissRequest = vm::cancelImport,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DayBookTheme.colors.card,
            shape = Radius.sheetTop,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = Space.s3),
            ) {
                SectionHeader(stringResource(R.string.import_choose_mode))
                ActionRow(
                    title = stringResource(R.string.import_merge),
                    hint = stringResource(R.string.import_merge_hint),
                    enabled = true,
                    onClick = { vm.confirmImport(ImportMode.MERGE) },
                )
                // Second, and marked destructive: it deletes everything on the
                // phone before it writes a row, and a user who taps the first
                // thing they see should land on the safe one.
                ActionRow(
                    title = stringResource(R.string.import_replace),
                    hint = stringResource(R.string.import_replace_hint),
                    enabled = true,
                    destructive = true,
                    onClick = { vm.confirmImport(ImportMode.REPLACE) },
                )
            }
        }
    }

    // FR-DAT-06 — 05 §8's single exception to "no confirmation dialogs".
    state.deleteTyped?.let { typed ->
        DeleteAllSheet(
            typed = typed,
            armed = state.deleteArmed,
            onTyped = vm::onDeleteTyped,
            onConfirm = vm::confirmDeleteAll,
            onDismiss = vm::cancelDeleteAll,
        )
    }
}

/**
 * The typed confirmation — 05 §8.
 *
 * > "Every destructive action is undoable for 5 seconds. No confirmation dialogs
 * > for deletes … **The exception is 'delete all data,' which requires typed
 * > confirmation, because there is no undo for it.**"
 *
 * The word is not translated. A confirmation phrase that changes with the
 * device language is one somebody gets wrong at the moment they most need to
 * get it right, and the point of typing is deliberation rather than vocabulary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteAllSheet(
    typed: String,
    armed: Boolean,
    onTyped: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val hint = stringResource(R.string.delete_all_hint_field)

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
                text = stringResource(R.string.delete_all),
                style = DayBookTheme.type.screenTitle,
                color = colors.ink,
            )
            Text(
                text = stringResource(R.string.delete_all_body),
                style = DayBookTheme.type.body,
                color = colors.inkSoft,
            )
            BasicTextField(
                value = typed,
                onValueChange = onTyped,
                singleLine = true,
                textStyle = DayBookTheme.type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.vermilion),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Sizes.minTouchTarget)
                    .drawBehind {
                        drawLine(
                            color = if (armed) colors.vermilion else colors.rule,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 2f,
                        )
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (typed.isEmpty()) {
                            Text(hint, style = DayBookTheme.type.body, color = colors.inkSoft)
                        }
                        inner()
                    }
                },
            )
            Button(
                onClick = onConfirm,
                // Disabled until the word is exact. The typing *is* the
                // confirmation; a live button beside an empty field would make
                // it decoration.
                enabled = armed,
                shape = RoundedCornerShape(Radius.input),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.vermilion,
                    contentColor = colors.card,
                    disabledContainerColor = colors.rule,
                    disabledContentColor = colors.inkSoft,
                ),
                modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
            ) {
                Text(stringResource(R.string.delete_all), style = DayBookTheme.type.body)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel), color = colors.inkSoft)
            }
        }
    }
}

/**
 * A [SettingsMessage] as the sentence the user reads.
 *
 * Resolved here rather than in the ViewModel so `domain` and the state machine
 * stay free of resources, and so the counts FR-DAT-03 requires — "inserted,
 * updated, and skipped" — reach the copy as arguments rather than as a string
 * somebody patched afterwards.
 */
@Composable
private fun settingsMessage(message: SettingsMessage): String = when (message) {
    is SettingsMessage.Exported ->
        pluralStringResource(R.plurals.export_done, message.rows, message.rows)
    is SettingsMessage.Imported -> stringResource(
        R.string.import_done,
        message.counts.inserted,
        message.counts.updated,
        message.counts.skipped,
    )
    is SettingsMessage.ImportFailed -> stringResource(
        when (message.failure) {
            ImportOutcome.Failure.NEWER_SCHEMA -> R.string.import_newer
            ImportOutcome.Failure.UNREADABLE -> R.string.import_unreadable
            ImportOutcome.Failure.DANGLING_REFERENCE -> R.string.import_dangling
            ImportOutcome.Failure.REJECTED -> R.string.import_rejected
        },
    )
    SettingsMessage.ExportFailed -> stringResource(R.string.export_failed)
    SettingsMessage.Rebuilt -> stringResource(R.string.rebuild_done)
    SettingsMessage.Deleted -> stringResource(R.string.delete_all_done)
}

private fun createDocument(mime: String, name: String) =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        putExtra(Intent.EXTRA_TITLE, name)
    }

private fun openDocument(mime: String) =
    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        // Some pickers report a JSON file as octet-stream; without this the
        // file the user just exported is greyed out in the picker they export
        // to, which is not a defensible first impression of a backup feature.
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(mime, MIME_ANY))
    }

private const val MIME_JSON = "application/json"
private const val MIME_ZIP = "application/zip"
private const val MIME_ANY = "application/octet-stream"
private const val JSON_NAME = "daybook-export.json"
private const val CSV_NAME = "daybook-csv.zip"
