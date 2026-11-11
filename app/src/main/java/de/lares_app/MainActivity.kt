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
import de.lares_app.ui.LaresRoot
import de.lares_app.ui.theme.LaresTheme

/** AppCompatActivity (not ComponentActivity) so per-app language works back to API 26. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No password manager / autofill anywhere in Lares: the guardian's code must never be
        // offered for saving on the phone owner's device. Dialogs have their own window and
        // call DisableAutofill() themselves.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent {
            LaresTheme {
                LaresRoot()
            }
        }
    }
}
