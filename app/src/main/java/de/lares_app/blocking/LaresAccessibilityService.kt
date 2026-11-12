/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import de.lares_app.core.policy.SupportedBrowsers
import javax.inject.Inject

/**
 * Detects the app in the foreground (window state changes) and, for supported browsers, the URL in
 * the address bar; [BlockingController] decides. Nothing else of the window content is read, and
 * nothing is stored.
 */
@AndroidEntryPoint
class LaresAccessibilityService : AccessibilityService(), BlockingHost {

    @Inject lateinit var controller: BlockingController

    private val main = Handler(Looper.getMainLooper())
    private var timer: TimerOverlay? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> controller.onScreen(false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> controller.onScreen(true)
                else -> { // packages changed
                    controller.refreshExemptApps()
                    refreshKeyboards()
                    activityCache.clear()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        timer = TimerOverlay(this)
        refreshKeyboards()
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED)
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, receiver, packages, ContextCompat.RECEIVER_NOT_EXPORTED)
        controller.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        // Supported browsers: (re-)read the address bar when their content changes – bundled.
        if (pkg in SupportedBrowsers.packages && controller.readsUrls) scheduleUrlRead(pkg)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Keyboards, the notification shade, popups and dialogs of other apps also send window
        // changes; only a real app screen (activity) changes the app in use.
        if (pkg in TRANSIENT || pkg in keyboards || !isActivity(pkg, event.className?.toString())) {
            BlockingLog.d { "ignored window change: $pkg / ${event.className}" }
            return
        }
        controller.onForeground(pkg)
    }

    private var pendingUrlRead: String? = null

    /** Reads the URL bar at most every [URL_READ_DELAY_MILLIS] (content changes fire very often). */
    private fun scheduleUrlRead(pkg: String) {
        if (pendingUrlRead == pkg) return
        pendingUrlRead = pkg
        main.postDelayed({
            pendingUrlRead = null
            readUrl(pkg)
        }, URL_READ_DELAY_MILLIS)
    }

    /**
     * Reads only the browser's address bar (nothing else of the window). While the user is typing
     * in it (focused), nothing is reported – only committed URLs count.
     */
    private fun readUrl(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val node = SupportedBrowsers.urlBarIds(pkg).firstNotNullOfOrNull { id -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull() }
            ?: return
        if (node.isFocused) return
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) controller.onUrl(pkg, text)
    }

    private val activityCache = HashMap<ComponentName, Boolean>()
    private var keyboards: Set<String> = emptySet()

    /**
     * True if [className] is an activity of [pkg]. If the package isn't visible to Lares (package
     * visibility rules), it can't be checked – then the change is accepted.
     */
    private fun isActivity(pkg: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        val component = ComponentName(pkg, className)
        return activityCache.getOrPut(component) {
            try {
                packageManager.getActivityInfo(component, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                // Visible package but no such activity → a dialog / popup / view; invisible package → accept.
                runCatching { packageManager.getPackageInfo(pkg, 0) }.isFailure
            }
        }
    }

    private fun refreshKeyboards() {
        keyboards = getSystemService(InputMethodManager::class.java)?.inputMethodList?.map { it.packageName }?.toSet().orEmpty()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        controller.stop()
        runCatching { unregisterReceiver(receiver) }
        timer?.hide()
        timer = null
        super.onDestroy()
    }

    // ---- BlockingHost (called from the controller's thread) -----------------------------------

    override fun showBlockScreen(info: BlockInfo) {
        // Shown directly over the blocked app / browser (a home or back action first would race with it).
        main.post { startActivity(BlockActivity.intent(this, info)) }
    }

    override fun goHome(delayMillis: Long) {
        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, delayMillis)
    }

    override fun showTimer(remainingMillis: Long?) {
        main.post { if (remainingMillis == null) timer?.hide() else timer?.show(remainingMillis) }
    }

    private companion object {
        val TRANSIENT = setOf("com.android.systemui")
        const val URL_READ_DELAY_MILLIS = 300L
    }
}
