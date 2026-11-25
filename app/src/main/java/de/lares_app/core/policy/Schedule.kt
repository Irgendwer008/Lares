/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What applies inside a window, or outside all windows of a schedule. */
@Serializable
enum class WindowMode {
    /** Blocked the whole time. */
    BLOCK,

    /** A time budget ([ScheduleWindow.minutes] / [Schedule.outsideMinutes]). */
    TIMER,

    /** Not restricted. */
    FREE,
}

/**
 * One time window of a [Schedule] on a weekday. It may cross midnight (start + length > 24 h) and
 * then belongs to its start day (e.g. Mon 22:00 for 9 h = Monday night until Tuesday 07:00).
 *
 * @property id unique within the schedule (references, budgets, editor).
 * @property day weekday the window starts on (ISO: 1 = Monday … 7 = Sunday).
 * @property startMinute minute of the day the window starts (0 until 1439).
 * @property lengthMinutes 1 until 1440.
 * @property minutes budget for [WindowMode.TIMER].
 * @property shareGroup [WindowMode.TIMER] windows of the same day with the same value share one
 *   budget (that of the window with the lowest [id]; the editor keeps them equal).
 */
@Serializable
data class ScheduleWindow(
    val id: Int,
    val day: Int,
    val startMinute: Int,
    val lengthMinutes: Int,
    val mode: WindowMode,
    val minutes: Int = 0,
    val shareGroup: Int? = null,
) {
    val dayOfWeek: DayOfWeek get() = DayOfWeek.of(day)
    val endMinute: Int get() = startMinute + lengthMinutes

    fun isValid(): Boolean = day in 1..7 && startMinute in 0 until Schedule.MINUTES_PER_DAY && lengthMinutes in 1..Schedule.MINUTES_PER_DAY &&
        minutes in 0..Schedule.MINUTES_PER_DAY && (shareGroup == null || mode == WindowMode.TIMER)
}

/**
 * Named weekly time table that rules and groups can use instead of "always blocked" / a daily limit:
 * time windows per weekday ([windows]) and what applies outside them ([outside]). "Always blocked"
 * and "daily limit" are the same as a schedule without windows ([alwaysBlocked], [dailyLimit]).
 */
data class Schedule(
    val id: Long,
    val name: String,
    val windows: List<ScheduleWindow> = emptyList(),
    val outside: WindowMode = WindowMode.FREE,
    /** Budget for all time outside the windows in one usage period, for [WindowMode.TIMER]. */
    val outsideMinutes: Int = 0,
) {
    /** Valid windows that don't overlap (also across midnight and from Sunday into Monday). */
    fun isValid(): Boolean {
        if (outsideMinutes !in 0..MINUTES_PER_DAY || windows.any { !it.isValid() }) return false
        if (windows.map { it.id }.toSet().size != windows.size) return false
        val intervals = windows.map { weekStart(it) to weekStart(it) + it.lengthMinutes }
        for (i in intervals.indices) {
            for (j in i + 1 until intervals.size) {
                if (overlapsCyclic(intervals[i], intervals[j])) return false
            }
        }
        // Shared budgets only between windows of the same day.
        return windows.filter { it.shareGroup != null }.groupBy { it.shareGroup }.values.all { group -> group.map { it.day }.toSet().size == 1 }
    }

    /** What applies at [now] (local time). */
    fun segmentAt(now: LocalDateTime): Segment {
        for (window in windows) {
            val start = instanceStartCovering(window, now) ?: continue
            return Segment(
                mode = window.mode,
                start = start,
                end = start.plusMinutes(window.lengthMinutes.toLong()),
                minutes = budgetMinutes(window),
                budgetKey = if (window.mode == WindowMode.TIMER) budgetKey(id, window, start.toLocalDate()) else null,
                window = window,
            )
        }
        val next = nextWindowStart(now)
        val previous = previousWindowEnd(now)
        return Segment(mode = outside, start = previous, end = next, minutes = outsideMinutes, budgetKey = null, window = null)
    }

    /** The budget of [window] (shared: the lowest window id of its group decides). */
    fun budgetMinutes(window: ScheduleWindow): Int {
        val group = window.shareGroup ?: return window.minutes
        return windows.filter { it.shareGroup == group && it.day == window.day }.minByOrNull { it.id }?.minutes ?: window.minutes
    }

    /** Start of the instance of [window] that contains [now], if any (today's or yesterday's). */
    private fun instanceStartCovering(window: ScheduleWindow, now: LocalDateTime): LocalDateTime? {
        for (daysBack in 0L..1L) {
            val date = now.toLocalDate().minusDays(daysBack)
            if (date.dayOfWeek != window.dayOfWeek) continue
            val start = date.atStartOfDay().plusMinutes(window.startMinute.toLong())
            if (!now.isBefore(start) && now.isBefore(start.plusMinutes(window.lengthMinutes.toLong()))) return start
        }
        return null
    }

    /** Next start of any window after [now] (null if there are no windows). */
    private fun nextWindowStart(now: LocalDateTime): LocalDateTime? = windows.mapNotNull { w ->
        (0L..7L).asSequence()
            .map { now.toLocalDate().plusDays(it) }
            .filter { it.dayOfWeek == w.dayOfWeek }
            .map { it.atStartOfDay().plusMinutes(w.startMinute.toLong()) }
            .firstOrNull { it.isAfter(now) }
    }.minOrNull()

    /** Last end of any window before [now] (null if there are no windows). */
    private fun previousWindowEnd(now: LocalDateTime): LocalDateTime? = windows.mapNotNull { w ->
        (-1L..8L).asSequence()
            .map { now.toLocalDate().minusDays(it) }
            .filter { it.dayOfWeek == w.dayOfWeek }
            .map { it.atStartOfDay().plusMinutes(w.endMinute.toLong()) }
            .firstOrNull { !it.isAfter(now) }
    }.maxOrNull()

    /**
     * What applies at a moment.
     * @property start / end when it began / ends (null: no windows at all – the same forever).
     * @property minutes budget for [WindowMode.TIMER].
     * @property budgetKey identifies a window budget (schedule, window or shared group, start date); null
     *   for the "outside" time, whose budget is the usage period's (like a daily limit).
     */
    data class Segment(
        val mode: WindowMode,
        val start: LocalDateTime?,
        val end: LocalDateTime?,
        val minutes: Int,
        val budgetKey: String?,
        val window: ScheduleWindow?,
    )

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        private const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

        /** "Always blocked" as a schedule. */
        val alwaysBlocked = Schedule(id = 0, name = "", outside = WindowMode.BLOCK)

        /** "Daily limit" as a schedule. */
        fun dailyLimit(minutes: Int) = Schedule(id = 0, name = "", outside = WindowMode.TIMER, outsideMinutes = minutes)

        /** "7/w3@2026-11-25" (window 3 of schedule 7) or "7/s1@…" (shared timer 1): another schedule never shares it. */
        fun budgetKey(scheduleId: Long, window: ScheduleWindow, startDate: LocalDate): String =
            "$scheduleId/" + (window.shareGroup?.let { "s$it" } ?: "w${window.id}") + "@" + startDate

        private fun weekStart(w: ScheduleWindow) = (w.day - 1) * MINUTES_PER_DAY + w.startMinute

        private fun overlapsCyclic(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean =
            (-1..1).any { shift ->
                val bs = b.first + shift * MINUTES_PER_WEEK
                val be = b.second + shift * MINUTES_PER_WEEK
                a.first < be && bs < a.second
            }

        fun timeOf(minuteOfDay: Int): LocalTime = LocalTime.of((minuteOfDay / 60) % 24, minuteOfDay % 60)
    }
}
