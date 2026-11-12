/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.protection

/**
 * Recognizes the system-settings pages of Lares itself: App info (force stop, uninstall, clear
 * storage), its accessibility page, the device-admin page and the special-access pages (usage
 * access, overlay, battery). All of them show "Lares" as page title or app header; lists that
 * merely contain Lares (e.g. the accessibility list) don't.
 * Settings apps differ between manufacturers – verify on devices and extend the ids.
 */
object SettingsGuard {
    val packages = setOf("com.android.settings", "com.miui.securitycenter")

    /** App header of app-specific pages (AOSP and close derivatives). */
    val headerIds = listOf(
        "com.android.settings:id/entity_header_title",
        "com.android.settings:id/app_detail_title",
        "com.android.settings:id/admin_name",
    )

    /**
     * @param windowTitle text of the last window change (the page title)
     * @param headerTexts texts of the nodes with [headerIds]
     */
    fun isProtectedPage(windowTitle: String?, headerTexts: List<String>, appLabel: String): Boolean =
        windowTitle?.trim().equals(appLabel, ignoreCase = true) ||
            headerTexts.any { it.trim().equals(appLabel, ignoreCase = true) }
}
