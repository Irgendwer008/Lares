/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Apps that open arbitrary web links (browsers). A neutral example address only matches apps with
 * a generic web filter, not apps that just handle their own domain (e.g. a YouTube app).
 * Visible via the `VIEW https` entry in the manifest's `<queries>`.
 */
class InstalledBrowsers(private val context: Context) {
    fun packages(): Set<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()), 0)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .toSet()
}
