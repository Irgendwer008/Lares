/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import android.text.format.DateFormat
import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.Libs
import de.lares_app.R
import de.lares_app.ui.theme.wallpaperColorsAvailable
import de.lares_app.core.data.AppTheme
import de.lares_app.core.alerts.AlertChannelConfig
import de.lares_app.core.alerts.ChannelType
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.Protection
import de.lares_app.core.data.UnlockMethod
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-app language choices: follow the system (empty tag) or one of [AppLanguages]. */
private val Languages: List<Pair<String, String?>> = listOf("" to null) + AppLanguages


/** Settings, separated into app settings and info (about, privacy policy, licenses). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeCode: () -> Unit,
    onOpenChannel: (id: String?) -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenLibraries: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showUnlock by remember { mutableStateOf(false) }
    var showResetTime by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showBlockingMode by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var highlightScrolled by rememberSaveable { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.export { content ->
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(content.encodeToByteArray()) } != null }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.import {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // A rules file is a few KB; refuse anything huge instead of loading it into memory.
                        val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
                        if (bytes.size > MAX_IMPORT_BYTES) null else bytes.decodeToString()
                    }
                }
            }
        }
    }

    // Notification permission: requested only when a warning is switched on.
    val deniedText = stringResource(R.string.settings_warn_denied)
    var pendingWarning by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingWarning?.invoke() else scope.launch { snackbar.showSnackbar(deniedText) }
        pendingWarning = null
    }
    val requestWarning: (Boolean, () -> Unit) -> Unit = { on, apply ->
        if (on && vm.warningNeedsPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingWarning = apply
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            apply()
        }
    }

    val needsCodeText = stringResource(R.string.home_needs_code)
    val enterCodeText = stringResource(R.string.home_unlock)
    val exportedText = stringResource(R.string.backup_exported)
    val exportFailedText = stringResource(R.string.backup_export_failed)
    val importFailedText = stringResource(R.string.backup_import_failed)
    val res = LocalResources.current
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                SettingsEvent.OpenChangeCode -> onChangeCode()
                is SettingsEvent.OpenChannel -> onOpenChannel(event.id)
                SettingsEvent.NeedsCode -> {
                    val result = snackbar.showSnackbar(needsCodeText, actionLabel = enterCodeText, withDismissAction = true)
                    if (result == SnackbarResult.ActionPerformed) showUnlock = true
                }
                is SettingsEvent.Exported -> snackbar.showSnackbar(if (event.ok) exportedText else exportFailedText)
                is SettingsEvent.Imported -> snackbar.showSnackbar(
                    event.result?.let { r ->
                        res.getQuantityString(R.plurals.backup_imported, r.rulesAdded, r.rulesAdded, r.groupsAdded, r.skipped)
                    } ?: importFailedText,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)) {
            // ================= App settings =================
            SettingsHeader(stringResource(R.string.settings_section_app))
            if (!ui.unlocked) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.settings_locked_hint), style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsSubheader(stringResource(R.string.settings_group_guardian), divider = false)
            SettingsItem(
                title = stringResource(R.string.settings_guardian_code),
                summary = stringResource(if (ui.method == UnlockMethod.TOTP) R.string.setup_method_totp_title else R.string.setup_method_password_title),
                icon = R.drawable.ic_key,
                onClick = vm::changeCode,
            )

            SettingsSubheader(stringResource(R.string.settings_group_blocking))
            SettingsItem(
                modifier = Modifier
                    .highlightPulse(vm.highlightStartUptime.takeIf { vm.highlight == SettingsHighlight.BLOCKING_MODE })
                    .onGloballyPositioned { coords ->
                        // Once, when the screen opens – not again whenever the user scrolls back up.
                        if (vm.highlight == SettingsHighlight.BLOCKING_MODE && !highlightScrolled) {
                            highlightScrolled = true
                            val target = (coords.positionInParent().y - with(density) { 96.dp.toPx() }).toInt().coerceAtLeast(0)
                            if (target > 0) scope.launch { scroll.animateScrollTo(target) }
                        }
                    },
                title = stringResource(R.string.settings_blocking_mode),
                summary = stringResource(
                    when (ui.blockingMode) {
                        BlockingMode.ACCESSIBILITY -> R.string.blocking_mode_accessibility
                        BlockingMode.USAGE_STATS -> R.string.blocking_mode_usage_stats
                        null -> R.string.blocking_mode_not_chosen
                    },
                ),
                icon = R.drawable.ic_apps,
                onClick = { showBlockingMode = true },
            )
            // Needs the Accessibility service – in Usage access mode, switch the mode instead.
            val websiteAvailable = ui.blockingMode != BlockingMode.USAGE_STATS
            SwitchItem(
                title = stringResource(R.string.settings_website_blocking),
                summary = stringResource(
                    if (websiteAvailable) R.string.settings_website_blocking_desc else R.string.settings_website_blocking_unavailable,
                ),
                checked = ui.websiteBlocking && websiteAvailable,
                icon = R.drawable.ic_public,
                enabled = websiteAvailable,
                onChange = vm::setWebsiteBlocking,
            )
            SettingsItem(
                title = stringResource(R.string.settings_reset_time),
                summary = ui.pendingResetTime?.let {
                    stringResource(R.string.settings_reset_time_pending, formatTime(ui.resetTime), formatTime(it))
                } ?: formatTime(ui.resetTime),
                icon = R.drawable.ic_schedule,
                onClick = { showResetTime = true },
            )
            SwitchItem(
                title = stringResource(R.string.settings_limit_timer),
                summary = stringResource(R.string.settings_limit_timer_desc),
                checked = ui.limitTimer,
                icon = R.drawable.ic_timer,
                onChange = vm::setLimitTimer,
            )
            SwitchItem(
                title = stringResource(R.string.settings_warn_five),
                summary = stringResource(R.string.settings_warn_desc),
                checked = ui.warnFiveMinutes,
                icon = R.drawable.ic_notifications,
                onChange = { on -> requestWarning(on) { vm.setWarnings(on, ui.warnOneMinute) } },
            )
            SwitchItem(
                title = stringResource(R.string.settings_warn_one),
                summary = stringResource(R.string.settings_warn_desc),
                checked = ui.warnOneMinute,
                icon = R.drawable.ic_notifications,
                onChange = { on -> requestWarning(on) { vm.setWarnings(ui.warnFiveMinutes, on) } },
            )

            SettingsSubheader(stringResource(R.string.settings_group_protection))
            ProtectionSwitch(Protection.UNINSTALL, R.string.protection_uninstall, R.string.protection_uninstall_desc, R.drawable.ic_delete, ui, vm)
            ProtectionSwitch(Protection.SETTINGS_GUARD, R.string.protection_settings_guard, R.string.protection_settings_guard_desc, R.drawable.ic_shield, ui, vm)
            ProtectionSwitch(Protection.GUARDIAN_ALERTS, R.string.protection_alerts, R.string.protection_alerts_desc, R.drawable.ic_notifications_active, ui, vm)

            // Greyed out while "Notify guardian" (above) is off.
            val alertsOn = Protection.GUARDIAN_ALERTS in ui.protections
            HorizontalDivider(Modifier.padding(top = 8.dp))
            if (!alertsOn) {
                Text(
                    stringResource(R.string.channels_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            Column(Modifier.alpha(if (alertsOn) 1f else DISABLED_ALPHA)) {
                SettingsSubheader(stringResource(R.string.settings_group_notifications), divider = false)
                if (ui.channels.isEmpty()) {
                    Text(
                        stringResource(R.string.channels_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                ui.channels.forEach { channel ->
                    SettingsItem(
                        title = stringResource(channelTypeLabel(channel.type)),
                        summary = channelSummary(channel),
                        icon = R.drawable.ic_send,
                        enabled = alertsOn,
                        onClick = { vm.openChannel(channel.id) },
                    )
                }
                TextButton(onClick = { onOpenChannel(null) }, enabled = alertsOn, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.channels_add))
                }
            }

            SettingsSubheader(stringResource(R.string.settings_group_backup))
            SettingsItem(stringResource(R.string.backup_export), stringResource(R.string.backup_export_desc), icon = R.drawable.ic_upload) {
                exportLauncher.launch(backupFileName())
            }
            SettingsItem(stringResource(R.string.backup_import), stringResource(R.string.backup_import_desc), icon = R.drawable.ic_download) {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }

            SettingsSubheader(stringResource(R.string.settings_group_app))
            if (wallpaperColorsAvailable) {
                SettingsItem(
                    title = stringResource(R.string.settings_theme),
                    summary = stringResource(if (ui.theme == AppTheme.WALLPAPER) R.string.theme_wallpaper else R.string.theme_lares),
                    icon = R.drawable.ic_palette,
                    onClick = { showTheme = true },
                )
            }
            SettingsItem(
                title = stringResource(R.string.settings_language),
                summary = currentLanguageLabel(),
                icon = R.drawable.ic_translate,
                onClick = { showLanguage = true },
            )

            HorizontalDivider(Modifier.padding(top = 16.dp), thickness = 4.dp, color = MaterialTheme.colorScheme.surfaceVariant)

            // ================= Info =================
            SettingsHeader(stringResource(R.string.settings_section_info))
            SettingsItem(stringResource(R.string.about_title), stringResource(R.string.about_version, appVersion()), icon = R.drawable.ic_info, onClick = { showAbout = true })
            SettingsItem(stringResource(R.string.privacy_title), stringResource(R.string.privacy_summary), icon = R.drawable.ic_privacy_tip, onClick = onOpenPrivacy)
            SettingsItem(stringResource(R.string.license_title), stringResource(R.string.license_summary), icon = R.drawable.ic_description, onClick = { showLicense = true })
            SettingsItem(stringResource(R.string.libraries_title), stringResource(R.string.libraries_summary), icon = R.drawable.ic_code, onClick = onOpenLibraries)
        }
    }

    if (showUnlock) UnlockDialog(onDismiss = { showUnlock = false })
    if (showResetTime) {
        ResetTimeDialog(
            initial = ui.pendingResetTime ?: ui.resetTime,
            onConfirm = {
                vm.setResetTime(it)
                showResetTime = false
            },
            onDismiss = { showResetTime = false },
        )
    }
    if (showBlockingMode) {
        BlockingModeDialog(
            current = ui.blockingMode,
            onSelect = {
                vm.setBlockingMode(it)
                showBlockingMode = false
            },
            onDismiss = { showBlockingMode = false },
        )
    }
    if (showLanguage) LanguageDialog(onDismiss = { showLanguage = false })
    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    ThemeOptions(ui.theme) {
                        vm.setTheme(it)
                        showTheme = false
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (showAbout) {
        InfoDialog(
            title = stringResource(R.string.app_name),
            text = stringResource(R.string.about_body, appVersion()),
            onDismiss = { showAbout = false },
            wordmark = true,
        )
    }
    if (showLicense) {
        InfoDialog(
            title = stringResource(R.string.license_title),
            text = stringResource(R.string.license_body),
            onDismiss = { showLicense = false },
            extraAction = stringResource(R.string.license_full_text) to {
                showLicense = false
                onOpenLicense()
            },
        )
    }
}

private const val MAX_IMPORT_BYTES = 1024 * 1024

/** Reads at most [limit] bytes (InputStream.readNBytes needs API 33). */
private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** e.g. "Lares-Rules_2026-11-11_1530.json" – local time, no characters that are invalid in file names. */
private fun backupFileName(): String =
    "Lares-Rules_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".json"

