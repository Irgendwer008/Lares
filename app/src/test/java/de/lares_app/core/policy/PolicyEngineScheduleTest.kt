/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PolicyEngineScheduleTest {
    // 2026-11-23 is a Monday.
    private fun at(s: String) = LocalDateTime.parse(s)
    private val app = "com.instagram.android"

    private val evenings = Schedule(
        id = 7,
        name = "Evenings",
        windows = listOf(
            ScheduleWindow(1, day = 1, startMinute = 9 * 60, lengthMinutes = 8 * 60, mode = WindowMode.BLOCK),
            ScheduleWindow(2, day = 1, startMinute = 18 * 60, lengthMinutes = 2 * 60, mode = WindowMode.TIMER, minutes = 30),
            ScheduleWindow(3, day = 1, startMinute = 20 * 60, lengthMinutes = 60, mode = WindowMode.FREE),
        ),
        outside = WindowMode.BLOCK,
    )

    private fun snapshot(now: String, rules: List<Rule> = listOf(Rule(1, RuleType.APP, app, scheduleId = 7)), groups: Map<Long, RuleGroup> = emptyMap(), windowUsed: Map<String, Long> = emptyMap(), used: Map<Long, Long> = emptyMap()) =
        PolicySnapshot(
            rules = rules,
            groups = groups,
            usedMillis = used,
            schedules = mapOf(7L to evenings),
            now = at(now),
            periodEnd = at("2026-11-24T00:00"),
            windowUsedMillis = windowUsed,
        )

    @Test fun fullBlockWindowSaysWhenItsFreeAgain() {
        val d = PolicyEngine.evaluate(app, null, snapshot("2026-11-23T10:00")) as Decision.Block
        assertEquals(BlockReason.SCHEDULE, d.reason)
        // 17:00 the window ends, but outside is blocked until the timer window at 18:00.
        assertEquals(at("2026-11-23T18:00"), d.until)
    }

    @Test fun timerWindowCountsItsOwnBudget() {
        val d = PolicyEngine.evaluate(app, null, snapshot("2026-11-23T18:10")) as Decision.Allow
        assertEquals(setOf("r1|7/w2@2026-11-23"), d.countedWindowKeys)
        assertTrue(d.countedRuleIds.isEmpty())
        assertEquals(30 * 60_000L, d.remainingMillis)

        val used = PolicyEngine.evaluate(app, null, snapshot("2026-11-23T18:40", windowUsed = mapOf("r1|7/w2@2026-11-23" to 30 * 60_000L)))
        used as Decision.Block
        assertEquals(BlockReason.LIMIT_REACHED, used.reason)
        assertEquals(at("2026-11-23T20:00"), used.until) // the free window follows
    }

    @Test fun countdownBeforeTheScheduleBlocks() {
        // Free window 20:00–21:00, then outside = full block: the countdown runs to 21:00.
        val d = PolicyEngine.evaluate(app, null, snapshot("2026-11-23T20:56")) as Decision.Allow
        assertEquals(4 * 60_000L, d.remainingMillis)
        assertEquals(setOf("r1"), d.restrictedOwners)
        assertTrue(d.countedWindowKeys.isEmpty())
    }

    @Test fun groupScheduleAppliesToMembersAndSharesTheBudget() {
        val group = RuleGroup(3, "Social", scheduleId = 7)
        val rules = listOf(Rule(1, RuleType.APP, app, groupId = 3), Rule(2, RuleType.APP, "com.reddit.frontpage", groupId = 3))
        val d = PolicyEngine.evaluate("com.reddit.frontpage", null, snapshot("2026-11-23T18:10", rules, mapOf(3L to group))) as Decision.Allow
        assertEquals(setOf("g3|7/w2@2026-11-23"), d.countedWindowKeys)
    }

    @Test fun unknownScheduleBlocks() {
        val s = snapshot("2026-11-23T20:30").copy(schedules = emptyMap())
        val d = PolicyEngine.evaluate(app, null, s) as Decision.Block
        assertEquals(BlockReason.SCHEDULE, d.reason)
    }

    @Test fun outsideTimerUsedUpIsFreeAtTheNextWindowOrTheReset() {
        val s = Schedule(8, "Limit + free evening", listOf(ScheduleWindow(1, 1, 20 * 60, 60, WindowMode.FREE)), WindowMode.TIMER, 60)
        val snap = snapshot("2026-11-23T15:00", rules = listOf(Rule(1, RuleType.APP, app, scheduleId = 8)), used = mapOf(1L to 60 * 60_000L))
            .copy(schedules = mapOf(8L to s))
        val d = PolicyEngine.evaluate(app, null, snap) as Decision.Block
        assertEquals(BlockReason.LIMIT_REACHED, d.reason)
        assertEquals(at("2026-11-23T20:00"), d.until)
        // After the free window: blocked until the period ends.
        val late = PolicyEngine.evaluate(app, null, snap.copy(now = at("2026-11-23T22:00"))) as Decision.Block
        assertEquals(at("2026-11-24T00:00"), late.until)
    }

    @Test fun dailyLimitAndAlwaysBlockedBehaveAsBefore() {
        val rules = listOf(Rule(1, RuleType.APP, app, limitMinutesPerDay = 10), Rule(2, RuleType.APP, "com.reddit.frontpage"))
        val allow = PolicyEngine.evaluate(app, null, snapshot("2026-11-23T12:00", rules)) as Decision.Allow
        assertEquals(setOf(1L), allow.countedRuleIds)
        assertEquals(10 * 60_000L, allow.remainingMillis)
        val block = PolicyEngine.evaluate("com.reddit.frontpage", null, snapshot("2026-11-23T12:00", rules)) as Decision.Block
        assertEquals(BlockReason.ALWAYS_ON, block.reason)
        assertEquals(null, block.until)
    }

    @Test fun availableAgainConsidersAUsedUpOutsideBudget() {
        // Block window until 12:00, then the "outside" timer – already used up today.
        val s = Schedule(9, "x", listOf(ScheduleWindow(1, 1, 9 * 60, 3 * 60, WindowMode.BLOCK), ScheduleWindow(2, 1, 20 * 60, 60, WindowMode.FREE)), WindowMode.TIMER, 30)
        val snap = snapshot("2026-11-23T10:00", rules = listOf(Rule(1, RuleType.APP, app, scheduleId = 9)), used = mapOf(1L to 30 * 60_000L))
            .copy(schedules = mapOf(9L to s))
        val d = PolicyEngine.evaluate(app, null, snap) as Decision.Block
        assertEquals(at("2026-11-23T20:00"), d.until) // not 12:00
    }

    @Test fun countdownWhenTheNextBudgetIsUsedUp() {
        // Free until 18:00, then a timer window whose budget is already used: blocked at 18:00.
        val used = mapOf("r1|7/w2@2026-11-23" to 30 * 60_000L)
        val s = evenings.copy(windows = evenings.windows + ScheduleWindow(4, 1, 17 * 60, 60, WindowMode.FREE))
        val d = PolicyEngine.evaluate(app, null, snapshot("2026-11-23T17:57", windowUsed = used).copy(schedules = mapOf(7L to s))) as Decision.Allow
        assertEquals(3 * 60_000L, d.remainingMillis)
    }

    @Test fun countdownAcrossDaylightSavingIsRealTime() {
        // 2027-03-28: clocks go forward at 02:00 in Berlin. 01:50 → block at 03:00 is 10 min away.
        val s = Schedule(5, "x", listOf(ScheduleWindow(1, 7, 3 * 60, 60, WindowMode.BLOCK)), WindowMode.FREE)
        val snap = PolicySnapshot(
            rules = listOf(Rule(1, RuleType.APP, app, scheduleId = 5)),
            schedules = mapOf(5L to s),
            now = at("2027-03-28T01:50"),
            zone = java.time.ZoneId.of("Europe/Berlin"),
        )
        assertEquals(10 * 60_000L, (PolicyEngine.evaluate(app, null, snap) as Decision.Allow).remainingMillis)
    }
}
