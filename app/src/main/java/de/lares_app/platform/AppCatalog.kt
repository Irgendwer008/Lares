/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import de.lares_app.core.policy.RuleTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * Lists launchable apps. Uses a `<queries>` entry for MAIN/LAUNCHER instead of the restricted
 * QUERY_ALL_PACKAGES permission (see docs/distribution.md).
 */
class AppCatalog(private val context: Context) {
    private val cache = ConcurrentHashMap<String, InstalledApp>()

    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { describe(it) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Label and icon of [packageName]. If it isn't installed: a known name from the templates
     * (e.g. "TikTok"), otherwise the package name; no icon (the UI shows a letter icon).
     */
    suspend fun describeOrFallback(packageName: String): InstalledApp = withContext(Dispatchers.IO) {
        describe(packageName) ?: InstalledApp(packageName, RuleTemplates.knownAppNames[packageName] ?: packageName, null)
    }

    private fun describe(packageName: String): InstalledApp? {
        cache[packageName]?.let { return it }
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            val icon = runCatching { pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
            InstalledApp(packageName, pm.getApplicationLabel(info).toString(), icon).also { cache[packageName] = it }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private companion object {
        const val ICON_PX = 96
    }
}