@Composable
/** Section within an area: divider line + title in the accent color (Material settings pattern). */
private fun SettingsSubheader(text: String, divider: Boolean = true) {
    if (divider) HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(
    title: String,
    summary: String,
    checked: Boolean,
    icon: Int? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = icon?.let { { SettingsIcon(it) } },
        // One focus target for TalkBack: the whole row is the switch (with the title as label).
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun ProtectionSwitch(protection: Protection, title: Int, desc: Int, icon: Int, ui: SettingsUi, vm: SettingsViewModel) {
    SwitchItem(stringResource(title), stringResource(desc), protection in ui.protections, icon = icon) { vm.setProtection(protection, it) }
}

fun channelTypeLabel(type: ChannelType): Int = when (type) {
    ChannelType.TELEGRAM -> R.string.channel_telegram
    ChannelType.NTFY -> R.string.channel_ntfy
    ChannelType.WEBHOOK -> R.string.channel_webhook
    ChannelType.MATRIX -> R.string.channel_matrix
    ChannelType.EMAIL -> R.string.channel_email
}

/** Non-secret summary, e.g. the ntfy topic or the webhook host. */
@Composable
private fun channelSummary(channel: AlertChannelConfig): String = when (channel.type) {
    ChannelType.TELEGRAM -> stringResource(R.string.channel_summary_telegram, channel.address.orEmpty())
    ChannelType.NTFY -> "${channel.server.orEmpty().removePrefix("https://")}/${channel.address.orEmpty()}"
    ChannelType.WEBHOOK -> channel.server.orEmpty().removePrefix("https://").substringBefore('/')
    ChannelType.MATRIX -> channel.address.orEmpty()
    ChannelType.EMAIL -> channel.address.orEmpty()
}

@Composable
private fun BlockingModeDialog(current: BlockingMode?, onSelect: (BlockingMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_blocking_mode)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(BlockingMode.ACCESSIBILITY, R.string.blocking_mode_accessibility_recommended, R.string.blocking_mode_accessibility_desc),
                    Triple(BlockingMode.USAGE_STATS, R.string.blocking_mode_usage_stats, R.string.blocking_mode_usage_stats_desc),
                ).forEach { (mode, title, desc) ->
                    Column {
                        RadioRow(stringResource(title), current == mode) { onSelect(mode) }
                        Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp))
                    }
                }
                Text(stringResource(R.string.blocking_mode_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
/** Main area ("App settings" / "Info"). */
private fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsItem(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = icon?.let { { SettingsIcon(it) } },
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun SettingsIcon(icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A setting the screen should scroll to and highlight when opened (e.g. from the setup-issues box). */
enum class SettingsHighlight { BLOCKING_MODE }

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** Time in the user's 12 h / 24 h preference. */
@Composable
private fun formatTime(time: LocalTime): String {
    val context = LocalContext.current
    val instant = time.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant()
    return DateFormat.getTimeFormat(context).format(Date.from(instant))
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
}

@Composable
private fun currentLanguageLabel(): String {
    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    return Languages.firstOrNull { it.first == tag }?.second ?: stringResource(R.string.settings_language_system)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResetTimeDialog(initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reset_time)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_reset_time_explanation), style = MaterialTheme.typography.bodySmall)
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.rule_dialog_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Languages.forEach { (tag, name) ->
                    RadioRow(name ?: stringResource(R.string.settings_language_system), current == tag) {
                        // AppCompat stores the choice and recreates the activity in the new language.
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                        )
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun InfoDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    extraAction: Pair<String, () -> Unit>? = null,
    /** Show the "Lares" wordmark instead of the title text. */
    wordmark: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (wordmark) {
                Image(
                    painterResource(R.drawable.logo_wordmark),
                    contentDescription = title,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.width(120.dp),
                )
            } else {
                Text(title)
            }
        },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = extraAction?.let { (label, action) -> { TextButton(onClick = action) { Text(label) } } },
    )
}

// ---- Info screens ----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        content = content,
    )
}

