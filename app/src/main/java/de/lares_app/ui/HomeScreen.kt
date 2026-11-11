/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.core.policy.RuleType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onAddRule: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val rules by vm.ruleItems.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showUnlock by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RuleItem?>(null) }

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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddRule,
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
            item {
                StatusCard(
                    session = session,
                    onUnlock = { showUnlock = true },
                    onEndPause = vm::endPause,
                    onEndEdit = vm::endEditSession,
                )
            }
            if (rules.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(rules, key = { it.rule.id }) { item -> RuleRow(item, onClick = { selected = item }) }
        }
    }

    if (showUnlock) UnlockDialog(onDismiss = { showUnlock = false })
    selected?.let { item ->
        RuleDialog(
            item = item,
            onDismiss = { selected = null },
            onSave = { limit, enabled ->
                if (limit != item.rule.limitMinutesPerDay) vm.setLimit(item.rule.id, limit)
                if (enabled != item.rule.enabled) vm.setEnabled(item.rule.id, enabled)
                selected = null
            },
            onRemove = {
                vm.remove(item.rule.id)
                selected = null
            },
        )
    }
}

@Composable
private fun StatusCard(session: SessionInfo, onUnlock: () -> Unit, onEndPause: () -> Unit, onEndEdit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        session.editRemainingMillis?.let { remaining ->
            SessionCard(
                title = stringResource(R.string.home_status_edit_title),
                body = stringResource(R.string.home_status_edit_body),
                remaining = remaining,
                action = stringResource(R.string.home_end_edit),
                onAction = onEndEdit,
                container = MaterialTheme.colorScheme.secondaryContainer,
            )
        }
        if (session.pauseRemainingMillis == null && session.editRemainingMillis == null) {
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
private fun SessionCard(title: String, body: String?, remaining: Long, action: String, onAction: () -> Unit, container: Color) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.home_time_left, formatCountdown(remaining)), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun RuleRow(item: RuleItem, onClick: () -> Unit) {
    val limit = limitLabel(item.rule.limitMinutesPerDay)
    val disabled = stringResource(R.string.rule_disabled)
    ListItem(
        headlineContent = { Text(item.label) },
        supportingContent = { Text(if (item.rule.enabled) limit else stringResource(R.string.rule_supporting_disabled, limit, disabled)) },
        leadingContent = { RuleIcon(item) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun RuleIcon(item: RuleItem) {
    val icon = item.icon
    if (item.rule.type == RuleType.APP && icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Icon(painterResource(R.drawable.ic_public), contentDescription = null, modifier = Modifier.size(40.dp).padding(6.dp))
    }
}

@Composable
private fun RuleDialog(item: RuleItem, onDismiss: () -> Unit, onSave: (Int?, Boolean) -> Unit, onRemove: () -> Unit) {
    val limit = rememberLimitState(item.rule.limitMinutesPerDay)
    var enabled by remember { mutableStateOf(item.rule.enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { RuleIcon(item) },
        title = { Text(item.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LimitSelector(limit)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.rule_dialog_enabled))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(stringResource(R.string.rule_dialog_hint), style = MaterialTheme.typography.bodySmall)
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
}
