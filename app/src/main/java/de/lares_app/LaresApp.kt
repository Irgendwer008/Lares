/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import de.lares_app.blocking.BlockingLog
import de.lares_app.platform.RecoveryExport

@HiltAndroidApp
class LaresApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Safety net: never keep a shared recovery code file around (e.g. if the app was killed).
        RecoveryExport.deleteSharedFile(this)
        // Blocking diagnostics only in debuggable builds (never in releases).
        BlockingLog.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
