/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.lares_app.R
import de.lares_app.core.policy.Schedule
import de.lares_app.core.unlock.RestoreResult
import de.lares_app.core.data.AppTheme
import de.lares_app.core.policy.RuleType
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App name followed by the package ID in parentheses, smaller and muted, e.g.
 * "YouTube (com.google.android.youtube)", so apps with the same name can be told apart.
 * Websites (no [packageName]) and apps without a known name show the text only once.
 */
@Composable
fun appLabel(label: String, packageName: String?): AnnotatedString {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        append(label)
        if (packageName != null && packageName != label) {
            withStyle(SpanStyle(fontSize = 0.8.em, color = muted)) { append(" ($packageName)") }
        }
    }
}

/** [appLabel] for a rule: package ID for app rules, nothing extra for websites. */
@Composable
fun RuleItem.displayName(): AnnotatedString =
    appLabel(label, rule.target.takeIf { rule.type == RuleType.APP })

/**
 * Excludes the window this is composed in (and all its views) from Android autofill, so password
 * managers neither offer to save nor fill the guardian's code. Needed in dialogs, which have their
 * own window; the main activity window is excluded in MainActivity.
 */
@Composable
fun DisableAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.rootView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { }
    }
}

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
fun limitLabel(limitMinutes: Int?, scheduleId: Long? = null): String =
    if (scheduleId != null) {
        scheduleName(scheduleId)
    } else if (limitMinutes == null) {
        stringResource(R.string.rule_always_blocked)
    } else {
        pluralStringResource(R.plurals.rule_limit_minutes, limitMinutes, limitMinutes)
    }

enum class LimitMode { ALWAYS, DAILY, SCHEDULE }

/** "Schedule “School”" for a schedule from [LocalScheduleChoice] (or just "Schedule" if unknown). */
@Composable
fun scheduleName(scheduleId: Long): String =
    LocalScheduleChoice.current?.schedules?.firstOrNull { it.id == scheduleId }?.let { stringResource(R.string.limit_schedule_named, it.name) }
        ?: stringResource(R.string.limit_schedule)

/** Editable "always blocked / daily limit / schedule" choice. */
@Stable
class LimitState(initial: Int?, initialSchedule: Long? = null) {
    var mode by mutableStateOf(
        when {
            initialSchedule != null -> LimitMode.SCHEDULE
            initial == null -> LimitMode.ALWAYS
            else -> LimitMode.DAILY
        },
    )
    var minutesText by mutableStateOf(initial?.toString() ?: "30")
    var scheduleId by mutableStateOf(initialSchedule)

    val isValid: Boolean
        get() = when (mode) {
            LimitMode.ALWAYS -> true
            LimitMode.DAILY -> minutesText.toIntOrNull()?.let { it >= 0 } == true
            LimitMode.SCHEDULE -> scheduleId != null
        }

    /** null = always blocked (or a schedule). Only meaningful if [isValid]. */
    val limit: Int? get() = if (mode == LimitMode.DAILY) minutesText.toIntOrNull() else null

    /** The chosen schedule in schedule mode, else null. */
    val schedule: Long? get() = if (mode == LimitMode.SCHEDULE) scheduleId else null
}

/** Survives rotation (otherwise a group could be created with a limit the user didn't choose). */
@Composable
fun rememberLimitState(initial: Int?, initialSchedule: Long? = null) =
    rememberSaveable(saver = LimitStateSaver) { LimitState(initial, initialSchedule) }

private val LimitStateSaver = listSaver<LimitState, Any>(
    save = { listOf(it.mode.name, it.minutesText, it.scheduleId ?: -1L) },
    restore = { saved ->
        LimitState(null).apply {
            mode = LimitMode.valueOf(saved[0] as String)
            minutesText = saved[1] as String
            scheduleId = (saved[2] as Long).takeIf { it > 0 }
        }
    },
)

