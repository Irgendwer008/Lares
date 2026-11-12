/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.protection

import de.lares_app.core.data.AppSettings
import de.lares_app.core.data.AppState
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.GrantedPermissions
import de.lares_app.core.data.Protection
import de.lares_app.core.data.TamperEventType
import de.lares_app.core.time.Clock
import kotlin.math.abs

/**
 * Tamper events are only recorded while Lares is set up and locked: whoever changes something
 * in unlocked mode has entered the guardian code. A pause doesn't count as unlocked.
 */
fun AppState.tamperReportable(clock: Clock): Boolean =
    settings.setupComplete && unlock.isSetUp && unlock.unlockedSession?.isActive(clock) != true

/** System permissions whose loss is reported (when the settings need them). */
enum class WatchedPermission(val event: TamperEventType) {
    ACCESSIBILITY(TamperEventType.ACCESSIBILITY_DISABLED),
    USAGE_ACCESS(TamperEventType.PERMISSION_REVOKED),
    OVERLAY(TamperEventType.PERMISSION_REVOKED),
    DEVICE_ADMIN(TamperEventType.DEVICE_ADMIN_DISABLED),
}

object PermissionWatch {
    /** What the current settings need (same rules as [de.lares_app.core.data.SetupRequirements]). */
    fun needed(settings: AppSettings): Set<WatchedPermission> = buildSet {
        val mode = settings.blockingMode ?: return@buildSet
        if (mode == BlockingMode.ACCESSIBILITY || settings.websiteBlockingActive || Protection.SETTINGS_GUARD in settings.protections) {
            add(WatchedPermission.ACCESSIBILITY)
        }
        if (mode == BlockingMode.USAGE_STATS) {
            add(WatchedPermission.USAGE_ACCESS)
            add(WatchedPermission.OVERLAY)
        }
        if (Protection.UNINSTALL in settings.protections) add(WatchedPermission.DEVICE_ADMIN)
    }

    fun granted(granted: GrantedPermissions): Set<WatchedPermission> = buildSet {
        if (granted.accessibility) add(WatchedPermission.ACCESSIBILITY)
        if (granted.usageAccess) add(WatchedPermission.USAGE_ACCESS)
        if (granted.overlay) add(WatchedPermission.OVERLAY)
        if (granted.deviceAdmin) add(WatchedPermission.DEVICE_ADMIN)
    }

    /**
     * Permissions that were working ([previouslyWorking]) and are needed, but are gone now. Only
     * losses count – something never granted (e.g. right after setup) is a setup issue, not tampering.
     */
    fun lost(previouslyWorking: Set<WatchedPermission>, needed: Set<WatchedPermission>, granted: Set<WatchedPermission>) =
        (previouslyWorking intersect needed) - granted
}

/** Last sign of life of the running protection (see [GapCheck]). */
data class Heartbeat(
    val wallMillis: Long,
    val elapsedMillis: Long,
    val bootCount: Int,
    val screenOn: Boolean,
    /** Locked at that time (a stop in unlocked mode isn't reported). */
    val reportable: Boolean,
)

object GapCheck {
    /** Shorter stops are ignored (restarts by the system take a moment). */
    const val STOP_THRESHOLD_MILLIS = 10 * 60_000L

    /**
     * Minutes the protection was stopped while the phone was in use, or null. Only within one boot
     * (a reboot is normal) and only if the last heartbeat came with the screen on – after the
     * screen went off, a later start says nothing about when the phone was used again.
     */
    fun stoppedMinutes(last: Heartbeat?, nowElapsedMillis: Long, bootCount: Int): Long? {
        if (last == null || last.bootCount != bootCount || !last.screenOn || !last.reportable) return null
        val gap = nowElapsedMillis - last.elapsedMillis
        return if (gap > STOP_THRESHOLD_MILLIS) gap / 60_000L else null
    }

    /**
     * Minutes from the last heartbeat of an earlier boot to the start of this boot, if it came
     * with the screen on – a clean shutdown marks the heartbeat "screen off" first. So: protection
     * stopped while in use (e.g. force stop), the phone used further, then restarted.
     */
    fun stoppedBeforeReboot(last: Heartbeat?, bootStartWallMillis: Long, bootCount: Int): Long? {
        if (last == null || last.bootCount == bootCount || !last.screenOn || !last.reportable) return null
        val gap = bootStartWallMillis - last.wallMillis
        return if (gap > STOP_THRESHOLD_MILLIS) gap / 60_000L else null
    }

    /**
     * Phone starts in which Lares didn't run at all (e.g. safe mode: third-party apps don't run).
     * Lares starts on every normal boot, so the boot counter must have grown by exactly one.
     */
    fun bootsWithoutLares(lastBootCount: Int?, bootCount: Int): Int =
        if (lastBootCount == null || bootCount <= lastBootCount) 0 else bootCount - lastBootCount - 1
}

object ClockCheck {
    /** Network time corrections are small; only real changes are reported. */
    const val THRESHOLD_MILLIS = 2 * 60_000L

    /**
     * Minutes the wall clock jumped (positive = forward), or null. The offset wall − elapsed
     * stays constant unless the clock is set; offsets from another boot can't be compared.
     */
    fun jumpMinutes(previousOffset: Long?, previousBoot: Int?, offset: Long, bootCount: Int): Long? {
        if (previousOffset == null || previousBoot != bootCount) return null
        val delta = offset - previousOffset
        return if (abs(delta) > THRESHOLD_MILLIS) delta / 60_000L else null
    }
}
