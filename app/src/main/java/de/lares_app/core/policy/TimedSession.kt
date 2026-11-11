/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import de.lares_app.core.time.Clock
import kotlinx.serialization.Serializable

/**
 * End of a pause or edit session. Stored both as wall clock and as `elapsedRealtime`, so
 * changing the system clock doesn't extend it while the device stays booted.
 *
 * @property bootId identifies the boot in which [elapsedEndMillis] is valid
 *   (e.g. `Settings.Global.BOOT_COUNT`).
 */
@Serializable
data class TimedSession(
    val wallEndMillis: Long,
    val elapsedEndMillis: Long,
    val bootId: Int,
) {
    init {
        require(wallEndMillis >= 0 && elapsedEndMillis >= 0)
    }

    fun isActive(nowWallMillis: Long, nowElapsedMillis: Long, currentBootId: Int): Boolean =
        remainingMillis(nowWallMillis, nowElapsedMillis, currentBootId) > 0

    fun remainingMillis(nowWallMillis: Long, nowElapsedMillis: Long, currentBootId: Int): Long =
        if (currentBootId == bootId) {
            elapsedEndMillis - nowElapsedMillis
        } else {
            // After a reboot elapsedRealtime restarts at 0 → fall back to the wall clock.
            wallEndMillis - nowWallMillis
        }

    fun isActive(clock: Clock): Boolean = isActive(clock.wallMillis(), clock.elapsedMillis(), clock.bootId())

    fun remainingMillis(clock: Clock): Long = remainingMillis(clock.wallMillis(), clock.elapsedMillis(), clock.bootId())

    companion object {
        fun start(durationMillis: Long, clock: Clock): TimedSession =
            start(durationMillis, clock.wallMillis(), clock.elapsedMillis(), clock.bootId())

        fun start(durationMillis: Long, nowWallMillis: Long, nowElapsedMillis: Long, bootId: Int): TimedSession {
            require(durationMillis > 0) { "duration must be positive" }
            return TimedSession(nowWallMillis + durationMillis, nowElapsedMillis + durationMillis, bootId)
        }
    }
}
