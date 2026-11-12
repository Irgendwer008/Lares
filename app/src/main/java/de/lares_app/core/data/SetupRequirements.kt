/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

/**
 * Something that keeps blocking / protection from working, shown on the main screen.
 * @property required false = recommendation (works on many devices without it, but not reliably).
 */
enum class SetupIssue(val required: Boolean = true) {
    NO_BLOCKING_MODE,
    ACCESSIBILITY,
    USAGE_ACCESS,
    OVERLAY,
    DEVICE_ADMIN,

    /** Battery optimization may stop the background detection, especially on Xiaomi, Huawei, Samsung … */
    BATTERY_OPTIMIZATION(required = false),
}

/** What the user has currently granted in the system settings. */
data class GrantedPermissions(
    val accessibility: Boolean,
    val usageAccess: Boolean,
    val overlay: Boolean,
    val deviceAdmin: Boolean,
    /** Lares is exempt from battery optimization. */
    val batteryUnrestricted: Boolean,
)

/** Which system permissions the current settings need (see docs/blocking.md). */
object SetupRequirements {
    /**
     * Everything still missing, all at once. While no mode is chosen, the permissions of the
     * recommended Accessibility mode are listed too (it's also what website blocking needs), so the
     * user doesn't discover them one after another.
     */
    fun missing(settings: AppSettings, granted: GrantedPermissions): List<SetupIssue> = buildList {
        if (settings.blockingMode == null) add(SetupIssue.NO_BLOCKING_MODE)
        val mode = settings.blockingMode ?: BlockingMode.ACCESSIBILITY
        val needsAccessibility = mode == BlockingMode.ACCESSIBILITY || settings.websiteBlockingActive ||
            Protection.SETTINGS_GUARD in settings.protections
        if (needsAccessibility && !granted.accessibility) add(SetupIssue.ACCESSIBILITY)
        if (mode == BlockingMode.USAGE_STATS) {
            if (!granted.usageAccess) add(SetupIssue.USAGE_ACCESS)
            if (!granted.overlay) add(SetupIssue.OVERLAY)
        }
        if (Protection.UNINSTALL in settings.protections && !granted.deviceAdmin) add(SetupIssue.DEVICE_ADMIN)
        // Blocking runs in the background in both modes.
        if (!granted.batteryUnrestricted) add(SetupIssue.BATTERY_OPTIMIZATION)
    }
}
