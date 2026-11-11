/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.policy.RuleTemplate
import de.lares_app.core.policy.RuleTemplates
import de.lares_app.core.policy.RuleType
import de.lares_app.platform.InstalledApp

/** Tabs of the add screen, in display order. */
private enum class AddTab(@StringRes val label: Int) {
    TEMPLATES(R.string.add_rule_tab_templates),
    GROUP(R.string.add_rule_tab_group),
    APPS(R.string.add_rule_tab_apps),
    WEBSITE(R.string.add_rule_tab_website),
}

/** @param onGroupCreated a group was created in the "Group" tab; continue by adding members to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRuleScreen(onDone: () -> Unit, onGroupCreated: (Long) -> Unit, vm: AddRuleViewModel = hiltViewModel()) {
    val group by vm.group.collectAsStateWithLifecycle()
    val inGroup = vm.groupId != null
    // No groups inside groups: the "Group" tab is hidden when adding to a group.
    val tabs = remember(inGroup) { AddTab.entries.filter { !(inGroup && it == AddTab.GROUP) } }
    var tab by rememberSaveable { mutableStateOf(if (inGroup) AddTab.APPS else AddTab.TEMPLATES) }
    val snackbar = remember { SnackbarHostState() }
    val duplicateText = stringResource(if (inGroup) R.string.add_rule_duplicate_in_group else R.string.add_rule_duplicate)
    val invalidText = stringResource(R.string.add_rule_invalid)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                AddRuleEvent.Added -> onDone()
                is AddRuleEvent.GroupCreated -> onGroupCreated(event.groupId)
                AddRuleEvent.Duplicate -> snackbar.showSnackbar(duplicateText)
                AddRuleEvent.Invalid -> snackbar.showSnackbar(invalidText)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(group?.let { stringResource(R.string.add_rule_to_group_title, it.name) } ?: stringResource(R.string.add_rule_title))
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            group?.let {
                Text(
                    stringResource(R.string.add_rule_group_hint, groupLimitLabel(it.limitMinutesPerDay)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0)) {
                tabs.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.label)) })
                }
            }
            when (tab) {
                AddTab.TEMPLATES -> TemplatesTab(vm, group)
                AddTab.GROUP -> NewGroupTab(onCreate = vm::createGroup)
                AddTab.APPS -> AppsTab(vm, inGroup)
                AddTab.WEBSITE -> WebsiteTab(inGroup, vm, onAdd = { target, limit -> vm.add(RuleType.WEBSITE, target, limit) })
            }
        }
    }
}

@Composable
private fun TemplatesTab(vm: AddRuleViewModel, group: RuleGroup?) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val installed = remember(apps) { apps.orEmpty().associateBy { it.packageName } }
    var pending by remember { mutableStateOf<RuleTemplate?>(null) }
    val websiteBlocking by vm.websiteBlocking.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.add_rule_templates_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(vm.templates, key = { it.id }) { template ->
            val icon = template.apps.firstNotNullOfOrNull { installed[it]?.icon }
            val appsText = pluralStringResource(R.plurals.add_rule_template_apps, template.apps.size, template.apps.size)
            val sitesText = pluralStringResource(R.plurals.add_rule_template_websites, template.websites.size, template.websites.size)
            val isInstalled = template.apps.any { it in installed }
            ListItem(
                headlineContent = { Text(template.name) },
                supportingContent = {
                    Text(
                        if (isInstalled) {
                            stringResource(R.string.add_rule_template_summary_installed, appsText, sitesText)
                        } else {
                            stringResource(R.string.add_rule_template_summary, appsText, sitesText)
                        },
                    )
                },
                leadingContent = {
                    if (icon != null) {
                        Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                    } else {
                        LetterIcon(template.name, brandColor = template.color)
                    }
                },
                modifier = Modifier.clickable { pending = template },
            )
        }
    }

    pending?.let { template ->
        val limit = rememberLimitState(if (group == null) 30 else null)
        var asGroup by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.add_rule_limit_title, template.name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        template.apps.forEach { pkg ->
                            val name = installed[pkg]?.label ?: RuleTemplates.knownAppNames[pkg] ?: pkg
                            Text(appLabel(name, pkg), style = MaterialTheme.typography.bodySmall)
                        }
                        template.websites.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (!websiteBlocking && template.websites.isNotEmpty()) {
                        WebsiteBlockingOffNote(onEnable = vm::enableWebsiteBlocking)
                    }
                    if (group == null) {
                        LimitSelector(limit)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = asGroup, onCheckedChange = { asGroup = it })
                            Text(stringResource(R.string.add_rule_template_as_group))
                        }
                        Text(
                            stringResource(if (asGroup) R.string.add_rule_template_group_note else R.string.add_rule_template_limit_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addTemplate(template, limit.limit, asGroup)
                        pending = null
                    },
                    enabled = group != null || limit.isValid,
                ) { Text(stringResource(R.string.add_rule_add)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AppsTab(vm: AddRuleViewModel, inGroup: Boolean) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<InstalledApp?>(null) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            placeholder = { Text(stringResource(R.string.add_rule_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val filtered = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName) },
                        leadingContent = { app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) } },
                        // In a group, the group's limit applies – no need to ask.
                        modifier = Modifier.clickable { if (inGroup) vm.add(RuleType.APP, app.packageName, null) else pending = app },
                    )
                }
            }
        }
    }

    pending?.let { app ->
        val limit = rememberLimitState(null)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.add_rule_limit_title, app.label)) },
            text = { LimitSelector(limit) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.add(RuleType.APP, app.packageName, limit.limit)
                        pending = null
                    },
                    enabled = limit.isValid,
                ) { Text(stringResource(R.string.add_rule_add)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun WebsiteTab(inGroup: Boolean, vm: AddRuleViewModel, onAdd: (String, Int?) -> Unit) {
    var site by rememberSaveable { mutableStateOf("") }
    val limit = rememberLimitState(null)
    val websiteBlocking by vm.websiteBlocking.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!websiteBlocking) WebsiteBlockingOffNote(onEnable = vm::enableWebsiteBlocking)
        OutlinedTextField(
            value = site,
            onValueChange = { site = it.trim() },
            label = { Text(stringResource(R.string.add_rule_website_label)) },
            placeholder = { Text(stringResource(R.string.add_rule_website_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!inGroup) LimitSelector(limit)
        Button(
            onClick = { onAdd(site, if (inGroup) null else limit.limit) },
            enabled = site.isNotBlank() && (inGroup || limit.isValid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.add_rule_add)) }
    }
}

@Composable
private fun NewGroupTab(onCreate: (String, Int?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val limit = rememberLimitState(60)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.group_explanation), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text(stringResource(R.string.group_name_label)) },
            placeholder = { Text(stringResource(R.string.group_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LimitSelector(limit)
        Button(
            onClick = { onCreate(name, limit.limit) },
            enabled = name.isNotBlank() && limit.isValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.add_rule_group_create)) }
    }
}

/** Shown when adding website rules while website blocking is switched off in the settings. */
@Composable
private fun WebsiteBlockingOffNote(onEnable: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.website_blocking_off_note), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onEnable) { Text(stringResource(R.string.website_blocking_turn_on)) }
        }
    }
}