/** What the "Schedule" mode offers: the schedules and a way to open the schedule list. */
data class ScheduleChoice(val schedules: List<Schedule>, val onManage: () -> Unit)

/** Provided by screens that offer schedules; without it, [LimitSelector] has no "Schedule" mode. */
val LocalScheduleChoice = compositionLocalOf<ScheduleChoice?> { null }

/**
 * @param schedules offered in the "Schedule" mode; [onManageSchedules] opens the schedule list
 *   (null: the "Schedule" mode isn't offered here). Both default to [LocalScheduleChoice].
 */
@Composable
fun LimitSelector(
    state: LimitState,
    modifier: Modifier = Modifier,
    schedules: List<Schedule> = LocalScheduleChoice.current?.schedules.orEmpty(),
    onManageSchedules: (() -> Unit)? = LocalScheduleChoice.current?.onManage,
) {
    Column(modifier.selectableGroup()) {
        RadioRow(stringResource(R.string.rule_always_blocked), state.mode == LimitMode.ALWAYS) { state.mode = LimitMode.ALWAYS }
        RadioRow(stringResource(R.string.limit_daily), state.mode == LimitMode.DAILY) { state.mode = LimitMode.DAILY }
        if (state.mode == LimitMode.DAILY) {
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
        if (onManageSchedules != null) {
            RadioRow(stringResource(R.string.limit_schedule), state.mode == LimitMode.SCHEDULE) { state.mode = LimitMode.SCHEDULE }
            if (state.mode == LimitMode.SCHEDULE) SchedulePicker(state, schedules, onManageSchedules, Modifier.padding(start = 48.dp))
        }
    }
}

/** Dropdown with the schedules' names and summaries, plus "Manage schedules". */
@Composable
private fun SchedulePicker(state: LimitState, schedules: List<Schedule>, onManageSchedules: () -> Unit, modifier: Modifier = Modifier) {
    if (schedules.isEmpty()) {
        Column(modifier) {
            Text(stringResource(R.string.limit_schedule_none), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onManageSchedules) { Text(stringResource(R.string.limit_schedule_create)) }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    val selected = schedules.firstOrNull { it.id == state.scheduleId }
    Box(modifier) {
        OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.name ?: stringResource(R.string.limit_schedule_choose), style = MaterialTheme.typography.bodyLarge)
                    selected?.let { Text(scheduleSummary(it), style = MaterialTheme.typography.bodySmall) }
                }
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in schedules) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.name)
                            Text(scheduleSummary(s), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        state.scheduleId = s.id
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.limit_schedule_manage)) },
                onClick = {
                    open = false
                    onManageSchedules()
                },
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

private val LetterPalette = listOf(
    Color(0xFFC62828), Color(0xFFAD1457), Color(0xFF6A1B9A), Color(0xFF283593), Color(0xFF1565C0),
    Color(0xFF00695C), Color(0xFF2E7D32), Color(0xFFE65100), Color(0xFF4E342E), Color(0xFF37474F),
)

/**
 * Placeholder for apps / websites without an installed app icon: the first letter on a colored
 * circle. [brandColor] (ARGB, from the templates) is used if known; otherwise the color is derived
 * from [text], so it's stable and differs between names.
 * Original artwork – no brand logos (trademark / license / store reasons, see docs/decisions.md, "Icons").
 */
