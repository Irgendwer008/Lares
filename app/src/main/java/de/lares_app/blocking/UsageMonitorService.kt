/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import de.lares_app.MainActivity
import de.lares_app.R
import de.lares_app.core.blocking.ForegroundTracker
import de.lares_app.core.data.BlockingMode
import de.lares_app.platform.ProtectionMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Usage access mode: foreground service that polls `UsageStatsManager` once per second (screen on
 * only) for the app in the foreground and hosts the block screen / countdown for
 * [BlockingController]. Started and stopped by [UsageMonitorStarter].
 */
@AndroidEntryPoint
class UsageMonitorService : Service(), BlockingHost {

    @Inject lateinit var controller: BlockingController

    @Inject lateinit var monitor: ProtectionMonitor

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var timer: TimerOverlay? = null

    @Volatile private var screenOn = true

    /** False if starting in the foreground was refused (then nothing else was set up). */
    private var started = true

    private val events = SystemEvents(
        context = this,
        onScreen = {
            screenOn = it
            controller.onScreen(it)
        },
        onPackagesChanged = { controller.refreshExemptApps() },
        onShutdown = { monitor.onShutdown() },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: RuntimeException) {
            // Not allowed right now (e.g. sticky restart from the background on Android 12+):
            // don't crash the process (it also runs the Accessibility service); started again
            // when Lares is opened.
            BlockingLog.d { "usage monitor: startForeground refused: $e" }
            started = false
            stopSelf()
            return
        }
        timer = TimerOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        events.register()
        controller.attach(BlockingMode.USAGE_STATS, this)
        controller.onScreen(screenOn)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch { poll() }
    }

    /** Restarted by the system with a null intent after it was killed (START_STICKY). */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (!started) return super.onDestroy()
        scope?.cancel()
        scope = null
        controller.detach(BlockingMode.USAGE_STATS)
        events.unregister()
        timer?.hide()
        timer = null
        super.onDestroy()
    }

    /** Follows `ACTIVITY_RESUMED` events via [ForegroundTracker]. */
    private suspend fun poll() {
        val usage = getSystemService(UsageStatsManager::class.java)
        val event = UsageEvents.Event()
        val tracker = ForegroundTracker()
        while (scope?.isActive == true) {
            if (screenOn) {
                val now = System.currentTimeMillis()
                // Empty without usage access – then nothing is detected until it's granted.
                val result = runCatching { usage.queryEvents(tracker.queryStart(now), now) }.getOrNull()
                val resumed = buildList {
                    while (result != null && result.hasNextEvent()) {
                        result.getNextEvent(event)
                        if (event.eventType == RESUMED) add(event.packageName to event.timeStamp)
                    }
                }
                // Reported every time (cheap): picks up a mode switch without a new event.
                tracker.onResumed(resumed, now)?.let { controller.onForeground(BlockingMode.USAGE_STATS, it) }
            }
            delay(POLL_MILLIS)
        }
    }

    private fun notification() = run {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel_blocking), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.blocking_service_title))
            .setContentText(getString(R.string.blocking_service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ---- BlockingHost (called from the controller's thread) -----------------------------------
    // Starting activities from the background is allowed because Lares may draw over other apps.

    override fun showBlockScreen(info: BlockInfo) {
        main.post { runCatching { startActivity(BlockActivity.intent(this, info)) } }
    }

    override fun goHome(delayMillis: Long) {
        main.postDelayed({
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, delayMillis)
    }

    override fun showTimer(remainingMillis: Long?) {
        main.post { if (remainingMillis == null) timer?.hide() else timer?.show(remainingMillis) }
    }

    companion object {
        private const val CHANNEL = "blocking_service"
        private const val NOTIFICATION_ID = 2

        /** `ACTIVITY_RESUMED` (API 29+), the same value as the older `MOVE_TO_FOREGROUND`. */
        private const val RESUMED = 1
        private const val POLL_MILLIS = 1_000L

        fun start(context: Context) {
            // Not allowed from the background on Android 12+ without an exemption (battery
            // optimization off, overlay permission …) – then it's started the next time Lares is opened.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, UsageMonitorService::class.java)) }
                .onFailure { BlockingLog.d { "usage monitor not started: $it" } }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }
}
