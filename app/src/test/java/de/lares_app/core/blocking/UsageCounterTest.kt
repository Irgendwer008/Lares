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

    @Test fun warningsFireOncePerThresholdAndPeriod() {
        val warnings = LimitWarnings()
        val both = listOf(FIVE_MINUTES, ONE_MINUTE)
        assertEquals(emptyList<Long>(), warnings.crossed("a", 6 * 60_000L, both))
        assertEquals(listOf(FIVE_MINUTES), warnings.crossed("a", FIVE_MINUTES, both))
        assertEquals(emptyList<Long>(), warnings.crossed("a", 4 * 60_000L, both)) // already sent
        assertEquals(listOf(ONE_MINUTE), warnings.crossed("a", 59_000L, both))
        assertEquals(listOf(FIVE_MINUTES, ONE_MINUTE), warnings.crossed("b", 30_000L, both)) // other subject
        assertEquals(emptyList<Long>(), warnings.crossed("c", 0L, both)) // already blocked, no warning
        warnings.reset()
        assertEquals(listOf(FIVE_MINUTES), warnings.crossed("a", FIVE_MINUTES, listOf(FIVE_MINUTES)))
    }
}
