/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import de.lares_app.blocking.BlockingLog
import de.lares_app.core.data.StateStore
import de.lares_app.core.data.TamperEventType
import de.lares_app.core.data.TamperLog
import de.lares_app.core.protection.ClockCheck
import de.lares_app.core.protection.GapCheck
import de.lares_app.core.protection.Heartbeat
import de.lares_app.core.protection.PermissionWatch
import de.lares_app.core.protection.WatchedPermission
import de.lares_app.core.protection.tamperReportable
import de.lares_app.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TimeZone

/**
 * Detects tampering and records it in the [TamperLog] (from where [AlertDispatcher] notifies the
 * guardian): lost permissions, protection gaps, boots without Lares, clock and time-zone changes,
 * device administrator disabled. Nothing is recorded while Lares is unlocked.
 */
class ProtectionMonitor(
    private val store: StateStore,
    private val tamperLog: TamperLog,
    private val clock: Clock,
    private val permissions: PermissionStatus,
    private val prefs: ProtectionStore,
    /** Screen on right now (read when the heartbeat is written, not when it was requested). */
    private val isInteractive: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Process start (every boot, after updates, when a service starts). */
    fun onProcessStart() = launchLocked {
        val boots = clock.bootId()
        val missed = GapCheck.bootsWithoutLares(prefs.lastBootCount, boots)
        prefs.lastBootCount = boots
        if (missed > 0) report(TamperEventType.PROTECTION_GAP, "boots=$missed")
        val zone = TimeZone.getDefault().id
        prefs.timeZone?.takeIf { it != zone }?.let { report(TamperEventType.TIMEZONE_CHANGED, "from=$it;to=$zone") }
        prefs.timeZone = zone
        checkPermissionsLocked()
    }

    /** The blocking of the chosen mode (re)started: was it stopped while the phone was in use? */
    fun onProtectionStarted() = launchLocked {
        GapCheck.stoppedMinutes(prefs.heartbeat, clock.elapsedMillis(), clock.bootId())?.let {
            report(TamperEventType.PROTECTION_GAP, "minutes=$it")
        }
        beatLocked()
    }

    /** Sign of life while the protection runs (every minute with the screen on, and on screen changes). */
    fun beat() = launchLocked { beatLocked() }

    /** Clean shutdown: the phone is off now, so no gap is counted until it's on again. */
    fun onShutdown() = launchLocked {
        prefs.heartbeat?.let { prefs.heartbeat = it.copy(wallMillis = clock.wallMillis(), screenOn = false) }
    }

    fun checkPermissions(delayMillis: Long = 0) = launchLocked(delayMillis) { checkPermissionsLocked() }

    /** Same, waiting for the result (background work that may end right after). */
    suspend fun checkPermissionsNow() = mutex.withLock { checkPermissionsLocked() }

    fun onTimeChanged() = launchLocked {
        val boot = clock.bootId()
        val offset = clock.wallMillis() - clock.elapsedMillis()
        val previous = prefs.clockOffset
        ClockCheck.jumpMinutes(previous?.first, previous?.second, offset, boot)?.let {
            report(TamperEventType.CLOCK_CHANGED, "minutes=$it")
        }
        prefs.clockOffset = offset to boot
    }

    fun onTimeZoneChanged() = launchLocked {
        val zone = TimeZone.getDefault().id
        prefs.timeZone?.takeIf { it != zone }?.let { report(TamperEventType.TIMEZONE_CHANGED, "from=$it;to=$zone") }
        prefs.timeZone = zone
    }

    /** Called by the device-admin receiver (Android asks it first, so this still gets out). */
    suspend fun onDeviceAdminDisabled() = mutex.withLock {
        // Only while the protection is on (after switching it off in Lares, deactivating is fine).
        val needed = WatchedPermission.DEVICE_ADMIN in PermissionWatch.needed(store.current().settings)
        if (needed && WatchedPermission.DEVICE_ADMIN in prefs.working) report(TamperEventType.DEVICE_ADMIN_DISABLED)
        prefs.working -= WatchedPermission.DEVICE_ADMIN
    }

    private suspend fun beatLocked() {
        val boot = clock.bootId()
        val elapsed = clock.elapsedMillis()
        // First beat after a reboot: stopped while in use and then restarted (no clean shutdown)?
        GapCheck.stoppedBeforeReboot(prefs.heartbeat, clock.wallMillis() - elapsed, boot)?.let {
            report(TamperEventType.PROTECTION_GAP, "minutes=$it")
        }
        prefs.heartbeat = Heartbeat(clock.wallMillis(), elapsed, boot, isInteractive(), store.current().tamperReportable(clock))
        // Keep the clock offset current, so slow network-time drift never adds up to a "jump".
        prefs.clockOffset = (clock.wallMillis() - elapsed) to boot
    }

    private suspend fun checkPermissionsLocked() {
        val settings = store.current().settings
        val needed = PermissionWatch.needed(settings)
        val granted = PermissionWatch.granted(permissions.current())
        PermissionWatch.lost(prefs.working, needed, granted).forEach { lost ->
            when (lost) {
                WatchedPermission.USAGE_ACCESS, WatchedPermission.OVERLAY -> report(lost.event, "permission=${lost.name}")
                else -> report(lost.event)
            }
        }
        prefs.working = needed intersect granted
    }

    private suspend fun report(type: TamperEventType, details: String? = null) {
        if (!store.current().tamperReportable(clock)) return
        BlockingLog.d { "tamper: $type $details" }
        tamperLog.record(type, details)
    }

    private fun launchLocked(delayMillis: Long = 0, block: suspend () -> Unit) =
        scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            mutex.withLock { block() }
        }
}
