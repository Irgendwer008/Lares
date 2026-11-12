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
import de.lares_app.core.data.TamperEvent
import de.lares_app.core.data.TamperEventType
import java.text.DateFormat
import java.util.Date
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

    /** @return title and text of a message about [events] (oldest first). */
    fun events(language: String, events: List<TamperEvent>): Pair<String, String> {
        val c = localized(language)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag(language))
        val lines = events.map { "${describe(c, it)} (${time.format(Date(it.timestampMillis))})" }
        val title = if (events.size == 1) {
            c.getString(R.string.alert_event_title, describe(c, events.single()))
        } else {
            c.resources.getQuantityString(R.plurals.alert_events_title, events.size, events.size)
        }
        val body = (if (events.size == 1) lines else lines.map { "• $it" }).joinToString("\n")
        return title to body + "\n\n" + c.getString(R.string.alert_footer, Build.MODEL ?: "Android")
    }

    private fun describe(c: Context, event: TamperEvent): String {
        val details = event.details.orEmpty().split(';').mapNotNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        fun number(key: String) = details[key]?.toLongOrNull()
        fun plural(id: Int, n: Long) = c.resources.getQuantityString(id, n.toInt(), n)
        return when (event.type) {
            TamperEventType.DEVICE_ADMIN_DISABLED -> c.getString(R.string.alert_event_device_admin)
            TamperEventType.ACCESSIBILITY_DISABLED -> c.getString(R.string.alert_event_accessibility)
            TamperEventType.PERMISSION_REVOKED -> when (details["permission"]) {
                "USAGE_ACCESS" -> c.getString(R.string.alert_event_usage_access)
                "OVERLAY" -> c.getString(R.string.alert_event_overlay)
                else -> c.getString(R.string.alert_event_permission)
            }
            TamperEventType.PROTECTION_GAP -> number("minutes")?.let { plural(R.plurals.alert_event_stopped, it) }
                ?: c.getString(R.string.alert_event_boots)
            TamperEventType.CLOCK_CHANGED -> number("minutes")?.let {
                if (it >= 0) plural(R.plurals.alert_event_clock_forward, it) else plural(R.plurals.alert_event_clock_back, -it)
            } ?: c.getString(R.string.alert_event_clock)
            TamperEventType.TIMEZONE_CHANGED ->
                c.getString(R.string.alert_event_timezone, details["from"].orEmpty(), details["to"].orEmpty())
            TamperEventType.REPEATED_WRONG_CODE -> plural(R.plurals.alert_event_wrong_code, number("failures") ?: 3)
            TamperEventType.RECOVERY_CODE_USED -> c.getString(R.string.alert_event_recovery)
        }
    }
}
