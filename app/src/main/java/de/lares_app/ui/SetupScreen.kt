/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import android.app.Activity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Switch
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectableGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.ui.theme.previewColorScheme
import de.lares_app.ui.theme.wallpaperColorsAvailable
import de.lares_app.core.data.AppTheme
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.Protection
import de.lares_app.core.unlock.UnlockManager
import de.lares_app.platform.RecoveryExport
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.ScrollState

@Composable
/**
 * @param onDone called after the new recovery code was confirmed (needed for [SetupMode.CHANGE];
 *   for the other modes the app switches to the main screen by itself).
 * @param onCancel leaving the flow before a code was stored ([SetupMode.CHANGE] only).
 */
fun SetupScreen(mode: SetupMode, onDone: () -> Unit = {}, onCancel: () -> Unit = {}, vm: SetupViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val needsCode by vm.needsCode.collectAsStateWithLifecycle()
    var showUnlock by remember { mutableStateOf(false) }
    var restorePackage by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readNBytesCompat(MAX_IMPORT_BYTES + 1).takeIf { it.size <= MAX_IMPORT_BYTES }?.decodeToString()
                        }
                    }.getOrNull()
                }
                vm.importForRestore(text)?.let { restorePackage = it }
            }
        }
    }
    LaunchedEffect(mode) { vm.start(mode) }
    LaunchedEffect(Unit) { vm.done.collect { onDone() } }
    // One page back – the same for the back arrow and the system back gesture. None on the first
    // page, and none on the recovery code (the new code is already stored: leaving would lose it).
    val goBack: (() -> Unit)? = when {
        busy -> null
        step is SetupStep.Password || step is SetupStep.Totp || step == SetupStep.BlockingMode -> vm::back
        step == SetupStep.Intro || step is SetupStep.Tutorial -> vm::back
        step == SetupStep.ChooseMethod && mode == SetupMode.FIRST -> vm::back
        step == SetupStep.ChooseMethod && mode == SetupMode.CHANGE -> onCancel
        else -> null
    }
    // While storing, back is swallowed (leaving mid-store could lose the new recovery code).
    BackHandler(enabled = busy || goBack != null) { goBack?.invoke() }
    BackHandler(enabled = step is SetupStep.Recovery || step == SetupStep.Done) {}

    // Every page (and tutorial page) starts at the top, not where the previous one was scrolled to.
    val scrollKey = (step as? SetupStep.Tutorial)?.let { "tutorial${it.page}" } ?: step?.javaClass?.simpleName.orEmpty()
    val scroll = rememberSaveable(scrollKey, saver = ScrollState.Saver) { ScrollState(0) }
    Scaffold(
        bottomBar = {
            val current = step?.let(::stageOf)
            if (current != null) SetupProgress(stagesFor(mode), current)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (needsCode) {
                ErrorText(stringResource(R.string.setup_unlock_expired))
                OutlinedButton(onClick = { showUnlock = true }) { Text(stringResource(R.string.home_unlock)) }
            }
            when (val s = step ?: return@Column) {
                SetupStep.Welcome -> {
                    val theme by vm.theme.collectAsStateWithLifecycle()
                    Welcome(theme, onSelect = vm::setTheme, onContinue = vm::welcomeDone)
                }
                is SetupStep.Tutorial -> TutorialPage(s.page, onBack = vm::back, onSkip = vm::skipTutorial, onNext = vm::tutorialNext)
                SetupStep.Intro -> Intro(onBack = goBack, onStart = vm::skipIntro)
                SetupStep.BlockingMode -> {
                    val current by vm.blockingMode.collectAsStateWithLifecycle()
                    val protections by vm.protections.collectAsStateWithLifecycle()
                    ChooseBlockingMode(
                        current = current,
                        protections = protections,
                        onBack = goBack,
                        onChoose = vm::chooseBlockingMode,
                        onWebsiteBlocking = vm::setWebsiteBlocking,
                        onProtection = vm::setProtection,
                        onContinue = vm::continueFromMode,
                    )
                }
                SetupStep.ChooseMethod -> {
                    val restoreMessage by vm.restoreMessage.collectAsStateWithLifecycle()
                    ChooseMethod(
                        mode,
                        onPassword = vm::choosePassword,
                        onTotp = vm::chooseTotp,
                        onCancel = onCancel,
                        onRestore = if (mode == SetupMode.FIRST) {
                            { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                        } else {
                            null
                        },
                        restoreMessage = restoreMessage,
                    )
                }
                is SetupStep.Password -> PasswordStep(s.error, busy, onSubmit = vm::submitPassword, onBack = vm::back)
                is SetupStep.Totp -> TotpStep(s, busy, onConfirm = vm::confirmTotp, onBack = vm::back)
                is SetupStep.Recovery -> RecoveryStep(s.code, busy, onFinish = vm::finish)
                SetupStep.Done -> DoneStep(busy, onClose = vm::closeDone)
            }
            // "Back" like on the password page (which, like the authenticator page, has its own;
            // changing the code has "Cancel" instead).
            val ownBackButton = step is SetupStep.Password || step is SetupStep.Totp || step == SetupStep.Intro || step is SetupStep.Tutorial ||
                step == SetupStep.BlockingMode || (step == SetupStep.ChooseMethod && mode == SetupMode.CHANGE)
            if (goBack != null && !ownBackButton) {
                TextButton(onClick = goBack) { Text(stringResource(R.string.back)) }
            }
        }
    }
    restorePackage?.let { pkg ->
        TotpRestoreDialog(
            title = stringResource(R.string.setup_restore),
            body = stringResource(R.string.setup_restore_body),
            confirm = stringResource(R.string.backup_totp_take_over),
            restore = { code -> vm.restoreTotp(pkg, code) },
            onDone = {
                restorePackage = null
                vm.finishRestore()
            },
            onDismiss = {
                restorePackage = null
                vm.restoreCancelled()
            },
        )
    }
    if (showUnlock) {
        UnlockDialog(onDismiss = { showUnlock = false }, onUnlocked = vm::codeEntered)
    }
}

