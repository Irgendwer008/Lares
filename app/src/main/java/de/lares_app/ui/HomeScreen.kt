/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.core.policy.RuleType
import de.lares_app.core.data.SetupIssue
import de.lares_app.core.policy.RuleGroup

/** @param onAddRule opens "add rule"; with a group id the new rules become members of that group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddRule: (groupId: Long?) -> Unit,
    onOpenSettings: (SettingsHighlight?) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val content by vm.content.collectAsStateWithLifecycle()
    val setupIssues by vm.setupIssues.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    // Permissions are granted in the system settings: re-check whenever the screen comes back.
    LifecycleResumeEffect(Unit) {
        vm.refreshPermissions()
        onPauseOrDispose { }
    }
    val openIssue: (SetupIssue) -> Unit = { issue ->
        when (issue) {
            SetupIssue.NO_BLOCKING_MODE -> onOpenSettings(SettingsHighlight.BLOCKING_MODE)
            // Play policy: explain and ask for consent before sending the user to the accessibility settings.
            SetupIssue.ACCESSIBILITY -> showAccessibilityDisclosure = true
            else -> vm.intentFor(issue)?.let { intent ->
                runCatching { context.startActivity(intent) }.onFailure { context.startActivity(vm.appDetailsIntent(issue)) }
            }
        }
    }
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showUnlock by remember { mutableStateOf(false) }
    var showPause by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RuleItem?>(null) }
    var editingGroup by remember { mutableStateOf<RuleGroup?>(null) }

    val needsCodeText = stringResource(R.string.home_needs_code)
    val enterCodeText = stringResource(R.string.home_unlock)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                HomeEvent.NeedsCode -> {
                    val result = snackbar.showSnackbar(needsCodeText, actionLabel = enterCodeText, withDismissAction = true)
                    if (result == SnackbarResult.ActionPerformed) showUnlock = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // "Lares" wordmark (docs/Logo_long.svg) instead of plain text.
                    Image(
                        painterResource(R.drawable.logo_wordmark),
                        contentDescription = stringResource(R.string.app_name),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.height(26.dp).aspectRatio(WORDMARK_ASPECT),
                    )
                },
                actions = {
                    IconButton(onClick = { onOpenSettings(null) }) {
                        Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAddRule(null) },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_rule)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (required, recommended) = setupIssues.partition { it.required }
            if (required.isNotEmpty()) {
                item {
                    SetupIssuesCard(R.string.setup_issues_title, required, MaterialTheme.colorScheme.errorContainer, openIssue, vm::dontKillMyAppUrl)
                }
            }
            if (recommended.isNotEmpty()) {
                item {
                    SetupIssuesCard(R.string.setup_recommendations_title, recommended, MaterialTheme.colorScheme.secondaryContainer, openIssue, vm::dontKillMyAppUrl)
                }
            }
            item {
                StatusCard(
                    session = session,
                    onUnlock = { showUnlock = true },
                    onLock = vm::lock,
                    onPause = { showPause = true },
                    onEndPause = vm::endPause,
                )
            }
            if (content.isEmpty) {
                item {
                    Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            if (content.groups.isNotEmpty()) item { SectionHeader(stringResource(R.string.home_groups)) }
            items(content.groups, key = { "g" + it.group.id }) { item ->
                GroupCard(
                    item = item,
                    websiteBlocking = content.websiteBlocking,
                    onToggle = { vm.setGroupEnabled(item.group.id, it) },
                    onEdit = { editingGroup = item.group },
                    onAddMember = { onAddRule(item.group.id) },
                    onMemberClick = { selected = it },
                )
            }
            if (content.standalone.isNotEmpty()) item { SectionHeader(stringResource(R.string.home_single_rules)) }
            // One compact card for all single rules, styled like the group cards.
            if (content.standalone.isNotEmpty()) {
                item(key = "single-rules") {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(), border = CardDefaults.outlinedCardBorder()) {
                        content.standalone.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider()
                            RuleRow(item, content.websiteBlocking, onClick = { selected = item }, onToggle = { vm.setEnabled(item.rule.id, it) })
                        }
                    }
                }
            }
        }
    }

    if (showUnlock) UnlockDialog(onDismiss = { showUnlock = false })
    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
            text = { Text(stringResource(R.string.accessibility_disclosure_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityDisclosure = false
                    vm.intentFor(SetupIssue.ACCESSIBILITY)?.let { runCatching { context.startActivity(it) } }
                }) { Text(stringResource(R.string.accessibility_disclosure_agree)) }
            },
            dismissButton = { TextButton(onClick = { showAccessibilityDisclosure = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showPause) {
        PauseDialog(
            onStart = { minutes ->
                vm.startPause(minutes)
                showPause = false
            },
            onDismiss = { showPause = false },
        )
    }
    editingGroup?.let { group ->
        GroupDialog(
            initial = group,
            onSave = { name, limit ->
                vm.updateGroup(group.copy(name = name, limitMinutesPerDay = limit))
                editingGroup = null
            },
            onDelete = {
                vm.deleteGroup(group.id)
                editingGroup = null
            },
            onDismiss = { editingGroup = null },
        )
    }
    selected?.let { item ->
        val group = content.groups.firstOrNull { it.group.id == item.rule.groupId }?.group
        if (group != null) {
            MemberDialog(
                item = item,
                group = group,
                onTakeOut = {
                    vm.setGroup(item.rule.id, null)
                    selected = null
                },
                onRemove = {
                    vm.remove(item.rule.id)
                    selected = null
                },
                onDismiss = { selected = null },
            )
        } else {
            RuleDialog(
                item = item,
                groups = content.groups.map { it.group },
                onDismiss = { selected = null },
                onSave = { limit, enabled ->
                    if (limit != item.rule.limitMinutesPerDay || enabled != item.rule.enabled) vm.updateRule(item.rule.id, limit, enabled)
                    selected = null
                },
                onMoveToGroup = { groupId ->
                    vm.setGroup(item.rule.id, groupId)
                    selected = null
                },
                onRemove = {
                    vm.remove(item.rule.id)
                    selected = null
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
}

// ---- Setup issues ----------------------------------------------------------------------------------

/** Box with one entry per missing permission / choice (required ones in red, recommendations calmer). */
@Composable
private fun SetupIssuesCard(
    title: Int,
    issues: List<SetupIssue>,
    container: Color,
    onFix: (SetupIssue) -> Unit,
    dontKillMyAppUrl: () -> String,
) {
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            issues.forEach { issue ->
                val (title, body, action) = when (issue) {
                    SetupIssue.NO_BLOCKING_MODE -> Triple(R.string.issue_mode_title, R.string.issue_mode_body, R.string.issue_mode_action)
                    SetupIssue.ACCESSIBILITY -> Triple(R.string.issue_accessibility_title, R.string.issue_accessibility_body, R.string.issue_open_settings)
                    SetupIssue.USAGE_ACCESS -> Triple(R.string.issue_usage_title, R.string.issue_usage_body, R.string.issue_open_settings)
                    SetupIssue.OVERLAY -> Triple(R.string.issue_overlay_title, R.string.issue_overlay_body, R.string.issue_open_settings)
                    SetupIssue.DEVICE_ADMIN -> Triple(R.string.issue_admin_title, R.string.issue_admin_body, R.string.issue_admin_action)
                    SetupIssue.BATTERY_OPTIMIZATION -> Triple(R.string.issue_battery_title, R.string.issue_battery_body, R.string.issue_open_settings)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onFix(issue) }) { Text(stringResource(action)) }
                        if (issue == SetupIssue.BATTERY_OPTIMIZATION) {
                            TextButton(onClick = { runCatching { uri.openUri(dontKillMyAppUrl()) } }) {
                                Text(stringResource(R.string.issue_battery_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Status ----------------------------------------------------------------------------------------

@Composable
private fun StatusCard(
    session: SessionInfo,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onPause: () -> Unit,
    onEndPause: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        session.unlockedRemainingMillis?.let { remaining ->
            SessionCard(
                title = stringResource(R.string.home_status_unlocked_title),
                body = stringResource(R.string.home_status_unlocked_body),
                remaining = remaining,
                action = stringResource(R.string.home_lock),
                onAction = onLock,
                container = MaterialTheme.colorScheme.secondaryContainer,
                secondaryAction = if (session.pauseRemainingMillis == null) stringResource(R.string.home_pause) else null,
                onSecondaryAction = onPause,
            )
        }
        session.pauseRemainingMillis?.let { remaining ->
            SessionCard(
                title = stringResource(R.string.home_status_paused_title),
                body = null,
                remaining = remaining,
                action = stringResource(R.string.home_end_pause),
                onAction = onEndPause,
                container = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }
        if (!session.isUnlocked) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_lock), contentDescription = null)
                        Text(stringResource(R.string.home_status_protected_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(R.string.home_status_protected_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onUnlock) { Text(stringResource(R.string.home_unlock)) }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    title: String,
    body: String?,
    remaining: Long,
    action: String,
    onAction: () -> Unit,
    container: Color,
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.home_time_left, formatCountdown(remaining)), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction) { Text(action) }
                secondaryAction?.let { OutlinedButton(onClick = onSecondaryAction) { Text(it) } }
            }
        }
    }
}

// ---- Groups ----------------------------------------------------------------------------------------

/** "12 min used today of 30 minutes per day" – only for rules / groups with a daily limit. */
@Composable
private fun usageLabel(limitMinutes: Int?, usedMillis: Long, shared: Boolean = false): String? {
    limitMinutes ?: return null
    val usedMinutes = (usedMillis / 60_000L).toInt()
    val limit = if (shared) groupLimitLabel(limitMinutes) else limitLabel(limitMinutes)
    return pluralStringResource(R.plurals.usage_today, usedMinutes, usedMinutes, limit)
}

@Composable
fun groupLimitLabel(limitMinutes: Int?): String =
    if (limitMinutes == null) {
        stringResource(R.string.rule_always_blocked)
    } else {
        pluralStringResource(R.plurals.group_limit_minutes, limitMinutes, limitMinutes)
    }

@Composable
private fun GroupCard(
    item: GroupItem,
    websiteBlocking: WebsiteBlockingState,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onAddMember: () -> Unit,
    onMemberClick: (RuleItem) -> Unit,
) {
    val group = item.group
    // Collapsed by default; remembered per group (also across rotation).
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(), border = CardDefaults.outlinedCardBorder()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(if (expanded) R.string.group_collapse else R.string.group_expand)) { expanded = !expanded }
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).rotate(rotation),
            )
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                if (item.members.any { it.rule.type == RuleType.WEBSITE }) WebsiteWarning(websiteBlocking, inGroup = true)
                val limit = usageLabel(group.limitMinutesPerDay, item.usedMillis, shared = true) ?: groupLimitLabel(group.limitMinutesPerDay)
                val count = pluralStringResource(R.plurals.group_member_count, item.members.size, item.members.size)
                Text(
                    if (group.enabled) {
                        stringResource(R.string.group_summary, limit, count)
                    } else {
                        stringResource(R.string.group_summary_disabled, limit, count, stringResource(R.string.rule_disabled))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.group_edit))
            }
            Switch(checked = group.enabled, onCheckedChange = onToggle)
        }
        AnimatedVisibility(visible = expanded) {
            GroupCardContent(item, websiteBlocking, onEdit, onAddMember, onMemberClick)
        }
    }
}

