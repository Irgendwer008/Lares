/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.blocking

import de.lares_app.core.blocking.LimitWarnings.Companion.FIVE_MINUTES
import de.lares_app.core.blocking.LimitWarnings.Companion.ONE_MINUTE
import de.lares_app.core.policy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCounterTest {

    @Test fun countsRulesAndGroupsOncePerTick() {
        val counter = UsageCounter(UsageSnapshot(0, 1000))
        assertTrue(counter.add(Decision.Allow(setOf(1L), 60_000L, setOf(10L)), 1_000))
        counter.add(Decision.Allow(setOf(1L, 2L), null, setOf(10L)), 500)
        assertEquals(mapOf(1L to 1_500L, 2L to 500L), counter.snapshot.ruleMillis)
        assertEquals(mapOf(10L to 1_500L), counter.snapshot.groupMillis)
        assertFalse(counter.add(Decision.Allow(), 1_000)) // nothing counted
    }

    @Test fun movingARuleCarriesItsUsedTime() {
        // Rule 1 used 30 min alone; group 10 is fresh, group 11 used 40 min.
        val min = 60_000L
        val counter = UsageCounter(UsageSnapshot(0, 1000, mapOf(1L to 30 * min), mapOf(11L to 40 * min)))
        assertTrue(counter.carryOver(1L, fromGroup = null, toGroup = 10L)) // into a fresh group: no new quota
        assertEquals(30 * min, counter.snapshot.groupMillis[10L])
        assertFalse(counter.carryOver(1L, fromGroup = 10L, toGroup = 11L)) // destination already has more
        assertTrue(counter.carryOver(1L, fromGroup = 11L, toGroup = null)) // out of the group: its time comes along
        assertEquals(40 * min, counter.snapshot.ruleMillis[1L])
    }

    @Test fun warningFollowsTheRemainingTime() {
        val warnings = LimitWarnings()
        val both = listOf(FIVE_MINUTES, ONE_MINUTE)
        val show5 = LimitWarnings.Change.Show(FIVE_MINUTES)
        val show1 = LimitWarnings.Change.Show(ONE_MINUTE)
        assertEquals(null, warnings.update("a", 6 * 60_000L, both))
        assertEquals(show5, warnings.update("a", FIVE_MINUTES, both))
        assertEquals(null, warnings.update("a", 4 * 60_000L, both)) // already shown
        assertEquals(show1, warnings.update("a", 59_000L, both))
        // Limit raised: 30 s → 2 min left goes back to the 5-minute warning …
        assertEquals(show5, warnings.update("a", 2 * 60_000L, both))
        // … and above 5 minutes the warning disappears; it fires again when time runs low.
        assertEquals(LimitWarnings.Change.Remove, warnings.update("a", 10 * 60_000L, both))
        assertEquals(show5, warnings.update("a", 4 * 60_000L, both))
        assertEquals(show1, warnings.update("b", 30_000L, both)) // other subject: straight to 1 min
        assertEquals(null, warnings.update("c", 0L, both)) // already used up: nothing to show
        // Warnings switched off: a shown one is removed.
        assertEquals(LimitWarnings.Change.Remove, warnings.update("b", 30_000L, emptyList()))
        assertEquals(setOf("a"), warnings.reset())
        assertEquals(show5, warnings.update("a", FIVE_MINUTES, listOf(FIVE_MINUTES)))
    }

    @Test fun warningsOfLimitsNoLongerEvaluatedAreRemoved() {
        val warnings = LimitWarnings()
        val both = listOf(FIVE_MINUTES, ONE_MINUTE)
        warnings.update("a", 30_000L, both)
        warnings.update("b", 4 * 60_000L, both)
        // A pause started / app "b" closed: only "a" is still evaluated with time left.
        assertEquals(setOf("b"), warnings.removeAllExcept(setOf("a")))
        assertEquals(emptySet<String>(), warnings.removeAllExcept(setOf("a")))
        assertEquals(LimitWarnings.Change.Show(FIVE_MINUTES), warnings.update("b", 4 * 60_000L, both)) // fires again
    }

    @Test fun movingARuleCarriesItsWindowBudgetsAlong() {
        val counter = UsageCounter(UsageSnapshot(0, 1, windowMillis = mapOf("r1|7/w1@2026-11-23" to 30 * 60_000L)))
        // Into a group with the same schedule: the group's window budget is not fresh.
        assertTrue(counter.carryOver(1L, fromGroup = null, toGroup = 5L))
        assertEquals(30 * 60_000L, counter.snapshot.windowMillis["g5|7/w1@2026-11-23"])
        assertFalse(counter.carryOver(1L, fromGroup = null, toGroup = 5L)) // nothing new
    }
}
