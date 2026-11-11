/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import de.lares_app.R
import java.util.Locale

/** Guardian message texts in the channel's language (independent of the app language). */
class AlertMessages(private val context: Context) {

    private fun localized(language: String): Context {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(config)
    }

    /** @return title and text of the test message. */
    fun test(language: String): Pair<String, String> {
        val c = localized(language)
        return c.getString(R.string.alert_test_title) to c.getString(R.string.alert_test_body, Build.MODEL ?: "Android")
    }
}
