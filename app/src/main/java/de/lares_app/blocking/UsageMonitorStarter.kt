/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.content.Context
import de.lares_app.core.data.AppState
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Runs [UsageMonitorService] exactly while Usage access mode is chosen and setup is complete. */
@OptIn(FlowPreview::class)
class UsageMonitorStarter(private val context: Context, private val store: StateStore) {
    @Volatile private var shouldRun = false

    /** Follows the settings for the lifetime of the process (called from the Application). */
    fun observe() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            // Debounced: starting and stopping in quick succession can crash a foreground service
            // that was stopped before it called startForeground.
            store.state.map(::needsService).distinctUntilChanged().debounce(START_DEBOUNCE_MILLIS).collect { run ->
                shouldRun = run
                if (run) UsageMonitorService.start(context) else UsageMonitorService.stop(context)
            }
        }
    }

    /** Lares is in the foreground: starting is always allowed now (retry after a refused background start). */
    fun ensureRunning() {
        if (shouldRun) UsageMonitorService.start(context)
    }

    private fun needsService(state: AppState) =
        state.settings.blockingMode == BlockingMode.USAGE_STATS && state.settings.setupComplete && state.unlock.isSetUp

    private companion object {
        const val START_DEBOUNCE_MILLIS = 500L
    }
}
