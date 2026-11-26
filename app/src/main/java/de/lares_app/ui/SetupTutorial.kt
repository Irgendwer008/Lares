/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lares_app.R
import de.lares_app.core.policy.WindowMode
import java.time.DayOfWeek
import java.time.format.TextStyle

// ---- Tutorial ------------------------------------------------------------------------------------

/** One page of the first-setup tutorial: what Lares blocks, the three modes, schedules, the code. */
@Composable
internal fun TutorialPage(page: Int, onBack: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.tutorial_label, page + 1, TUTORIAL_PAGES),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = onSkip) { Text(stringResource(R.string.tutorial_skip)) }
    }
    when (page) {
        0 -> {
            TutorialHeader(R.drawable.ic_apps, R.string.tutorial_rules_title)
            Text(stringResource(R.string.tutorial_rules_body))
            TutorialPoint(R.drawable.ic_add, R.string.tutorial_rules_add)
            TutorialPoint(R.drawable.ic_apps, R.string.tutorial_rules_templates)
            TutorialPoint(R.drawable.ic_public, R.string.tutorial_rules_groups)
        }
        1 -> {
            TutorialHeader(R.drawable.ic_timer, R.string.tutorial_modes_title)
            Text(stringResource(R.string.tutorial_modes_body))
            ModeCard(R.drawable.ic_lock, R.string.rule_always_blocked, R.string.tutorial_modes_always)
            ModeCard(R.drawable.ic_timer, R.string.limit_daily, R.string.tutorial_modes_daily)
            ModeCard(R.drawable.ic_calendar, R.string.limit_schedule, R.string.tutorial_modes_schedule)
        }
        2 -> {
            TutorialHeader(R.drawable.ic_calendar, R.string.tutorial_schedules_title)
            Text(stringResource(R.string.tutorial_schedules_body))
            ScheduleIllustration()
            Text(stringResource(R.string.tutorial_schedules_more), style = MaterialTheme.typography.bodyMedium)
        }
        else -> {
            TutorialHeader(R.drawable.ic_key, R.string.tutorial_code_title)
            Text(stringResource(R.string.tutorial_code_body))
            TutorialPoint(R.drawable.ic_lock, R.string.tutorial_code_changes)
            TutorialPoint(R.drawable.ic_timer, R.string.tutorial_code_unlock)
            TutorialPoint(R.drawable.ic_notifications, R.string.tutorial_code_protect)
        }
    }
    val last = page == TUTORIAL_PAGES - 1
    BackAndNext(onBack) {
        Button(onClick = onNext) { Text(stringResource(if (last) R.string.tutorial_finish else R.string.tutorial_next)) }
    }
}

@Composable
private fun TutorialHeader(icon: Int, title: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Title(stringResource(title))
    }
}

@Composable
private fun TutorialPoint(icon: Int, text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModeCard(icon: Int, title: Int, body: Int) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A mini week of an example schedule: blocked during school hours, a timer in the evening, free on Saturday. */
@Composable
private fun ScheduleIllustration() {
    val locale = currentLocale()
    val days = weekDays(locale)
    val description = stringResource(R.string.tutorial_schedules_example)
    // Example windows per day: (from hour, to hour, mode).
    fun windowsOf(day: DayOfWeek): List<Triple<Int, Int, WindowMode>> = when (day) {
        DayOfWeek.SATURDAY -> listOf(Triple(14, 18, WindowMode.FREE))
        DayOfWeek.SUNDAY -> listOf(Triple(15, 17, WindowMode.TIMER))
        else -> listOf(Triple(8, 14, WindowMode.BLOCK), Triple(17, 19, WindowMode.TIMER))
    }
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in days) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    for ((from, to, mode) in windowsOf(day)) {
                        // Shows 06:00–22:00.
                        val top = (from - 6) / 16f
                        val height = (to - from) / 16f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fraction(top, height)
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(modeColors(mode).first),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in days) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(WindowMode.BLOCK, R.string.schedule_mode_block)
            Legend(WindowMode.TIMER, R.string.schedule_mode_timer)
            Legend(WindowMode.FREE, R.string.schedule_mode_free)
        }
    }
}

/** Places the box at [top] (fraction of the parent height) with [height] (fraction). */
private fun Modifier.fraction(top: Float, height: Float) = layout { measurable, constraints ->
    val h = (constraints.maxHeight * height).toInt()
    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, (constraints.maxHeight * top).toInt()) }
}

@Composable
private fun Legend(mode: WindowMode, label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(modeColors(mode).first))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

// ---- End of the setup / browser extension --------------------------------------------------------