@Composable
internal fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium)

@Composable
private fun Wordmark() {
    Image(
        painterResource(R.drawable.logo_wordmark),
        contentDescription = stringResource(R.string.app_name),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.width(170.dp),
    )
    Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.titleMedium)
}

/**
 * First screen: logo, a short welcome and – on Android 12+ – the user's own choice of colors
 * (two previews side by side, like the code method cards; the choice applies right away).
 */
@Composable
private fun Welcome(selected: AppTheme, onSelect: (AppTheme) -> Unit, onContinue: () -> Unit) {
    Wordmark()
    Title(stringResource(R.string.setup_welcome_title))
    Text(stringResource(R.string.setup_welcome_body))
    if (wallpaperColorsAvailable) {
        Text(stringResource(R.string.setup_theme_title), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePreviewCard(AppTheme.LARES, R.string.theme_lares, selected == AppTheme.LARES, Modifier.weight(1f)) { onSelect(AppTheme.LARES) }
            ThemePreviewCard(AppTheme.WALLPAPER, R.string.theme_wallpaper, selected == AppTheme.WALLPAPER, Modifier.weight(1f)) {
                onSelect(AppTheme.WALLPAPER)
            }
        }
        Text(stringResource(R.string.setup_theme_hint), style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_continue)) }
}

/** A tiny mock screen (app bar, card, button, accent dots) in the colors of [theme]. */
@Composable
private fun ThemePreviewCard(theme: AppTheme, label: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = previewColorScheme(theme)
    OutlinedCard(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(c.background).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.55f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.onBackground))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.primaryContainer).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.fillMaxWidth(0.7f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.onPrimaryContainer))
                    Box(Modifier.fillMaxWidth(0.45f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.onPrimaryContainer.copy(alpha = 0.5f)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 40.dp, height = 14.dp).clip(RoundedCornerShape(7.dp)).background(c.primary))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.secondary))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.tertiary))
                }
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(0.4f).height(10.dp).background(c.primaryContainer))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun Intro(onBack: (() -> Unit)?, onStart: () -> Unit) {
    Title(stringResource(R.string.setup_intro_title))
    Text(stringResource(R.string.setup_intro_body))
    BackAndNext(onBack) { Button(onClick = onStart) { Text(stringResource(R.string.setup_intro_start)) } }
}

