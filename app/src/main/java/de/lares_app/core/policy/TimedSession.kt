/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import de.lares_app.core.time.Clock
import kotlinx.serialization.Serializable

/** What happens to a [TimedSession] when the phone restarts. */
@Serializable
enum class AfterReboot {
    /** The session ends (unlocked mode, pause – ending them is tightening). */
    END,

    /** The session starts over with its full duration (wrong-code waiting time). */
    RESTART,
}

/**
 * A time-boxed state (unlocked mode, pause, wrong-code waiting time), measured with the monotonic
 * `elapsedRealtime` clock, so changing the system clock or time zone never shortens or extends it.
 *
 * `elapsedRealtime` restarts at every boot and there is no trustworthy clock across a reboot (the
 * wall clock can be set freely), so a reboot never extends a session: see [AfterReboot].
 *
 * @property bootId identifies the boot in which [elapsedEndMillis] is valid
 *   (`Settings.Global.BOOT_COUNT`).
 */
@Serializable
data class TimedSession(
    val elapsedEndMillis: Long,
    val bootId: Int,
    val durationMillis: Long,
    val afterReboot: AfterReboot = AfterReboot.END,
) {
    init {
        require(elapsedEndMillis >= 0 && durationMillis > 0)
    }

    fun isActive(nowElapsedMillis: Long, currentBootId: Int): Boolean = remainingMillis(nowElapsedMillis, currentBootId) > 0

    fun remainingMillis(nowElapsedMillis: Long, currentBootId: Int): Long = when {
        // More left than the whole duration: elapsed time restarted although the boot id didn't
        // change (boot counter missing) – treat it like a reboot.
        currentBootId == bootId && elapsedEndMillis - nowElapsedMillis > durationMillis ->
            if (afterReboot == AfterReboot.RESTART) durationMillis else 0
        currentBootId == bootId -> elapsedEndMillis - nowElapsedMillis
        afterReboot == AfterReboot.RESTART -> durationMillis
        else -> 0
    }

    fun isActive(clock: Clock): Boolean = isActive(clock.elapsedMillis(), clock.bootId())

    fun remainingMillis(clock: Clock): Long = remainingMillis(clock.elapsedMillis(), clock.bootId())

    /** True if the session belongs to an earlier boot (a [AfterReboot.RESTART] session must then be restarted). */
    fun isFromEarlierBoot(clock: Clock): Boolean = bootId != clock.bootId()

    /** The same session started over in the current boot. */
    fun restarted(clock: Clock): TimedSession = start(durationMillis, clock, afterReboot)

    companion object {
        fun start(durationMillis: Long, clock: Clock, afterReboot: AfterReboot = AfterReboot.END): TimedSession =
            start(durationMillis, clock.elapsedMillis(), clock.bootId(), afterReboot)

        fun start(durationMillis: Long, nowElapsedMillis: Long, bootId: Int, afterReboot: AfterReboot = AfterReboot.END): TimedSession {
            require(durationMillis > 0) { "duration must be positive" }
            return TimedSession(nowElapsedMillis + durationMillis, bootId, durationMillis, afterReboot)
        }
    }
}
