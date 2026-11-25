/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ScheduleTest {
    // 2026-11-23 is a Monday.
    private fun at(s: String) = LocalDateTime.parse(s)

    private val school = Schedule(
        id = 1,
        name = "School",
        windows = listOf(
            ScheduleWindow(id = 1, day = 1, startMinute = 9 * 60, lengthMinutes = 8 * 60, mode = WindowMode.BLOCK),
            ScheduleWindow(id = 2, day = 1, startMinute = 22 * 60, lengthMinutes = 9 * 60, mode = WindowMode.BLOCK), // Mon night → Tue 07:00
            ScheduleWindow(id = 3, day = 2, startMinute = 16 * 60, lengthMinutes = 60, mode = WindowMode.TIMER, minutes = 15, shareGroup = 1),
            ScheduleWindow(id = 4, day = 2, startMinute = 18 * 60, lengthMinutes = 60, mode = WindowMode.TIMER, minutes = 15, shareGroup = 1),
            ScheduleWindow(id = 5, day = 6, startMinute = 10 * 60, lengthMinutes = 120, mode = WindowMode.FREE),
        ),
        outside = WindowMode.TIMER,
        outsideMinutes = 60,
    )

    @Test fun segmentsInsideAndOutside() {
        val inside = school.segmentAt(at("2026-11-23T10:00"))
        assertEquals(WindowMode.BLOCK, inside.mode)
        assertEquals(at("2026-11-23T17:00"), inside.end)

        val outside = school.segmentAt(at("2026-11-23T18:00"))
        assertEquals(WindowMode.TIMER, outside.mode)
        assertNull(outside.budgetKey) // the period's budget
        assertEquals(60, outside.minutes)
        assertEquals(at("2026-11-23T17:00"), outside.start)
        assertEquals(at("2026-11-23T22:00"), outside.end)
    }

    @Test fun windowCrossingMidnightBelongsToItsStartDay() {
        val tuesdayMorning = school.segmentAt(at("2026-11-24T06:30"))
        assertEquals(WindowMode.BLOCK, tuesdayMorning.mode)
        assertEquals(2, tuesdayMorning.window?.id)
        assertEquals(at("2026-11-23T22:00"), tuesdayMorning.start)
        assertEquals(at("2026-11-24T07:00"), tuesdayMorning.end)
        assertEquals(WindowMode.TIMER, school.segmentAt(at("2026-11-24T07:00")).mode) // ended
    }

    @Test fun sharedTimersUseOneBudgetPerDay() {
        val a = school.segmentAt(at("2026-11-24T16:30"))
        val b = school.segmentAt(at("2026-11-24T18:30"))
        assertEquals("1/s1@2026-11-24", a.budgetKey)
        assertEquals(a.budgetKey, b.budgetKey)
        assertEquals(15, b.minutes)
        // Next week: a new budget.
        assertEquals("1/s1@2026-12-01", school.segmentAt(at("2026-12-01T16:30")).budgetKey)
    }

    @Test fun noWindowsIsTheSameForever() {
        val s = Schedule.dailyLimit(30).segmentAt(at("2026-11-23T12:00"))
        assertEquals(WindowMode.TIMER, s.mode)
        assertNull(s.start)
        assertNull(s.end)
        assertEquals(WindowMode.BLOCK, Schedule.alwaysBlocked.segmentAt(at("2026-11-23T12:00")).mode)
    }

    @Test fun validation() {
        assertTrue(school.isValid())
        // Overlap with the Monday night window (spills into Tuesday until 07:00).
        assertFalse(school.copy(windows = school.windows + ScheduleWindow(9, 2, 6 * 60, 60, WindowMode.FREE)).isValid())
        // Sunday night into Monday overlaps Monday's first window? No: ends 08:00 < 09:00.
        assertTrue(school.copy(windows = school.windows + ScheduleWindow(9, 7, 23 * 60, 9 * 60, WindowMode.BLOCK)).isValid())
        assertFalse(school.copy(windows = school.windows + ScheduleWindow(9, 7, 23 * 60, 11 * 60, WindowMode.BLOCK)).isValid())
        // Shared budget across days is not allowed.
        assertFalse(school.copy(windows = school.windows + ScheduleWindow(9, 3, 16 * 60, 60, WindowMode.TIMER, 15, shareGroup = 1)).isValid())
        // Duplicate ids, bad ranges.
        assertFalse(school.copy(windows = school.windows + ScheduleWindow(1, 4, 0, 60, WindowMode.FREE)).isValid())
        assertFalse(Schedule(1, "x", listOf(ScheduleWindow(1, 8, 0, 60, WindowMode.FREE))).isValid())
        assertFalse(Schedule(1, "x", listOf(ScheduleWindow(1, 1, 0, 0, WindowMode.FREE))).isValid())
    }
}
