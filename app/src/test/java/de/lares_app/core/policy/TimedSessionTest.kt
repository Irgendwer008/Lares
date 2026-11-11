/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedSessionTest {

    private val s = TimedSession.start(durationMillis = 15 * 60_000L, nowWallMillis = 1_000_000L, nowElapsedMillis = 5_000L, bootId = 3)

    @Test fun usesElapsedRealtimeWithinTheSameBoot() {
        assertTrue(s.isActive(nowWallMillis = 1_000_000L, nowElapsedMillis = 5_000L, currentBootId = 3))
        // Clock moved back an hour: still ends on time.
        assertFalse(s.isActive(nowWallMillis = 1_000_000L - 3_600_000L, nowElapsedMillis = 5_000L + 15 * 60_000L, currentBootId = 3))
        assertEquals(60_000L, s.remainingMillis(0L, 5_000L + 14 * 60_000L, 3))
    }

    @Test fun fallsBackToWallClockAfterReboot() {
        assertTrue(s.isActive(nowWallMillis = 1_000_000L + 60_000L, nowElapsedMillis = 10L, currentBootId = 4))
        assertFalse(s.isActive(nowWallMillis = 1_000_000L + 15 * 60_000L, nowElapsedMillis = 10L, currentBootId = 4))
    }
}
