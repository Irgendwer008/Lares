/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import de.lares_app.core.policy.Schedule
import de.lares_app.core.policy.ScheduleWindow
import de.lares_app.core.policy.WindowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

class ApplyWindowTest {
    private val x = ScheduleWindow(1, 1, 9 * 60, 60, WindowMode.TIMER, 15, shareGroup = 1)
    private val y = ScheduleWindow(2, 1, 11 * 60, 60, WindowMode.TIMER, 15, shareGroup = 1)
    private val base = Schedule(1, "s", listOf(x, y))

    @Test fun sharingWithAWindowOfAnotherGroupOnlyMovesThatWindow() {
        val w = ScheduleWindow(3, 1, 14 * 60, 60, WindowMode.TIMER)
        val (result, skipped) = applyWindow(base, w, 14 * 60, 60, WindowMode.TIMER, 20, setOf(1), emptySet())!!
        assertEquals(0, skipped)
        val byId = result.windows.associateBy { it.id }
        assertEquals(byId.getValue(3).shareGroup, byId.getValue(1).shareGroup)
        assertNull(byId.getValue(2).shareGroup) // not ticked: left alone (its group had only it left → dissolved)
        assertEquals(20, result.budgetMinutes(byId.getValue(1)))
        assertEquals(20, result.budgetMinutes(byId.getValue(3)))
    }

    @Test fun leavingAGroupKeepsTheOthersTogether() {
        val z = ScheduleWindow(3, 1, 14 * 60, 60, WindowMode.TIMER, 15, shareGroup = 1)
        val s = base.copy(windows = base.windows + z)
        val (result, _) = applyWindow(s, x, 9 * 60, 60, WindowMode.TIMER, 15, emptySet(), emptySet())!!
        val byId = result.windows.associateBy { it.id }
        assertNull(byId.getValue(1).shareGroup)
        assertEquals(1, byId.getValue(2).shareGroup)
        assertEquals(1, byId.getValue(3).shareGroup)
    }

    @Test fun overlapIsRejectedAndOverlappingCopiesAreSkipped() {
        assertNull(applyWindow(base, x, 10 * 60 + 30, 60, WindowMode.BLOCK, 0, emptySet(), emptySet()))
        val blocker = ScheduleWindow(9, 2, 9 * 60, 60, WindowMode.BLOCK)
        val (result, skipped) = applyWindow(base.copy(windows = base.windows + blocker), y, 11 * 60, 60, WindowMode.TIMER, 15, emptySet(), setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY))!!
        assertEquals(0, skipped) // Tuesday 11:00 doesn't overlap 09:00–10:00
        assertEquals(5, result.windows.size)
        val (_, skipped2) = applyWindow(base.copy(windows = base.windows + blocker), x, 9 * 60, 60, WindowMode.TIMER, 15, emptySet(), setOf(DayOfWeek.TUESDAY))!!
        assertEquals(1, skipped2)
    }
}
