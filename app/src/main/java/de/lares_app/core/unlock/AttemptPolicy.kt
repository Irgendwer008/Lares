/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

/**
 * Brute-force protection: the first [FREE_ATTEMPTS] wrong codes cost nothing, then every
 * further failure locks code entry, starting at 1 min and doubling up to [MAX_LOCKOUT_MILLIS].
 */
object AttemptPolicy {
    const val FREE_ATTEMPTS = 4
    private const val FIRST_LOCKOUT_MILLIS = 60_000L
    const val MAX_LOCKOUT_MILLIS = 24 * 60 * 60_000L

    /** Lockout that starts after the [failures]-th consecutive wrong code (0 = none). */
    fun lockoutMillisAfter(failures: Int): Long {
        if (failures <= FREE_ATTEMPTS) return 0L
        val exponent = (failures - FREE_ATTEMPTS - 1).coerceAtMost(30)
        return (FIRST_LOCKOUT_MILLIS shl exponent).coerceAtMost(MAX_LOCKOUT_MILLIS)
    }

    /** Report "repeated wrong code" to the guardian at the 5th failure and every 5th after. */
    fun shouldReport(failures: Int): Boolean =
        failures > FREE_ATTEMPTS && (failures - FREE_ATTEMPTS - 1) % 5 == 0

    fun attemptsBeforeLockout(failures: Int): Int = (FREE_ATTEMPTS - failures).coerceAtLeast(0)
}