/** The browser extension (separate project, desktop browsers). */
const val BROWSER_EXTENSION_URL = "https://github.com/Irgendwer008/Lares-BrowserEdition"

/** Last page of the first setup: ready, what's next, and the browser extension. */
@Composable
internal fun DoneStep(busy: Boolean, onClose: () -> Unit) {
    TutorialHeader(R.drawable.ic_check, R.string.setup_done_title)
    Text(stringResource(R.string.setup_done_body))
    BrowserExtensionCard()
    Button(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_done_start)) }
}

/** What the browser extension is, with a button to its project page. */
@Composable
internal fun BrowserExtensionCard() {
    val uriHandler = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_public), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.browser_ext_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.browser_ext_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.browser_ext_note), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(BROWSER_EXTENSION_URL) } }) {
                Text(stringResource(R.string.browser_ext_open))
            }
        }
    }
}

// ---- Progress through the setup -------------------------------------------------------------------

/** The parts of the setup, shown as icon circles at the bottom. */
internal enum class SetupStage(val icon: Int, val label: Int) {
    WELCOME(R.drawable.ic_lares, R.string.setup_stage_welcome),
    TUTORIAL(R.drawable.ic_info, R.string.setup_stage_tutorial),
    GUARDIAN(R.drawable.ic_shield, R.string.setup_stage_guardian),
    MODE(R.drawable.ic_apps, R.string.setup_stage_mode),
    CODE(R.drawable.ic_key, R.string.setup_stage_code),
    RECOVERY(R.drawable.ic_lock, R.string.setup_stage_recovery),
    DONE(R.drawable.ic_check, R.string.setup_stage_done),
}

internal fun stagesFor(mode: SetupMode): List<SetupStage> =
    if (mode == SetupMode.FIRST) SetupStage.entries else listOf(SetupStage.CODE, SetupStage.RECOVERY)

internal fun stageOf(step: SetupStep): SetupStage = when (step) {
    SetupStep.Welcome -> SetupStage.WELCOME
    is SetupStep.Tutorial -> SetupStage.TUTORIAL
    SetupStep.Intro -> SetupStage.GUARDIAN
    SetupStep.BlockingMode -> SetupStage.MODE
    SetupStep.ChooseMethod, is SetupStep.Password, is SetupStep.Totp -> SetupStage.CODE
    is SetupStep.Recovery -> SetupStage.RECOVERY
    SetupStep.Done -> SetupStage.DONE
}

/**
 * Small circles with icons: done, current (filled) and still to come – connected by short lines.
 * The tour is one circle; while it's the current step it splits into one smaller numbered circle
 * per page (instead of separate page dots) – like the browser extension.
 */
@Composable
internal fun SetupProgress(stages: List<SetupStage>, current: SetupStage, tourPage: Int = 0) {
    // (stage, tour page or null) per circle.
    val nodes = stages.flatMap { stage ->
        if (stage == SetupStage.TUTORIAL && current == SetupStage.TUTORIAL) (0 until TUTORIAL_PAGES).map { stage to it } else listOf(stage to null)
    }
    val index = nodes.indexOfFirst { (stage, page) -> stage == current && (page == null || page == tourPage) }.coerceAtLeast(0)
    val stageIndex = stages.indexOf(current).coerceAtLeast(0)
    val description = stringResource(R.string.setup_progress, stageIndex + 1, stages.size, stringResource(current.label))
    // With the tour split up there are ten circles: shorter lines so they fit on a 320 dp screen.
    val lineWidth = if (nodes.size > stages.size) 6.dp else 10.dp
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 12.dp, bottom = 8.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nodes.forEachIndexed { i, (stage, page) ->
            if (i > 0) {
                Box(
                    Modifier
                        .width(lineWidth)
                        .height(2.dp)
                        .background(if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
            val isCurrent = i == index
            val (fill, tint) = when {
                i < index -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                isCurrent -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val size = when {
                page != null -> if (isCurrent) 28.dp else 22.dp // the tour's page circles are smaller
                isCurrent -> 34.dp
                else -> 28.dp
            }
            Box(
                Modifier.size(size).clip(CircleShape).background(fill),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    page != null -> Text("${page + 1}", color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    // The Lares "L" is narrow and tall: a little smaller than the other icons.
                    stage == SetupStage.WELCOME -> Icon(
                        painterResource(stage.icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(if (isCurrent) 12.dp else 10.dp),
                    )
                    else -> Icon(painterResource(stage.icon), contentDescription = null, tint = tint, modifier = Modifier.size(if (isCurrent) 18.dp else 16.dp))
                }
            }
        }
    }
}
