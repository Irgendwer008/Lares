/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.ChangeKind
import de.lares_app.core.policy.ChangePolicy
import de.lares_app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Settings changes with the tighten/loosen policy applied. */
class SettingsRepository(
    private val store: StateStore,
    private val gate: UnlockGate,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val settings: Flow<AppSettings> = store.state.map { it.settings }

    /** Choosing the mode the first time is free; switching it later is loosening. */
    suspend fun setBlockingMode(mode: BlockingMode) {
        val old = store.current().settings.blockingMode
        if (old == mode) return
        gate.check(if (old == null) ChangeKind.NONE else ChangeKind.LOOSENING)
        store.update { it.copy(settings = it.settings.copy(blockingMode = mode)) }
    }

    suspend fun setWebsiteBlocking(enabled: Boolean) {
        val old = store.current().settings.websiteBlocking
        gate.check(ChangePolicy.classifyToggle(old, enabled))
        store.update { it.copy(settings = it.settings.copy(websiteBlocking = enabled)) }
    }

    suspend fun setProtection(protection: Protection, enabled: Boolean) {
        val current = store.current().settings.protections
        gate.check(ChangePolicy.classifyToggle(protection in current, enabled))
        store.update {
            val set = it.settings.protections
            it.copy(settings = it.settings.copy(protections = if (enabled) set + protection else set - protection))
        }
    }

    /** Countdown overlay and warnings only inform – switching them is never loosening. */
    suspend fun setLimitTimer(enabled: Boolean) {
        store.update { it.copy(settings = it.settings.copy(limitTimer = enabled)) }
    }

    suspend fun setWarnings(fiveMinutes: Boolean, oneMinute: Boolean) {
        store.update { it.copy(settings = it.settings.copy(warnFiveMinutes = fiveMinutes, warnOneMinute = oneMinute)) }
    }

    /** Needs the code (either direction can grant a fresh quota); takes effect after the next reset. */
    suspend fun setResetTime(time: LocalTime) {
        val settings = store.current().settings
        val now = Instant.ofEpochMilli(clock.wallMillis())
        val schedule = settings.resetSchedule.settle(now, zone())
        gate.check(ChangePolicy.classifyResetTime(schedule.pending?.resetTime ?: schedule.resetTime, time))
        val updated = schedule.requestChange(time.withSecond(0).withNano(0), now, zone())
        store.update { it.copy(settings = it.settings.withResetSchedule(updated)) }
    }
}
