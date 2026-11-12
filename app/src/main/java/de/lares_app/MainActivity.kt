/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        handleIntent(intent)
        setContent {
            LaresTheme {
                LaresRoot(openUnlock = openUnlock, onUnlockOpened = { openUnlock = false })
            }
        }
    }

    /** "Enter guardian code" on the block screen opens the code dialog on the main screen. */
    private var openUnlock by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_UNLOCK, false) == true) {
            openUnlock = true
            intent.removeExtra(EXTRA_OPEN_UNLOCK)
        }
    }

    companion object {
        const val EXTRA_OPEN_UNLOCK = "openUnlock"
    }
}
