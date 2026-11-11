/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri
import de.lares_app.R
import de.lares_app.blocking.LaresAccessibilityService
import de.lares_app.blocking.LaresDeviceAdminReceiver
import de.lares_app.core.data.GrantedPermissions
import de.lares_app.core.data.SetupIssue

/** Reads the granted system permissions and builds the intents that open the right settings page. */
class PermissionStatus(private val context: Context) {
    private val accessibilityComponent = ComponentName(context, LaresAccessibilityService::class.java)
    private val adminComponent = ComponentName(context, LaresDeviceAdminReceiver::class.java)

    fun current() = GrantedPermissions(
        accessibility = isAccessibilityEnabled(),
        usageAccess = hasUsageAccess(),
        overlay = Settings.canDrawOverlays(context),
        deviceAdmin = context.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == accessibilityComponent }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Intent for the system page that fixes [issue] (null for issues handled inside the app). */
    fun intentFor(issue: SetupIssue): Intent? {
        val pkg = "package:${context.packageName}".toUri()
        return when (issue) {
            SetupIssue.NO_BLOCKING_MODE -> null
            SetupIssue.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                // Understood by the (AOSP-based) Settings app: scrolls to and highlights our entry.
                // Ignored where unsupported – then the plain list opens.
                val key = accessibilityComponent.flattenToString()
                putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
                putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
            }
            SetupIssue.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg)
            SetupIssue.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            SetupIssue.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_explanation))
            // The list of apps (the one-tap dialog needs a permission that Google Play restricts).
            SetupIssue.BATTERY_OPTIMIZATION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    private companion object {
        const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    }

    /** Manufacturer-specific background-killing explanations (dontkillmyapp.com), opened in the browser. */
    fun dontKillMyAppUrl(): String {
        val vendor = Build.MANUFACTURER.lowercase().filter { it.isLetterOrDigit() }
        return if (vendor.isBlank()) "https://dontkillmyapp.com/" else "https://dontkillmyapp.com/$vendor"
    }

    /** Fallback if a device doesn't support the specific page: the app's own settings page. */
    fun appDetailsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
}
