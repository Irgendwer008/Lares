/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import de.lares_app.R
import de.lares_app.platform.AlertDelivery
import de.lares_app.platform.AlertDispatcher
import de.lares_app.platform.ProtectionMonitor
import de.lares_app.platform.SettingsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Device administrator for the "prevent uninstalling" protection: while active, Android refuses to
 * uninstall Lares. Uses no admin policies. Deactivating it is reported to the guardian right away
 * (uninstalling may follow immediately, so it's sent now and queued as a fallback).
 */
@AndroidEntryPoint
class LaresDeviceAdminReceiver : DeviceAdminReceiver() {
    @Inject lateinit var monitor: ProtectionMonitor

    @Inject lateinit var delivery: AlertDelivery

    @Inject lateinit var dispatcher: AlertDispatcher


    /** Shown in the system's confirmation dialog. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        // From the small settings mirror – no blocking read of the state on the main thread.
        return if (SettingsCache.guardianAlerts(context)) context.getString(R.string.device_admin_disable_warning) else null
    }

    override fun onDisabled(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                monitor.onDeviceAdminDisabled()
                dispatcher.enqueue() // fallback first: survives even if this process ends now
                withTimeoutOrNull(SEND_TIMEOUT_MILLIS) { delivery.deliver() } // try right away
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** A broadcast receiver has about 10 s. */
        const val SEND_TIMEOUT_MILLIS = 8_000L
    }
}
