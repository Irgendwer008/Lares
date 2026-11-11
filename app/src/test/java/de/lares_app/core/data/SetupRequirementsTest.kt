/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.data.SetupIssue.ACCESSIBILITY
import de.lares_app.core.data.SetupIssue.DEVICE_ADMIN
import de.lares_app.core.data.SetupIssue.NO_BLOCKING_MODE
import de.lares_app.core.data.SetupIssue.OVERLAY
import de.lares_app.core.data.SetupIssue.USAGE_ACCESS
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupRequirementsTest {
    // Battery exemption granted by default, so the other tests only see what they check.
    private val none = GrantedPermissions(accessibility = false, usageAccess = false, overlay = false, deviceAdmin = false, batteryUnrestricted = true)
    private val all = GrantedPermissions(accessibility = true, usageAccess = true, overlay = true, deviceAdmin = true, batteryUnrestricted = true)
    private val base = AppSettings(protections = emptySet())

    private fun missing(settings: AppSettings, granted: GrantedPermissions = none) = SetupRequirements.missing(settings, granted)

    @Test fun noModeChosenListsEverythingAtOnce() {
        // The recommended Accessibility mode's needs are shown right away.
        assertEquals(listOf(NO_BLOCKING_MODE, ACCESSIBILITY), missing(base))
        assertEquals(listOf(NO_BLOCKING_MODE, ACCESSIBILITY, DEVICE_ADMIN), missing(base.copy(protections = setOf(Protection.UNINSTALL))))
        assertEquals(listOf(NO_BLOCKING_MODE), missing(base, none.copy(accessibility = true)))
    }

    @Test fun accessibilityMode() {
        val s = base.copy(blockingMode = BlockingMode.ACCESSIBILITY)
        assertEquals(listOf(ACCESSIBILITY), missing(s))
        assertEquals(emptyList<SetupIssue>(), missing(s, none.copy(accessibility = true)))
    }

    @Test fun usageStatsModeNeedsUsageAccessAndOverlay() {
        val s = base.copy(blockingMode = BlockingMode.USAGE_STATS)
        assertEquals(listOf(USAGE_ACCESS, OVERLAY), missing(s))
        assertEquals(listOf(OVERLAY), missing(s, none.copy(usageAccess = true)))
    }

    @Test fun websiteBlockingAndSettingsGuardNeedAccessibilityInEveryMode() {
        val usage = base.copy(blockingMode = BlockingMode.USAGE_STATS)
        val granted = none.copy(usageAccess = true, overlay = true)
        assertEquals(listOf(ACCESSIBILITY), missing(usage.copy(websiteBlocking = true), granted))
        assertEquals(listOf(ACCESSIBILITY), missing(usage.copy(protections = setOf(Protection.SETTINGS_GUARD)), granted))
    }

    @Test fun batteryOptimizationIsARecommendation() {
        val optimized = all.copy(batteryUnrestricted = false)
        assertEquals(listOf(NO_BLOCKING_MODE, SetupIssue.BATTERY_OPTIMIZATION), missing(base, optimized))
        val issues = missing(base.copy(blockingMode = BlockingMode.ACCESSIBILITY), optimized)
        assertEquals(listOf(SetupIssue.BATTERY_OPTIMIZATION), issues)
        assertEquals(false, issues.single().required)
    }

    @Test fun uninstallProtectionNeedsDeviceAdmin() {
        val s = base.copy(blockingMode = BlockingMode.ACCESSIBILITY, protections = setOf(Protection.UNINSTALL))
        assertEquals(listOf(DEVICE_ADMIN), missing(s, none.copy(accessibility = true)))
        assertEquals(emptyList<SetupIssue>(), missing(s, all))
    }
}
