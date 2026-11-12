/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import de.lares_app.R
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.policy.SupportedBrowsers
import de.lares_app.core.protection.SettingsGuard
import de.lares_app.platform.ProtectionMonitor
import javax.inject.Inject

/**
 * Detects the app in the foreground (window state changes), for supported browsers the URL in the
 * address bar and, for the settings guard, title and app header of system-settings pages;
 * [BlockingController] decides. Nothing else of the window content is read, and nothing is stored.
 */
@AndroidEntryPoint
class LaresAccessibilityService : AccessibilityService(), BlockingHost {

    @Inject lateinit var controller: BlockingController

    @Inject lateinit var monitor: ProtectionMonitor

    private val main = Handler(Looper.getMainLooper())
    private var timer: TimerOverlay? = null

    private val events = SystemEvents(
        context = this,
        onScreen = { controller.onScreen(it) },
        onPackagesChanged = {
            controller.refreshExemptApps()
            refreshKeyboards()
            activityCache.clear()
        },
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        timer = TimerOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        refreshKeyboards()
        events.register()
        // Serves Accessibility mode; in Usage access mode it only reports browser URLs.
        controller.attach(BlockingMode.ACCESSIBILITY, this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        // Supported browsers: (re-)read the address bar when their content changes – bundled.
        if (pkg in SupportedBrowsers.packages && controller.readsUrls) scheduleUrlRead(pkg)
        // Settings guard: Lares' own pages in the system settings.
        if (pkg in SettingsGuard.packages && controller.guardsSettings()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) settingsTitle = event.text.joinToString(" ")
            scheduleSettingsCheck(pkg)
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Keyboards, the notification shade, popups and dialogs of other apps also send window
        // changes; only a real app screen (activity) changes the app in use.
        if (pkg in TRANSIENT || pkg in keyboards || !isActivity(pkg, event.className?.toString())) {
            BlockingLog.d { "ignored window change: $pkg / ${event.className}" }
            return
        }
        controller.onForeground(BlockingMode.ACCESSIBILITY, pkg)
    }

    private var pendingUrlRead: String? = null
    private var pendingSettingsCheck = false

    /** Title of the last settings window (page title, e.g. "Lares" on its accessibility page). */
    private var settingsTitle: String? = null

    private fun scheduleSettingsCheck(pkg: String) {
        if (pendingSettingsCheck) return
        pendingSettingsCheck = true
        main.postDelayed({
            pendingSettingsCheck = false
            checkSettingsPage(pkg)
        }, URL_READ_DELAY_MILLIS)
    }

    /** Reads only the page title and the app header of the settings window. */
    private fun checkSettingsPage(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val headers = SettingsGuard.headerIds.flatMap { id -> root.findAccessibilityNodeInfosByViewId(id) }.mapNotNull { it.text?.toString() }
        if (SettingsGuard.isProtectedPage(settingsTitle, headers, getString(R.string.app_name))) {
            BlockingLog.d { "protected settings page: $pkg / $settingsTitle / $headers" }
            controller.onProtectedSettings(pkg)
        }
    }

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

    /** Turned off in the settings: check right away whether that's tampering. */
    override fun onUnbind(intent: Intent?): Boolean {
        monitor.checkPermissions(delayMillis = UNBIND_CHECK_DELAY_MILLIS)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        controller.detach(BlockingMode.ACCESSIBILITY)
        events.unregister()
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

        /** The system updates the list of enabled services around the unbind. */
        const val UNBIND_CHECK_DELAY_MILLIS = 2_000L
    }
}
