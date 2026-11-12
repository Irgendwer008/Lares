/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.protection

/**
 * Recognizes the system pages of Lares itself: in the settings App info (force stop, uninstall,
 * clear storage), its accessibility page, the device-admin page and the special-access pages
 * (usage access, overlay, battery); in the package installer the uninstall dialog for Lares.
 *
 * Works without view ids (Settings pages are partly Compose since Android 14): a page belongs to
 * Lares if its title is "Lares" or "Lares" appears as a non-clickable text (the app header).
 * Lists that merely contain Lares show it in a clickable row and stay usable.
 * Settings apps differ between manufacturers – verify on devices.
 */
object SettingsGuard {
    val settingsPackages = setOf("com.android.settings", "com.miui.securitycenter")
    val installerPackages = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")
    val packages = settingsPackages + installerPackages

    /** A text on the page that equals the app name; [clickable] = it (or a close parent) is tappable (list row). */
    data class LabelMatch(val clickable: Boolean)

    /**
     * @param windowTitle text of the last window change (the page title)
     * @param windowClass class of the last window change (installer: only the uninstall dialog counts,
     *   so updating Lares still works)
     */
    fun isProtectedPage(
        packageName: String,
        windowTitle: String?,
        windowClass: String?,
        matches: List<LabelMatch>,
        appLabel: String,
    ): Boolean = if (windowClass?.endsWith("Dialog") == true && packageName !in installerPackages) {
        // Settings dialogs (e.g. "Don't optimize" for the battery) aren't Lares' pages; the
        // dangerous dialogs (force stop, …) only open from pages that are guarded themselves.
        false
    } else if (packageName in installerPackages) {
        windowClass?.contains("Uninstall", ignoreCase = true) == true &&
            (matches.isNotEmpty() || windowTitle?.contains(appLabel, ignoreCase = true) == true)
    } else {
        windowTitle?.trim().equals(appLabel, ignoreCase = true) || matches.any { !it.clickable }
    }
}
