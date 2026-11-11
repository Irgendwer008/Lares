/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.core.policy.RuleType
import de.lares_app.platform.InstalledApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRuleScreen(onDone: () -> Unit, vm: AddRuleViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val duplicateText = stringResource(R.string.add_rule_duplicate)
    val invalidText = stringResource(R.string.add_rule_invalid)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                AddRuleEvent.Added -> onDone()
                AddRuleEvent.Duplicate -> snackbar.showSnackbar(duplicateText)
                AddRuleEvent.Invalid -> snackbar.showSnackbar(invalidText)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_rule_title)) },
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
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.add_rule_tab_apps)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.add_rule_tab_website)) })
            }
            when (tab) {
                0 -> AppsTab(vm)
                else -> WebsiteTab(onAdd = { target, limit -> vm.add(RuleType.WEBSITE, target, limit) })
            }
        }
    }
}

@Composable
private fun AppsTab(vm: AddRuleViewModel) {
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
                        modifier = Modifier.clickable { pending = app },
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
private fun WebsiteTab(onAdd: (String, Int?) -> Unit) {
    var site by rememberSaveable { mutableStateOf("") }
    val limit = rememberLimitState(null)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = site,
            onValueChange = { site = it.trim() },
            label = { Text(stringResource(R.string.add_rule_website_label)) },
            placeholder = { Text(stringResource(R.string.add_rule_website_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        LimitSelector(limit)
        Button(
            onClick = { onAdd(site, limit.limit) },
            enabled = site.isNotBlank() && limit.isValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.add_rule_add)) }
    }
}
