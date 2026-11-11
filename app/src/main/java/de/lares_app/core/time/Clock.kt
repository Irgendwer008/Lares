/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.time

/** Abstraction over wall clock, monotonic clock and boot identity (testable). */
interface Clock {
    /** Wall clock, `System.currentTimeMillis()`. Can be changed by the user. */
    fun wallMillis(): Long

    /** Monotonic time since boot, `SystemClock.elapsedRealtime()`. */
    fun elapsedMillis(): Long

    /** Changes on every reboot (`Settings.Global.BOOT_COUNT`). */
    fun bootId(): Int
}
