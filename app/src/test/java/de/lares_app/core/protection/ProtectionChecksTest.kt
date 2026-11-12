/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.protection

import de.lares_app.core.data.AppSettings
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.Protection
import de.lares_app.core.protection.WatchedPermission.ACCESSIBILITY
import de.lares_app.core.protection.WatchedPermission.DEVICE_ADMIN
import de.lares_app.core.protection.WatchedPermission.OVERLAY
import de.lares_app.core.protection.WatchedPermission.USAGE_ACCESS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionChecksTest {

    @Test fun neededFollowsTheSettings() {
        val base = AppSettings(protections = emptySet())
        assertEquals(emptySet<WatchedPermission>(), PermissionWatch.needed(base))
        assertEquals(setOf(ACCESSIBILITY), PermissionWatch.needed(base.copy(blockingMode = BlockingMode.ACCESSIBILITY)))
        assertEquals(
            setOf(USAGE_ACCESS, OVERLAY, ACCESSIBILITY, DEVICE_ADMIN),
            PermissionWatch.needed(
                base.copy(blockingMode = BlockingMode.USAGE_STATS, protections = setOf(Protection.SETTINGS_GUARD, Protection.UNINSTALL)),
            ),
        )
        // Website blocking has no effect in Usage access mode, so it needs nothing there.
        assertEquals(setOf(USAGE_ACCESS, OVERLAY), PermissionWatch.needed(base.copy(blockingMode = BlockingMode.USAGE_STATS, websiteBlocking = true)))
    }

    @Test fun onlyLossesOfWorkingPermissionsCount() {
        val needed = setOf(ACCESSIBILITY, DEVICE_ADMIN)
        // Never granted (e.g. right after setup): not tampering.
        assertEquals(emptySet<WatchedPermission>(), PermissionWatch.lost(emptySet(), needed, emptySet()))
        assertEquals(setOf(ACCESSIBILITY), PermissionWatch.lost(setOf(ACCESSIBILITY, DEVICE_ADMIN), needed, setOf(DEVICE_ADMIN)))
        // No longer needed (mode changed): not reported.
        assertEquals(emptySet<WatchedPermission>(), PermissionWatch.lost(setOf(USAGE_ACCESS), needed, emptySet()))
    }

    private fun beat(elapsed: Long, boot: Int = 7, screenOn: Boolean = true, reportable: Boolean = true) =
        Heartbeat(wallMillis = 0, elapsedMillis = elapsed, bootCount = boot, screenOn = screenOn, reportable = reportable)

    @Test fun stopWhileInUseIsAGap() {
        val min = 60_000L
        assertEquals(30L, GapCheck.stoppedMinutes(beat(0), 30 * min, 7))
        assertNull(GapCheck.stoppedMinutes(beat(0), 5 * min, 7)) // short restart
        assertNull(GapCheck.stoppedMinutes(beat(0, boot = 6), 30 * min, 7)) // rebooted
        assertNull(GapCheck.stoppedMinutes(beat(0, screenOn = false), 30 * min, 7)) // phone asleep
        assertNull(GapCheck.stoppedMinutes(beat(0, reportable = false), 30 * min, 7)) // unlocked
        assertNull(GapCheck.stoppedMinutes(null, 30 * min, 7))
    }

    @Test fun bootsWithoutLares() {
        assertEquals(0, GapCheck.bootsWithoutLares(null, 5))
        assertEquals(0, GapCheck.bootsWithoutLares(5, 5))
        assertEquals(0, GapCheck.bootsWithoutLares(5, 6))
        assertEquals(2, GapCheck.bootsWithoutLares(5, 8))
    }

    @Test fun clockJumps() {
        val min = 60_000L
        assertEquals(125L, ClockCheck.jumpMinutes(1_000, 3, 1_000 + 125 * min, 3))
        assertEquals(-60L, ClockCheck.jumpMinutes(1_000, 3, 1_000 - 60 * min, 3))
        assertNull(ClockCheck.jumpMinutes(1_000, 3, 1_000 + 30_000, 3)) // network correction
        assertNull(ClockCheck.jumpMinutes(1_000, 2, 1_000 + 125 * min, 3)) // other boot
        assertNull(ClockCheck.jumpMinutes(null, null, 5, 3))
    }

    @Test fun settingsGuardMatchesTitleOrNonClickableName() {
        val settings = "com.android.settings"
        val header = listOf(SettingsGuard.LabelMatch(clickable = false))
        val listRow = listOf(SettingsGuard.LabelMatch(clickable = true))
        assertTrue(SettingsGuard.isProtectedPage(settings, "Lares", null, emptyList(), "Lares")) // accessibility page
        assertTrue(SettingsGuard.isProtectedPage(settings, "App info", null, header, "Lares")) // app header
        assertFalse(SettingsGuard.isProtectedPage(settings, "Accessibility", null, listRow, "Lares")) // list
        assertFalse(SettingsGuard.isProtectedPage(settings, "App info", null, emptyList(), "Lares")) // other app
    }

    @Test fun installerOnlyGuardsUninstallingLares() {
        val installer = "com.google.android.packageinstaller"
        val name = listOf(SettingsGuard.LabelMatch(clickable = false))
        val uninstaller = "com.android.packageinstaller.UninstallerActivity"
        assertTrue(SettingsGuard.isProtectedPage(installer, "Lares", uninstaller, name, "Lares"))
        assertFalse(SettingsGuard.isProtectedPage(installer, "YouTube", uninstaller, emptyList(), "Lares"))
        // Updating Lares (install dialog) stays possible.
        assertFalse(SettingsGuard.isProtectedPage(installer, "Lares", "com.android.packageinstaller.PackageInstallerActivity", name, "Lares"))
    }
}
