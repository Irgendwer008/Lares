/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedBrowsersTest {

    @Test fun fullyQualifiedUrlBarIds() {
        assertEquals(listOf("com.android.chrome:id/url_bar"), SupportedBrowsers.urlBarIds("com.android.chrome"))
        assertEquals(listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"), SupportedBrowsers.urlBarIds("org.mozilla.firefox"))
        assertEquals(emptyList<String>(), SupportedBrowsers.urlBarIds("org.example.browser"))
    }

    @Test fun everyPackageIsListedOnce() {
        val all = SupportedBrowsers.all.flatMap { it.packages }
        assertEquals(all.size, all.toSet().size)
        assertTrue(SupportedBrowsers.all.all { it.urlBarIds.isNotEmpty() })
    }
}
