/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.lares_app.R
import kotlin.math.ceil

/** Countdown like "4:59" or "1:02:03" (locale-aware digits). */
fun formatCountdown(millis: Long): String = DateUtils.formatElapsedTime(ceil(millis / 1000.0).toLong())

/** "5 minutes", "1 hour", "90 minutes". */
@Composable
fun durationLabel(minutes: Long): String =
    if (minutes >= 60 && minutes % 60 == 0L) {
        val h = (minutes / 60).toInt()
        pluralStringResource(R.plurals.duration_hours, h, h)
    } else {
        pluralStringResource(R.plurals.duration_minutes, minutes.toInt(), minutes.toInt())
    }

@Composable
fun limitLabel(limitMinutes: Int?): String =
    if (limitMinutes == null) {
        stringResource(R.string.rule_always_blocked)
    } else {
        pluralStringResource(R.plurals.rule_limit_minutes, limitMinutes, limitMinutes)
    }

/** Editable "always blocked / daily limit" choice. */
@Stable
class LimitState(initial: Int?) {
    var alwaysOn by mutableStateOf(initial == null)
    var minutesText by mutableStateOf(initial?.toString() ?: "30")

    val isValid: Boolean get() = alwaysOn || minutesText.toIntOrNull()?.let { it >= 0 } == true

    /** null = always blocked. Only meaningful if [isValid]. */
    val limit: Int? get() = if (alwaysOn) null else minutesText.toIntOrNull()
}

@Composable
fun rememberLimitState(initial: Int?) = remember { LimitState(initial) }

@Composable
fun LimitSelector(state: LimitState, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup()) {
        RadioRow(stringResource(R.string.limit_always), state.alwaysOn) { state.alwaysOn = true }
        RadioRow(stringResource(R.string.limit_daily), !state.alwaysOn) { state.alwaysOn = false }
        if (!state.alwaysOn) {
            OutlinedTextField(
                value = state.minutesText,
                onValueChange = { v -> state.minutesText = v.filter { it.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.limit_minutes_label)) },
                isError = !state.isValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
            )
        }
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

/** Renders [content] as a QR code (black on white, also in dark mode, so scanners can read it). */
@Composable
fun QrCode(content: String, contentDescription: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        modifier = modifier.size(240.dp).background(Color.White),
    )
}
