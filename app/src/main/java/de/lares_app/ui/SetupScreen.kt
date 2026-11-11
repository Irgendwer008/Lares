/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lares_app.R
import de.lares_app.core.unlock.UnlockManager

@Composable
fun SetupScreen(reSetup: Boolean, vm: SetupViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    LaunchedEffect(reSetup) { if (reSetup) vm.skipIntro() }
    BackHandler(enabled = step is SetupStep.Password || step is SetupStep.Totp) { vm.back() }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = step) {
                SetupStep.Intro -> Intro(onStart = vm::skipIntro)
                SetupStep.ChooseMethod -> ChooseMethod(onPassword = vm::choosePassword, onTotp = vm::chooseTotp)
                is SetupStep.Password -> PasswordStep(s.error, onSubmit = vm::submitPassword, onBack = vm::back)
                is SetupStep.Totp -> TotpStep(s, onConfirm = vm::confirmTotp, onBack = vm::back)
                is SetupStep.Recovery -> RecoveryStep(s.code, onFinish = vm::finish)
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium)

@Composable
private fun Intro(onStart: () -> Unit) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
    Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.titleMedium)
    Title(stringResource(R.string.setup_intro_title))
    Text(stringResource(R.string.setup_intro_body))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_intro_start)) }
}

@Composable
private fun ChooseMethod(onPassword: () -> Unit, onTotp: () -> Unit) {
    Title(stringResource(R.string.setup_method_title))
    Text(stringResource(R.string.setup_method_body))
    MethodCard(stringResource(R.string.setup_method_password_title), stringResource(R.string.setup_method_password_desc), onPassword)
    MethodCard(stringResource(R.string.setup_method_totp_title), stringResource(R.string.setup_method_totp_desc), onTotp)
}

@Composable
private fun MethodCard(title: String, description: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PasswordStep(error: PasswordError?, onSubmit: (String, String) -> Unit, onBack: () -> Unit) {
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
    NavButtons(onBack = onBack, onNext = { onSubmit(password, repeat) })
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
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
private fun ColumnScope.TotpStep(step: SetupStep.Totp, onConfirm: (String) -> Unit, onBack: () -> Unit) {
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
    NavButtons(onBack = onBack, onNext = { onConfirm(code) }, nextLabel = stringResource(R.string.setup_totp_confirm))
}

@Composable
private fun RecoveryStep(code: String, onFinish: () -> Unit) {
    var stored by remember { mutableStateOf(false) }
    Title(stringResource(R.string.setup_recovery_title))
    Text(stringResource(R.string.setup_recovery_body))
    SelectionContainer {
        Text(code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = stored, onCheckedChange = { stored = it })
        Text(stringResource(R.string.setup_recovery_confirm))
    }
    Button(onClick = onFinish, enabled = stored, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_finish))
    }
}

@Composable
private fun NavButtons(onBack: () -> Unit, onNext: () -> Unit, nextLabel: String = stringResource(R.string.setup_continue)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
        Button(onClick = onNext) { Text(nextLabel) }
    }
}

@Composable
fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
