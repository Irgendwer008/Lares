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

    private val s = TimedSession.start(durationMillis = 15 * 60_000L, nowElapsedMillis = 5_000L, bootId = 3)

    @Test fun usesTheMonotonicClockWithinTheSameBoot() {
        assertTrue(s.isActive(nowElapsedMillis = 5_000L, currentBootId = 3))
        assertEquals(60_000L, s.remainingMillis(5_000L + 14 * 60_000L, 3))
        assertFalse(s.isActive(nowElapsedMillis = 5_000L + 15 * 60_000L, currentBootId = 3))
    }

    @Test fun endSessionsStopOnReboot() {
        // Unlocked mode / pause: a reboot ends them, so changing the clock afterwards can't extend them.
        assertFalse(s.isActive(nowElapsedMillis = 10L, currentBootId = 4))
    }

    @Test fun restartSessionsStartOverAfterReboot() {
        val lockout = TimedSession.start(60_000L, nowElapsedMillis = 5_000L, bootId = 3, afterReboot = AfterReboot.RESTART)
        assertEquals(60_000L, lockout.remainingMillis(nowElapsedMillis = 10L, currentBootId = 4))
    }
}