/** "Back" on the left (if any), the page's main action on the right – like [NavButtons]. */
@Composable
internal fun BackAndNext(onBack: (() -> Unit)?, next: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } else Spacer(Modifier)
        next()
    }
}

/** First setup: the guardian may already choose how Lares detects apps (free now, code later). */
@Composable
private fun ChooseBlockingMode(
    current: BlockingMode?,
    protections: SetupProtections,
    onBack: (() -> Unit)?,
    onChoose: (BlockingMode) -> Unit,
    onWebsiteBlocking: (Boolean) -> Unit,
    onProtection: (Protection, Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    Title(stringResource(R.string.setup_mode_title))
    Text(stringResource(R.string.setup_mode_body))
    MethodCard(
        stringResource(R.string.blocking_mode_accessibility_recommended),
        stringResource(R.string.blocking_mode_accessibility_desc),
        selected = current == BlockingMode.ACCESSIBILITY,
    ) { onChoose(BlockingMode.ACCESSIBILITY) }
    MethodCard(
        stringResource(R.string.blocking_mode_usage_stats),
        stringResource(R.string.blocking_mode_usage_stats_desc),
        selected = current == BlockingMode.USAGE_STATS,
    ) { onChoose(BlockingMode.USAGE_STATS) }
    Text(stringResource(R.string.blocking_mode_hint), style = MaterialTheme.typography.bodySmall)
    // The protections right here, so the guardian doesn't miss them (all free during setup).
    Text(stringResource(R.string.settings_group_protection), style = MaterialTheme.typography.titleMedium)
    val websiteAvailable = current != BlockingMode.USAGE_STATS
    SetupSwitch(
        title = stringResource(R.string.settings_website_blocking),
        description = stringResource(
            if (websiteAvailable) R.string.settings_website_blocking_desc else R.string.settings_website_blocking_unavailable,
        ),
        checked = protections.websiteBlocking && websiteAvailable,
        enabled = websiteAvailable,
        onChange = onWebsiteBlocking,
    )
    listOf(
        Triple(Protection.UNINSTALL, R.string.protection_uninstall, R.string.protection_uninstall_desc),
        Triple(Protection.SETTINGS_GUARD, R.string.protection_settings_guard, R.string.protection_settings_guard_desc),
        Triple(Protection.GUARDIAN_ALERTS, R.string.protection_alerts, R.string.protection_alerts_desc),
    ).forEach { (protection, title, desc) ->
        SetupSwitch(stringResource(title), stringResource(desc), protection in protections.protections) { onProtection(protection, it) }
    }
    Text(stringResource(R.string.setup_protection_hint), style = MaterialTheme.typography.bodySmall)
    BackAndNext(onBack) { Button(onClick = onContinue) { Text(stringResource(R.string.setup_continue)) } }
}

/** A setting with title, description and switch – the whole row toggles (one TalkBack target). */
@Composable
private fun SetupSwitch(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ChooseMethod(
    mode: SetupMode,
    onPassword: () -> Unit,
    onTotp: () -> Unit,
    onCancel: () -> Unit,
    onRestore: (() -> Unit)? = null,
    restoreMessage: Int? = null,
) {
    when (mode) {
        SetupMode.RECOVERY -> Text(stringResource(R.string.setup_re_setup_notice), color = MaterialTheme.colorScheme.error)
        SetupMode.CHANGE -> Text(stringResource(R.string.setup_change_notice), color = MaterialTheme.colorScheme.primary)
        SetupMode.FIRST -> Unit
    }
    Title(stringResource(R.string.setup_method_title))
    Text(stringResource(R.string.setup_method_body))
    MethodCard(stringResource(R.string.setup_method_password_title), stringResource(R.string.setup_method_password_desc), onClick = onPassword)
    MethodCard(stringResource(R.string.setup_method_totp_title), stringResource(R.string.setup_method_totp_desc), onClick = onTotp)
    // New phone: take over rules and the authenticator code from an export of the old one.
    onRestore?.let { restore ->
        OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_restore)) }
        restoreMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
    if (mode == SetupMode.CHANGE) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun MethodCard(title: String, description: String, selected: Boolean = false, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PasswordStep(error: PasswordError?, busy: Boolean, onSubmit: (String, String) -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    Title(stringResource(R.string.setup_password_title))
    PasswordField(password, { password = it }, stringResource(R.string.setup_password_label))
    PasswordField(repeat, { repeat = it }, stringResource(R.string.setup_password_repeat_label))
    when (error) {
        PasswordError.TOO_SHORT -> ErrorText(
            pluralStringResource(R.plurals.setup_password_too_short, UnlockManager.MIN_PASSWORD_LENGTH, UnlockManager.MIN_PASSWORD_LENGTH),
        )
        PasswordError.MISMATCH -> ErrorText(stringResource(R.string.setup_password_mismatch))
        null -> Unit
    }
    NavButtons(busy = busy, onBack = onBack, onNext = { onSubmit(password, repeat) })
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    DisableAutofill()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.TotpStep(step: SetupStep.Totp, busy: Boolean, onConfirm: (String) -> Unit, onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Title(stringResource(R.string.setup_totp_title))
    Text(stringResource(R.string.setup_totp_body))
    QrCode(
        content = step.setup.provisioningUri,
        contentDescription = stringResource(R.string.setup_totp_qr_description),
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(stringResource(R.string.setup_totp_manual), style = MaterialTheme.typography.bodySmall)
    SelectionContainer {
        Text(step.setup.secretBase32.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace)
    }
    OutlinedTextField(
        value = code,
        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
        label = { Text(stringResource(R.string.setup_totp_code_label)) },
        singleLine = true,
        isError = step.wrongCode,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    if (step.wrongCode) ErrorText(stringResource(R.string.setup_totp_wrong))
    NavButtons(busy = busy, onBack = onBack, onNext = { onConfirm(code) }, nextLabel = stringResource(R.string.setup_totp_confirm))
}

@Composable
private fun RecoveryStep(code: String, busy: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    var stored by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val fileContent = stringResource(
        R.string.recovery_file_content,
        code,
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()),
    )
    val copiedText = stringResource(R.string.setup_recovery_copied)
    val clipLabel = stringResource(R.string.setup_recovery_title)
    val shareTitle = stringResource(R.string.setup_recovery_share_title)

    // The temporary share file is removed as soon as this step is left (not on rotation – a share
    // target may still be reading it; LaresApp also cleans up at the next start).
    DisposableEffect(Unit) {
        onDispose { if ((context as? Activity)?.isChangingConfigurations != true) RecoveryExport.deleteSharedFile(context) }
    }

    Title(stringResource(R.string.setup_recovery_title))
    Text(stringResource(R.string.setup_recovery_body))
    SelectionContainer {
        Text(code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                RecoveryExport.copyToClipboard(context, clipLabel, code)
                status = copiedText
            },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.setup_recovery_copy)) }
        OutlinedButton(
            onClick = { RecoveryExport.share(context, fileContent, subject = clipLabel, chooserTitle = shareTitle) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.setup_recovery_share)) }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    Text(stringResource(R.string.setup_recovery_export_warning), style = MaterialTheme.typography.bodySmall)
    Row(
        Modifier.fillMaxWidth().toggleable(value = stored, role = Role.Checkbox, onValueChange = { stored = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = stored, onCheckedChange = null)
        Text(stringResource(R.string.setup_recovery_confirm))
    }
    ProgressButton(
        text = stringResource(R.string.setup_finish),
        busy = busy,
        enabled = stored,
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NavButtons(busy: Boolean, onBack: () -> Unit, onNext: () -> Unit, nextLabel: String = stringResource(R.string.setup_continue)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.back)) }
        ProgressButton(text = nextLabel, busy = busy, onClick = onNext)
    }
}

@Composable
fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
