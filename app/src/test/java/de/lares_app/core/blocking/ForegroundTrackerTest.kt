/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTrackerTest {
    private val t = ForegroundTracker(lookbackMillis = 60_000, overlapMillis = 2_000)

    @Test fun firstPollLooksBackAndTakesTheLatestApp() {
        assertEquals(40_000L, t.queryStart(100_000))
        assertEquals("b", t.onResumed(listOf("a" to 50_000L, "b" to 90_000L), 100_000))
    }

    @Test fun nextPollOverlapsAndSkipsSeenEvents() {
        t.queryStart(100_000)
        t.onResumed(listOf("a" to 99_500L), 100_000)
        assertEquals(98_000L, t.queryStart(101_000))
        // "a" again from the overlap doesn't count as new; nothing new keeps the current app.
        assertEquals("a", t.onResumed(listOf("a" to 99_500L), 101_000))
        assertEquals("c", t.onResumed(listOf("a" to 99_500L, "c" to 100_800L), 102_000))
        assertEquals("c", t.onResumed(emptyList(), 103_000))
    }

    @Test fun noEventsMeansUnknown() {
        t.queryStart(100_000)
        assertNull(t.onResumed(emptyList(), 100_000))
    }

    @Test fun clockSetBackStartsOver() {
        t.queryStart(100_000)
        t.onResumed(listOf("a" to 99_000L), 100_000)
        // Clock set back: older timestamps must not be ignored as "already seen".
        assertEquals(0L, t.queryStart(60_000))
        assertEquals("b", t.onResumed(listOf("b" to 30_000L), 60_000))
    }
}
