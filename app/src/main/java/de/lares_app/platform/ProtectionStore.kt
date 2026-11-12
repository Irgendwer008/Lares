/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import androidx.core.content.edit
import de.lares_app.core.protection.Heartbeat
import de.lares_app.core.protection.WatchedPermission

/**
 * Bookkeeping of the tamper detection (heartbeat, boot counter, clock offset, time zone, working
 * permissions). Separate from the app state, so the frequent heartbeat doesn't re-trigger
 * everything that observes the settings.
 */
class ProtectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("protection", Context.MODE_PRIVATE)

    var heartbeat: Heartbeat?
        get() = if (!prefs.contains(HB_WALL)) {
            null
        } else {
            Heartbeat(
                wallMillis = prefs.getLong(HB_WALL, 0),
                elapsedMillis = prefs.getLong(HB_ELAPSED, 0),
                bootCount = prefs.getInt(HB_BOOT, 0),
                screenOn = prefs.getBoolean(HB_SCREEN, false),
                reportable = prefs.getBoolean(HB_REPORTABLE, false),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(HB_WALL)
            } else {
                putLong(HB_WALL, value.wallMillis)
                putLong(HB_ELAPSED, value.elapsedMillis)
                putInt(HB_BOOT, value.bootCount)
                putBoolean(HB_SCREEN, value.screenOn)
                putBoolean(HB_REPORTABLE, value.reportable)
            }
        }

    var lastBootCount: Int?
        get() = prefs.getInt(BOOT_COUNT, -1).takeIf { it >= 0 }
        set(value) = prefs.edit { if (value == null) remove(BOOT_COUNT) else putInt(BOOT_COUNT, value) }

    /** Wall clock − elapsed realtime, and the boot it was measured in. */
    var clockOffset: Pair<Long, Int>?
        get() = if (prefs.contains(CLOCK_OFFSET)) prefs.getLong(CLOCK_OFFSET, 0) to prefs.getInt(CLOCK_BOOT, 0) else null
        set(value) = prefs.edit {
            if (value == null) remove(CLOCK_OFFSET) else putLong(CLOCK_OFFSET, value.first).putInt(CLOCK_BOOT, value.second)
        }

    var timeZone: String?
        get() = prefs.getString(TIME_ZONE, null)
        set(value) = prefs.edit { putString(TIME_ZONE, value) }

    var working: Set<WatchedPermission>
        get() = prefs.getStringSet(WORKING, emptySet()).orEmpty()
            .mapNotNull { runCatching { WatchedPermission.valueOf(it) }.getOrNull() }.toSet()
        set(value) = prefs.edit { putStringSet(WORKING, value.map { it.name }.toSet()) }

    private companion object {
        const val HB_WALL = "hb_wall"
        const val HB_ELAPSED = "hb_elapsed"
        const val HB_BOOT = "hb_boot"
        const val HB_SCREEN = "hb_screen"
        const val HB_REPORTABLE = "hb_reportable"
        const val BOOT_COUNT = "boot_count"
        const val CLOCK_OFFSET = "clock_offset"
        const val CLOCK_BOOT = "clock_boot"
        const val TIME_ZONE = "time_zone"
        const val WORKING = "working_permissions"
    }
}