@Composable
fun LetterIcon(text: String, modifier: Modifier = Modifier, size: Dp = 40.dp, brandColor: Long? = null) {
    val letter = text.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "?"
    val color = brandColor?.let { Color(it) }
        ?: LetterPalette[(text.lowercase().hashCode() and Int.MAX_VALUE) % LetterPalette.size]
    val luminance = color.luminance()
    // Light brand colors (e.g. Snapchat yellow) get a dark letter; very dark ones (black) an outline,
    // so they stay visible on a dark background.
    val letterColor = if (luminance > 0.4f) Color.Black else Color.White
    val outline = if (luminance < 0.05f) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier
    Box(
        modifier.size(size).clip(CircleShape).background(color).then(outline).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = letterColor, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Remaining time until [untilElapsedMillis] (`SystemClock.elapsedRealtime()` based), updated
 * exactly when the displayed seconds change. Returns 0 once the time is up.
 */
@Composable
fun rememberCountdown(untilElapsedMillis: Long): Long {
    val remaining by produceState(initialValue = (untilElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0), untilElapsedMillis) {
        while (true) {
            val left = untilElapsedMillis - SystemClock.elapsedRealtime()
            value = left.coerceAtLeast(0)
            if (left <= 0) break
            // The countdown shows rounded-up seconds, so wake up right after the next second boundary.
            delay((left - 1) % 1000 + 1)
        }
    }
    return remaining
}

/**
 * Draws attention to a setting the user was sent to: a translucent accent-colored layer pulses
 * softly three times over it (sin² curve). Drawn *over* the content – components like ListItem
 * paint an opaque background that would hide anything drawn behind them.
 *
 * Driven by an absolute start time ([startUptime], `SystemClock.uptimeMillis`) instead of a
 * composition-bound animation, so it continues correctly even if the composition is recreated.
 */
@Composable
fun Modifier.highlightPulse(startUptime: Long?): Modifier {
    if (startUptime == null) return this
    val end = startUptime + PULSES * PULSE_MILLIS
    val now by produceState(SystemClock.uptimeMillis(), startUptime) {
        while (value < end) {
            value = withFrameMillis { SystemClock.uptimeMillis() }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    return this.drawWithContent {
        drawContent()
        val p = (now - startUptime).toFloat() / PULSE_MILLIS
        if (p > 0f && p < PULSES) {
            val s = sin(PI * p).toFloat()
            drawRect(color.copy(alpha = PULSE_MAX_ALPHA * s * s))
        }
    }
}

private const val PULSES = 3
private const val PULSE_MILLIS = 500L
private const val PULSE_MAX_ALPHA = 0.22f

/** Button that shows a small spinner instead of its label while [busy] (and ignores taps). */
@Composable
fun ProgressButton(text: String, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        } else {
            Text(text)
        }
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
        filterQuality = FilterQuality.None,
        modifier = modifier.size(240.dp).background(Color.White),
    )
}

/** Lares colors / wallpaper colors (setup and settings). */
@Composable
fun ThemeOptions(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    RadioRow(stringResource(R.string.theme_lares), selected == AppTheme.LARES) { onSelect(AppTheme.LARES) }
    RadioRow(stringResource(R.string.theme_wallpaper), selected == AppTheme.WALLPAPER) { onSelect(AppTheme.WALLPAPER) }
}

/**
 * Asks for the recovery code of another phone to take over its authenticator code from a rules
 * export (settings import and first setup). [restore] returns the result (null = not allowed).
 */
@Composable
fun TotpRestoreDialog(
    title: String,
    body: String,
    confirm: String,
    restore: suspend (String) -> RestoreResult?,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    /** Message under the field: wrong recovery code, or not allowed right now (unlocked mode ended). */
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.setup_recovery_title)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = restore(code)
                        busy = false
                        when (result) {
                            RestoreResult.Restored -> onDone()
                            null -> error = R.string.backup_totp_not_allowed
                            else -> error = R.string.backup_totp_wrong
                        }
                    }
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** List rows without their own background: they take the color of the screen or card they're on. */
@Composable
fun clearListItemColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Text screens and lists don't get wider than this on tablets / in landscape (readable lines). */
val READABLE_WIDTH = 720.dp

/**
 * Centered with at most [max] width. Put it after `verticalScroll`, so the whole width still
 * scrolls.
 */
fun Modifier.readableWidth(max: Dp = READABLE_WIDTH): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/** Side padding that centers a lazy list of at most [max] width in [available] width. */
fun readableSidePadding(available: Dp, max: Dp = READABLE_WIDTH, min: Dp = 0.dp): Dp = maxOf((available - max) / 2, min)

