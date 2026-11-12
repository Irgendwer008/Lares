/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app

import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import de.lares_app.blocking.BlockingController
import de.lares_app.blocking.UsageMonitorStarter
import de.lares_app.platform.ProtectionMonitor
import de.lares_app.ui.LaresRoot
import de.lares_app.core.data.StateStore
import de.lares_app.ui.theme.LaresTheme
import de.lares_app.ui.theme.initialTheme
import javax.inject.Inject

/** AppCompatActivity (not ComponentActivity) so per-app language works back to API 26. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var usageMonitor: UsageMonitorStarter

    @Inject lateinit var protection: ProtectionMonitor

    @Inject lateinit var blocking: BlockingController

    @Inject lateinit var store: StateStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No password manager / autofill anywhere in Lares: the guardian's code must never be
        // offered for saving on the phone owner's device. Dialogs have their own window and
        // call DisableAutofill() themselves.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val theme = initialTheme(store)
        setContent {
            LaresTheme(store, theme) {
                LaresRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Usage access mode: a background start may have been refused – starting works while visible.
        usageMonitor.ensureRunning()
        protection.checkPermissions()
        // Back in Lares: a setup issue being fixed no longer opens the settings guard.
        blocking.endSettingsGrace()
    }
}
