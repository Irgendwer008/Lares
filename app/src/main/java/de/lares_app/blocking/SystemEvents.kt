/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Screen on / off, shutdown and installed-app changes, for the blocking services. */
class SystemEvents(
    private val context: Context,
    private val onScreen: (on: Boolean) -> Unit,
    private val onPackagesChanged: () -> Unit,
    private val onShutdown: () -> Unit = {},
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreen(false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreen(true)
                Intent.ACTION_SHUTDOWN -> onShutdown()
                else -> onPackagesChanged()
            }
        }
    }

    fun register() {
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(context, receiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED)
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED) // enabled / disabled
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, packages, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
