/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import androidx.core.content.edit
import de.lares_app.core.data.AppTheme
import de.lares_app.core.data.Protection
import de.lares_app.core.data.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The few settings that are needed synchronously on the main thread (colors at activity start,
 * the device-admin disable dialog), mirrored from the app state into small preferences – so no
 * blocking read of the state file is needed there. The app state stays the source of truth.
 */
object SettingsCache {
    private const val FILE = "settings_cache"
    private const val THEME = "theme"
    private const val ALERTS = "guardian_alerts"

    /** Keeps the mirror up to date for the lifetime of the process (called from the Application). */
    fun follow(context: Context, store: StateStore) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            store.state.map { it.settings.theme to (Protection.GUARDIAN_ALERTS in it.settings.protections) }
                .distinctUntilChanged()
                .collect { (theme, alerts) -> prefs.edit { putString(THEME, theme.name).putBoolean(ALERTS, alerts) } }
        }
    }

    /**
     * @param fallback read from the app state when the mirror is still empty (only the very first
     *   start after updating from a version without it).
     */
    fun theme(context: Context, fallback: () -> AppTheme = { AppTheme.LARES }): AppTheme =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(THEME, null)
            ?.let { name -> AppTheme.entries.firstOrNull { it.name == name } } ?: fallback()

    fun guardianAlerts(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ALERTS, false)
}
