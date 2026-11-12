/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import android.content.Intent
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
        fun handlers(intent: Intent) = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
        addAll(handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        addAll(handlers(Intent(Intent.ACTION_DIAL, "tel:".toUri())))
        addAll(handlers(Intent(Settings.ACTION_SETTINGS)))
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList?.forEach { add(it.packageName) }
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