/** Privacy policy (also in docs/privacy-policy.md for the stores). */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    InfoScaffold(stringResource(R.string.privacy_title), onBack) { padding ->
        Text(
            stringResource(R.string.privacy_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

/**
 * All libraries in the APK (also transitive ones) with their full license texts, generated at
 * build time by the AboutLibraries Gradle plugin (offline) into `R.raw.aboutlibraries`.
 */
@Composable
fun LibrariesScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val libraries by produceState<Libs?>(null) {
        value = withContext(Dispatchers.IO) {
            Libs.Builder().withJson(resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }).build()
        }
    }
    val other = stringResource(R.string.libraries_other_author)
    // Grouped by author: organization, otherwise the first developer; alphabetical, unknown last.
    val groups = remember(libraries, other) {
        libraries?.libraries.orEmpty()
            .groupBy { lib -> lib.organization?.name?.takeIf { it.isNotBlank() } ?: lib.developers.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) } }
            .toList()
            .sortedWith(compareBy<Pair<String?, List<Library>>>({ it.first == null }, { it.first?.lowercase() }))
            .map { (author, libs) -> (author ?: other) to libs.sortedBy { it.name.lowercase() } }
    }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var shown by remember { mutableStateOf<Library?>(null) }
    val expandLabel = stringResource(R.string.group_expand)
    val collapseLabel = stringResource(R.string.group_collapse)
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    // The plugin's offline mode stores no license texts – the texts used are bundled (by SPDX id).
    val bundledLicenses by produceState(emptyMap<String, String>()) {
        value = withContext(Dispatchers.IO) {
            mapOf("Apache-2.0" to resources.openRawResource(R.raw.apache_2_0).bufferedReader().use { it.readText() })
        }
    }
    val uriHandler = LocalUriHandler.current
    InfoScaffold(stringResource(R.string.libraries_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            groups.forEach { (author, libs) ->
                val open = author in expanded
                item(key = "a:$author") {
                    ListItem(
                        headlineContent = { Text(author, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(pluralStringResource(R.plurals.libraries_count, libs.size, libs.size)) },
                        trailingContent = {
                            Icon(
                                painterResource(R.drawable.ic_expand_more),
                                contentDescription = null,
                                modifier = Modifier.rotate(if (open) 180f else 0f),
                            )
                        },
                        modifier = Modifier
                            .clickable(onClickLabel = if (open) collapseLabel else expandLabel) {
                                expanded = if (open) expanded - author else expanded + author
                            }
                            .semantics { stateDescription = if (open) expandedState else collapsedState },
                    )
                }
                if (open) {
                    items(libs, key = { "l:$author:${it.uniqueId}" }) { lib ->
                        val link = lib.scm?.url?.takeIf { it.isNotBlank() } ?: lib.website?.takeIf { it.isNotBlank() }
                        ListItem(
                            headlineContent = { Text(lib.name) },
                            supportingContent = {
                                Text(listOfNotNull(lib.artifactVersion, lib.licenses.joinToString { it.spdxId ?: it.name }.ifBlank { null }).joinToString(" · "))
                            },
                            // License text and source code / website as compact buttons behind the entry.
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { shown = lib }) {
                                        Icon(painterResource(R.drawable.ic_description), contentDescription = stringResource(R.string.libraries_license))
                                    }
                                    if (link != null) {
                                        IconButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                                            Icon(
                                                painterResource(R.drawable.ic_code),
                                                contentDescription = stringResource(if (lib.scm?.url.isNullOrBlank()) R.string.libraries_website else R.string.libraries_source),
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(start = 16.dp).clickable { shown = lib },
                        )
                    }
                }
                item(key = "d:$author") { HorizontalDivider() }
            }
        }
    }
    shown?.let { lib ->
        InfoDialog(
            title = lib.name,
            text = lib.licenses.joinToString("\n\n") { license ->
                license.licenseContent?.takeIf { it.isNotBlank() } ?: bundledLicenses[license.spdxId] ?: license.name
            }.ifBlank { stringResource(R.string.libraries_no_license_text) },
            onDismiss = { shown = null },
        )
    }
}

/** Full GPL-3.0 text of Lares, offline (generated from the repository's LICENSE at build time). */
@Composable
fun LicenseTextScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val paragraphs by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(R.raw.lares_license).bufferedReader().use { it.readText() }
        }.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    InfoScaffold(stringResource(R.string.license_title), onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(paragraphs) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
