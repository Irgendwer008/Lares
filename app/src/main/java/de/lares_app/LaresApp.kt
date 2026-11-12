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
import de.lares_app.blocking.UsageMonitorStarter
import de.lares_app.platform.AlertDispatcher
import de.lares_app.platform.ProtectionCheckWorker
import de.lares_app.platform.ProtectionMonitor
import de.lares_app.platform.RecoveryExport
import javax.inject.Inject

@HiltAndroidApp
class LaresApp : Application() {
    @Inject lateinit var usageMonitor: UsageMonitorStarter

    @Inject lateinit var protection: ProtectionMonitor

    @Inject lateinit var alerts: AlertDispatcher

    override fun onCreate() {
        super.onCreate()
        // Safety net: never keep a shared recovery code file around (e.g. if the app was killed).
        RecoveryExport.deleteSharedFile(this)
        // Blocking diagnostics only in debuggable builds (never in releases).
        BlockingLog.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Usage access mode: keep its foreground service running while the mode is chosen.
        usageMonitor.observe()
        // Tamper detection: boot checks, periodic permission check, guardian notifications.
        protection.onProcessStart()
        ProtectionCheckWorker.schedule(this)
        alerts.observe()
    }
}
