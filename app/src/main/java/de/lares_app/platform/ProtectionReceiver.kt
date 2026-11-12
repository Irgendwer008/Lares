/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Boot, app update, clock and time-zone changes. Receiving the broadcast starts the process,
 * which restarts the blocking (Usage access mode, see `UsageMonitorStarter`) and runs the boot
 * checks ([ProtectionMonitor.onProcessStart] in `LaresApp`); the Accessibility service is restarted
 * by the system itself.
 */
@AndroidEntryPoint
class ProtectionReceiver : BroadcastReceiver() {
    @Inject lateinit var monitor: ProtectionMonitor

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED -> monitor.onTimeChanged()
            Intent.ACTION_TIMEZONE_CHANGED -> monitor.onTimeZoneChanged()
            // BOOT_COMPLETED, MY_PACKAGE_REPLACED: starting the process was the point.
        }
    }
}
