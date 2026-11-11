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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R

private val PausePresets = listOf(5L, 15L, 60L)
private val EditPresets = listOf(5L, 10L, 30L)

/** Guardian code entry, then choice between pause and edit session. */
@Composable
fun UnlockDialog(onDismiss: () -> Unit, vm: UnlockViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.reset() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (val s = ui) {
        is UnlockUi.EnterCode -> EnterCodeDialog(s, onSubmit = vm::submit, onDismiss = onDismiss)
        is UnlockUi.Choose -> ChooseDialog(s.viaRecoveryCode, onPause = vm::startPause, onEdit = vm::startEditSession, onDismiss = onDismiss)
        UnlockUi.Done -> LaunchedEffect(Unit) { onDismiss() }
    }
}

@Composable
private fun EnterCodeDialog(state: UnlockUi.EnterCode, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.unlock_body))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.unlock_code_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = state.error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val e = state.error) {
                    is CodeError.Wrong -> ErrorText(
                        if (e.lockoutMillis > 0) {
                            stringResource(R.string.unlock_error_wait, formatCountdown(e.lockoutMillis))
                        } else {
                            pluralStringResource(R.plurals.unlock_error_wrong, e.attemptsBeforeLockout, e.attemptsBeforeLockout)
                        },
                    )
                    is CodeError.LockedOut -> ErrorText(stringResource(R.string.unlock_error_locked, formatCountdown(e.remainingMillis)))
                    null -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = !state.busy && code.isNotBlank()) {
                Text(stringResource(R.string.unlock_submit))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private enum class UnlockMode { PAUSE, EDIT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChooseDialog(viaRecovery: Boolean, onPause: (Long) -> Unit, onEdit: (Long) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(UnlockMode.EDIT) }
    var preset by remember { mutableStateOf<Long?>(10L) }
    var customText by remember { mutableStateOf("") }
    val presets = if (mode == UnlockMode.PAUSE) PausePresets else EditPresets
    val minutes = preset ?: customText.toLongOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unlock_choose_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (viaRecovery) {
                    Text(stringResource(R.string.unlock_recovery_used), color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.selectableGroup()) {
                    RadioRow(stringResource(R.string.unlock_mode_edit), mode == UnlockMode.EDIT) {
                        mode = UnlockMode.EDIT
                        preset = EditPresets[1]
                    }
                    RadioRow(stringResource(R.string.unlock_mode_pause), mode == UnlockMode.PAUSE) {
                        mode = UnlockMode.PAUSE
                        preset = PausePresets[1]
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(durationLabel(p)) })
                    }
                    FilterChip(selected = preset == null, onClick = { preset = null }, label = { Text(stringResource(R.string.unlock_custom)) })
                }
                if (preset == null) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { v -> customText = v.filter { it.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.unlock_custom_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { minutes?.let { if (mode == UnlockMode.PAUSE) onPause(it) else onEdit(it) } },
                enabled = minutes != null,
            ) { Text(stringResource(R.string.unlock_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
