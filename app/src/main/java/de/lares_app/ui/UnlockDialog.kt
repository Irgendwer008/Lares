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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.core.unlock.UnlockManager

/** Guardian code entry. A correct code unlocks the app for [UnlockManager.UNLOCKED_DURATION_MILLIS]. */
@Composable
fun UnlockDialog(onDismiss: () -> Unit, vm: UnlockViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.reset() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (val s = ui) {
        is UnlockUi.EnterCode -> EnterCodeDialog(s, onSubmit = vm::submit, onDismiss = onDismiss)
        UnlockUi.Done -> LaunchedEffect(Unit) { onDismiss() }
    }
}

@Composable
private fun EnterCodeDialog(state: UnlockUi.EnterCode, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val lockedUntil = when (val e = state.error) {
        is CodeError.Wrong -> e.lockedUntilElapsed
        is CodeError.LockedOut -> e.lockedUntilElapsed
        null -> null
    }
    // Ticks every second; 0 once the waiting time is over (or if there is none).
    val lockRemaining = lockedUntil?.let { rememberCountdown(it) } ?: 0L
    val lockedOut = lockRemaining > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unlock_title)) },
        text = {
            DisableAutofill() // the dialog has its own window
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val minutes = (UnlockManager.UNLOCKED_DURATION_MILLIS / 60_000).toInt()
                Text(pluralStringResource(R.plurals.unlock_body, minutes, minutes))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.unlock_code_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!lockedOut) onSubmit(code) }),
                    isError = state.error != null,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val e = state.error) {
                    null -> Unit
                    else -> if (lockedUntil != null && !lockedOut) {
                        Text(stringResource(R.string.unlock_can_retry), color = MaterialTheme.colorScheme.primary)
                    } else if (e is CodeError.LockedOut) {
                        ErrorText(stringResource(R.string.unlock_error_locked, formatCountdown(lockRemaining)))
                    } else if (lockedOut) {
                        ErrorText(stringResource(R.string.unlock_error_wait, formatCountdown(lockRemaining)))
                    } else {
                        val left = (e as CodeError.Wrong).attemptsLeft
                        ErrorText(pluralStringResource(R.plurals.unlock_error_wrong, left, left))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = !state.busy && !lockedOut && code.isNotBlank()) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.unlock_submit))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val PausePresets = listOf(5L, 15L, 60L)

/** Duration picker for pausing all blocking (only offered while unlocked). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PauseDialog(onStart: (Long) -> Unit, onDismiss: () -> Unit) {
    var preset by remember { mutableStateOf<Long?>(PausePresets[1]) }
    var customText by remember { mutableStateOf("") }
    val minutes = preset ?: customText.toLongOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pause_body))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PausePresets.forEach { p ->
                        FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(durationLabel(p)) })
                    }
                    FilterChip(selected = preset == null, onClick = { preset = null }, label = { Text(stringResource(R.string.pause_custom)) })
                }
                if (preset == null) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { v -> customText = v.filter { it.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.pause_custom_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { minutes?.let(onStart) }, enabled = minutes != null) {
                Text(stringResource(R.string.pause_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
