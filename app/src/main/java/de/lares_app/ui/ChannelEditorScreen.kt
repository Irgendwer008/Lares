/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.core.alerts.ChannelType

/** Add or edit a guardian notification channel, with a test message before saving. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChannelEditorScreen(onDone: () -> Unit, vm: ChannelEditorViewModel = hiltViewModel()) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val detecting by vm.detecting.collectAsStateWithLifecycle()
    val editing = vm.channelId != null
    val snackbar = remember { SnackbarHostState() }
    var showUnlock by remember { mutableStateOf(false) }

    val needsCodeText = stringResource(R.string.home_needs_code)
    val enterCodeText = stringResource(R.string.home_unlock)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                ChannelEvent.Saved -> onDone()
                ChannelEvent.NeedsCode -> {
                    val result = snackbar.showSnackbar(needsCodeText, actionLabel = enterCodeText, withDismissAction = true)
                    if (result == SnackbarResult.ActionPerformed) showUnlock = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (editing) R.string.channel_edit_title else R.string.channel_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        DisableAutofill()
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!editing) {
                Text(stringResource(R.string.channel_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelType.entries.forEach { type ->
                        FilterChip(selected = draft.type == type, onClick = { vm.setType(type) }, label = { Text(stringResource(channelTypeLabel(type))) })
                    }
                }
            }
            Text(stringResource(channelHelp(draft.type)), style = MaterialTheme.typography.bodySmall)

            when (draft.type) {
                ChannelType.TELEGRAM -> {
                    SecretField(draft.secret, { v -> vm.update { it.copy(secret = v) } }, stringResource(R.string.channel_bot_token))
                    // Group chats have negative ids, and number keyboards often lack "-".
                    Field(draft.address, { v -> vm.update { it.copy(address = v) } }, stringResource(R.string.channel_chat_id), KeyboardType.Ascii)
                    OutlinedButton(onClick = vm::detectChatId, enabled = draft.secret.isNotBlank() && !detecting) {
                        if (detecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.channel_detect_chat_id))
                    }
                }
                ChannelType.NTFY -> {
                    Field(draft.server, { v -> vm.update { it.copy(server = v) } }, stringResource(R.string.channel_server), KeyboardType.Uri)
                    Field(draft.address, { v -> vm.update { it.copy(address = v) } }, stringResource(R.string.channel_topic))
                    OutlinedButton(onClick = vm::randomTopic) { Text(stringResource(R.string.channel_random_topic)) }
                    SecretField(draft.secret, { v -> vm.update { it.copy(secret = v) } }, stringResource(R.string.channel_token_optional))
                }
                ChannelType.WEBHOOK -> {
                    Field(draft.server, { v -> vm.update { it.copy(server = v) } }, stringResource(R.string.channel_url), KeyboardType.Uri)
                    SecretField(draft.secret, { v -> vm.update { it.copy(secret = v) } }, stringResource(R.string.channel_token_optional))
                }
                ChannelType.EMAIL -> {
                    Field(draft.server, { v -> vm.update { it.copy(server = v) } }, stringResource(R.string.channel_smtp_server), KeyboardType.Uri)
                    Text(stringResource(R.string.channel_security), style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        RadioRow(stringResource(R.string.channel_security_tls), !draft.startTls) { vm.setStartTls(false) }
                        RadioRow(stringResource(R.string.channel_security_starttls), draft.startTls) { vm.setStartTls(true) }
                    }
                    Field(
                        if (draft.port > 0) draft.port.toString() else "",
                        { v -> vm.update { it.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } },
                        stringResource(R.string.channel_port),
                        KeyboardType.Number,
                    )
                    Field(draft.username, { v -> vm.update { it.copy(username = v) } }, stringResource(R.string.channel_username), KeyboardType.Email)
                    SecretField(draft.secret, { v -> vm.update { it.copy(secret = v) } }, stringResource(R.string.channel_password))
                    Field(draft.sender, { v -> vm.update { it.copy(sender = v) } }, stringResource(R.string.channel_sender), KeyboardType.Email)
                    Field(draft.address, { v -> vm.update { it.copy(address = v) } }, stringResource(R.string.channel_recipient), KeyboardType.Email)
                }
                ChannelType.MATRIX -> {
                    Field(draft.server, { v -> vm.update { it.copy(server = v) } }, stringResource(R.string.channel_homeserver), KeyboardType.Uri)
                    Field(draft.address, { v -> vm.update { it.copy(address = v) } }, stringResource(R.string.channel_room_id))
                    SecretField(draft.secret, { v -> vm.update { it.copy(secret = v) } }, stringResource(R.string.channel_access_token))
                }
            }

            Text(stringResource(R.string.channel_language), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                AppLanguages.forEach { (tag, name) ->
                    RadioRow(name, draft.language == tag) { vm.update { it.copy(language = tag) } }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = vm::sendTest, enabled = draft.isComplete && test != TestState.Sending) {
                    Text(stringResource(R.string.channel_send_test))
                }
                when (val t = test) {
                    TestState.Idle -> Unit
                    TestState.Sending -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    TestState.Ok -> Text(stringResource(R.string.channel_test_ok), color = MaterialTheme.colorScheme.primary)
                    TestState.ChatNotFound -> ErrorText(stringResource(R.string.channel_detect_failed))
                    is TestState.Failed -> ErrorText(t.reason?.let { stringResource(R.string.channel_test_failed_reason, it) } ?: stringResource(R.string.channel_test_failed))
                }
            }
            if (!draft.isComplete) Text(stringResource(R.string.channel_incomplete), style = MaterialTheme.typography.bodySmall)

            ProgressButton(
                text = stringResource(R.string.rule_dialog_save),
                busy = false,
                enabled = draft.isComplete,
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth(),
            )
            if (editing) {
                TextButton(onClick = vm::delete) { Text(stringResource(R.string.channel_delete), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.channel_edit_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showUnlock) UnlockDialog(onDismiss = { showUnlock = false })
}

private fun channelHelp(type: ChannelType): Int = when (type) {
    ChannelType.TELEGRAM -> R.string.channel_help_telegram
    ChannelType.NTFY -> R.string.channel_help_ntfy
    ChannelType.WEBHOOK -> R.string.channel_help_webhook
    ChannelType.MATRIX -> R.string.channel_help_matrix
    ChannelType.EMAIL -> R.string.channel_help_email
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Tokens are hidden while typing and never offered to password managers. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
