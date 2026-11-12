/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

/**
 * Browsers whose address bar Lares can read (view id of the URL bar, without the package prefix).
 * Browser updates can rename these ids – keep this table in sync after testing on a device.
 * Browsers not listed here are blocked completely while website blocking is on.
 */
data class BrowserAdapter(val packages: List<String>, val urlBarIds: List<String>)

object SupportedBrowsers {
    val all: List<BrowserAdapter> = listOf(
        // Chromium-based
        BrowserAdapter(
            listOf(
                "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
                "com.brave.browser", "com.brave.browser_beta", "com.microsoft.emmx", "com.vivaldi.browser",
                "com.kiwibrowser.browser", "org.cromite.cromite", "org.bromite.bromite",
            ),
            listOf("url_bar"),
        ),
        // Samsung Internet
        BrowserAdapter(listOf("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta"), listOf("location_bar_edit_text")),
        // Firefox-based (Mozilla Android Components)
        BrowserAdapter(
            listOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.fennec_fdroid", "us.spotco.fennec_dos"),
            listOf("mozac_browser_toolbar_url_view"),
        ),
        BrowserAdapter(listOf("com.duckduckgo.mobile.android"), listOf("omnibarTextInput")),
        BrowserAdapter(listOf("com.opera.browser"), listOf("url_field")),
    )

    val packages: Set<String> = all.flatMap { it.packages }.toSet()

    /** Fully qualified view ids of the URL bar for [packageName] (e.g. `com.android.chrome:id/url_bar`). */
    fun urlBarIds(packageName: String): List<String> =
        all.firstOrNull { packageName in it.packages }?.urlBarIds?.map { "$packageName:id/$it" }.orEmpty()
}
