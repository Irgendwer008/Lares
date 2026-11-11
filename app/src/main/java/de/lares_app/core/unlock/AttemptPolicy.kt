/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

/**
 * Brute-force protection. `failures` = consecutive wrong codes so far (reset by a correct code).
 *
 * The [MAX_ATTEMPTS]-th wrong code in a row starts the first waiting time (1 min); every further
 * wrong code doubles it, up to [MAX_LOCKOUT_MILLIS]:
 *
 * | failures | attempts left | waiting time |
 * |----------|---------------|--------------|
 * | 1        | 2             | –            |
 * | 2        | 1             | –            |
 * | 3        | 0             | 1 min        |
 * | 4        | 0             | 2 min        |
 * | 5        | 0             | 4 min …      |
 */
object AttemptPolicy {
    /** Wrong codes allowed in a row; the last of them starts the first waiting time. */
    const val MAX_ATTEMPTS = 3
    private const val FIRST_LOCKOUT_MILLIS = 60_000L
    const val MAX_LOCKOUT_MILLIS = 24 * 60 * 60_000L

    /** Waiting time that starts after the [failures]-th consecutive wrong code (0 = none). */
    fun lockoutMillisAfter(failures: Int): Long {
        if (failures < MAX_ATTEMPTS) return 0L
        val exponent = (failures - MAX_ATTEMPTS).coerceAtMost(30)
        return (FIRST_LOCKOUT_MILLIS shl exponent).coerceAtMost(MAX_LOCKOUT_MILLIS)
    }

    /** Report "repeated wrong code" to the guardian at the first waiting time and every 5th failure after. */
    fun shouldReport(failures: Int): Boolean =
        failures >= MAX_ATTEMPTS && (failures - MAX_ATTEMPTS) % 5 == 0

    /** Attempts the user can still make before a wrong code starts a waiting time. */
    fun attemptsLeft(failures: Int): Int = (MAX_ATTEMPTS - failures).coerceAtLeast(0)
}
