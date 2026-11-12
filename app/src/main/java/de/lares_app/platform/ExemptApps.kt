/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri

/**
 * Apps that are never blocked, so nobody locks themselves out of something essential: Lares, the
 * home screen, phone / emergency calls, keyboards, system UI, the Settings app as a whole (the
 * settings guard protects its sensitive pages instead), and system permission / install dialogs.
 * Device-specific, so it's computed from what is installed.
 */
class ExemptApps(private val context: Context) {

    fun packages(): Set<String> = buildSet {
        add(context.packageName)
        addAll(FIXED)
        val pm = context.packageManager
        // Only system apps for these roles – any app can declare such an activity (a browser
        // declaring itself a home screen must not become unblockable). Plus the chosen defaults.
        fun systemHandlers(intent: Intent) = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { it.activityInfo.packageName }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        addAll(systemHandlers(home))
        pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" }?.let(::add) // the current home screen ("android" = chooser)
        addAll(systemHandlers(Intent(Intent.ACTION_DIAL, "tel:".toUri())))
        addAll(systemHandlers(Intent(Settings.ACTION_SETTINGS)))
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        // Enabled keyboards only (an installed but unused "keyboard" could be anything).
        context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { add(it.packageName) }
    }

    private companion object {
        val FIXED = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
    }
}
