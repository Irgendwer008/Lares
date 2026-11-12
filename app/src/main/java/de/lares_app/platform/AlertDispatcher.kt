/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.lares_app.core.alerts.AlertChannelRepository
import de.lares_app.core.alerts.AlertSender
import de.lares_app.core.data.Protection
import de.lares_app.core.data.StateStore
import de.lares_app.core.data.TamperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Sends undelivered tamper events to every guardian channel. Each channel remembers up to which
 * event it got everything ([de.lares_app.core.alerts.AlertChannelConfig.deliveredUpTo]), so a
 * failing channel never causes duplicates on the others; several events go out as one message.
 */
class AlertDelivery(
    private val store: StateStore,
    private val tamperLog: TamperLog,
    private val channels: AlertChannelRepository,
    private val sender: AlertSender,
    private val messages: AlertMessages,
) {
    private val mutex = Mutex()

    /** @return true if nothing is left to send (false = retry later). */
    suspend fun deliver(): Boolean = mutex.withLock {
        val pending = tamperLog.undelivered().sortedBy { it.id }
        if (pending.isEmpty()) return true
        val settings = store.current().settings
        if (Protection.GUARDIAN_ALERTS !in settings.protections || settings.alertChannels.isEmpty()) {
            // Recorded while notifications were off: never sent later.
            pending.forEach { tamperLog.markDelivered(it.id) }
            return true
        }
        var allSent = true
        for (channel in settings.alertChannels) {
            val events = pending.filter { it.id > channel.deliveredUpTo }
            if (events.isEmpty()) continue
            val (title, text) = messages.events(channel.language, events)
            // Sending blocks (not cancellable anyway); once sent, the cursor must be saved even if
            // the caller gave up meanwhile – otherwise the next run sends the same message again.
            val sent = withContext(NonCancellable) {
                sender.send(channels.draftOf(channel), title, text).isSuccess.also { ok ->
                    if (ok) {
                        val upTo = events.maxOf { it.id }
                        store.update { s ->
                            s.copy(
                                settings = s.settings.copy(
                                    alertChannels = s.settings.alertChannels.map {
                                        if (it.id == channel.id) it.copy(deliveredUpTo = maxOf(it.deliveredUpTo, upTo)) else it
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            if (!sent) allSent = false
        }
        val everywhere = store.current().settings.alertChannels.minOfOrNull { it.deliveredUpTo } ?: Long.MAX_VALUE
        pending.filter { it.id <= everywhere }.forEach { tamperLog.markDelivered(it.id) }
        allSent
    }
}

/** Starts [AlertWorker] whenever a new tamper event is waiting (WorkManager retries while offline). */
class AlertDispatcher(
    private val context: Context,
    private val store: StateStore,
    private val tamperLog: TamperLog,
    private val delivery: AlertDelivery,
) {
    /** For the lifetime of the process (called from the Application). */
    fun observe() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            val newestPending = tamperLog.events.map { events -> events.filterNot { it.delivered }.maxOfOrNull { it.id } }
            val configured = store.state.map { Protection.GUARDIAN_ALERTS in it.settings.protections && it.settings.alertChannels.isNotEmpty() }
            combine(newestPending, configured) { id, on -> id to on }.distinctUntilChanged().collect { (id, on) ->
                when {
                    id == null -> Unit
                    on -> enqueue()
                    else -> delivery.deliver() // marks them delivered (notifications off)
                }
            }
        }
    }

    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<AlertWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Appended: a run in progress finishes first, then the new events are picked up.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "guardian_alerts"
    }
}

/** Hilt access for WorkManager workers (created by WorkManager, not Hilt). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerEntryPoint {
    fun alertDelivery(): AlertDelivery

    fun protectionMonitor(): ProtectionMonitor
}

class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val delivery = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java).alertDelivery()
        return if (delivery.deliver()) Result.success() else Result.retry()
    }
}