@Composable
private fun GroupCardContent(
    item: GroupItem,
    websiteBlocking: WebsiteBlockingState,
    onEdit: () -> Unit,
    onAddMember: () -> Unit,
    onMemberClick: (RuleItem) -> Unit,
) {
    Column {
        if (item.members.isEmpty()) {
            Text(
                stringResource(R.string.group_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            HorizontalDivider()
            item.members.forEach { member ->
                ListItem(
                    headlineContent = { Text(member.displayName()) },
                    supportingContent = if (member.rule.type == RuleType.WEBSITE && websiteBlocking != WebsiteBlockingState.ON) {
                        { WebsiteWarning(websiteBlocking, inGroup = false) }
                    } else {
                        null
                    },
                    leadingContent = { RuleIcon(member, size = 32) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onMemberClick(member) },
                )
            }
        }
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = onAddMember) { Text(stringResource(R.string.group_add_member)) }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.group_edit)) }
        }
    }
}

/** Edit a group (new groups are created in the "Group" tab of the add screen). */
@Composable
private fun GroupDialog(initial: RuleGroup, onSave: (String, Int?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    val limit = rememberLimitState(initial.limitMinutesPerDay)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.group_name_label)) },
                    placeholder = { Text(stringResource(R.string.group_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LimitSelector(limit)
                Text(stringResource(R.string.group_explanation), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDelete) { Text(stringResource(R.string.group_delete), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, limit.limit) }, enabled = name.isNotBlank() && limit.isValid) {
                Text(stringResource(R.string.rule_dialog_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MemberDialog(item: RuleItem, group: RuleGroup, onTakeOut: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { RuleIcon(item) },
        title = { Text(item.displayName()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.member_info, group.name, groupLimitLabel(group.limitMinutesPerDay)))
                TextButton(onClick = onTakeOut) { Text(stringResource(R.string.member_take_out)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.rule_dialog_remove), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.rule_dialog_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

// ---- Standalone rules ------------------------------------------------------------------------------

@Composable
private fun RuleRow(item: RuleItem, websiteBlocking: WebsiteBlockingState, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val limit = usageLabel(item.rule.limitMinutesPerDay, item.usedMillis) ?: limitLabel(item.rule.limitMinutesPerDay)
    val disabled = stringResource(R.string.rule_disabled)
    ListItem(
        headlineContent = { Text(item.displayName()) },
        supportingContent = {
            Column {
                Text(
                    if (item.rule.enabled) limit else stringResource(R.string.rule_supporting_disabled, limit, disabled),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.rule.type == RuleType.WEBSITE) WebsiteWarning(websiteBlocking, inGroup = false)
            }
        },
        leadingContent = { RuleIcon(item, size = 32) },
        trailingContent = { Switch(checked = item.rule.enabled, onCheckedChange = onToggle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "Not blocked": website rules have no effect while website blocking is off / unavailable. */
@Composable
private fun WebsiteWarning(state: WebsiteBlockingState, inGroup: Boolean) {
    if (state == WebsiteBlockingState.ON) return
    val text = when {
        state == WebsiteBlockingState.UNAVAILABLE && inGroup -> R.string.group_websites_unavailable
        state == WebsiteBlockingState.UNAVAILABLE -> R.string.rule_website_unavailable
        inGroup -> R.string.group_websites_off
        else -> R.string.rule_website_off
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun RuleIcon(item: RuleItem, size: Int = 40) {
    val icon = item.icon
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size.dp))
    } else {
        LetterIcon(item.label, size = size.dp, brandColor = item.brandColor)
    }
}

@Composable
private fun RuleDialog(
    item: RuleItem,
    groups: List<RuleGroup>,
    onDismiss: () -> Unit,
    onSave: (Int?, Boolean) -> Unit,
    onMoveToGroup: (Long) -> Unit,
    onRemove: () -> Unit,
) {
    val limit = rememberLimitState(item.rule.limitMinutesPerDay)
    var enabled by remember { mutableStateOf(item.rule.enabled) }
    var pickGroup by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { RuleIcon(item) },
        title = { Text(item.displayName()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LimitSelector(limit)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.rule_dialog_enabled))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(stringResource(R.string.rule_dialog_hint), style = MaterialTheme.typography.bodySmall)
                if (groups.isNotEmpty()) {
                    TextButton(onClick = { pickGroup = true }) { Text(stringResource(R.string.rule_add_to_group)) }
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.rule_dialog_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(limit.limit, enabled) }, enabled = limit.isValid) {
                Text(stringResource(R.string.rule_dialog_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (pickGroup) {
        var choice by remember { mutableLongStateOf(groups.first().id) }
        AlertDialog(
            onDismissRequest = { pickGroup = false },
            title = { Text(stringResource(R.string.rule_add_to_group)) },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    groups.forEach { g ->
                        RadioRow("${g.name} – ${groupLimitLabel(g.limitMinutesPerDay)}", choice == g.id) { choice = g.id }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onMoveToGroup(choice) }) { Text(stringResource(R.string.rule_dialog_save)) } },
            dismissButton = { TextButton(onClick = { pickGroup = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Width / height of the wordmark drawable (viewport of `logo_wordmark.xml`). */
private const val WORDMARK_ASPECT = 289.3f / 87.91f
