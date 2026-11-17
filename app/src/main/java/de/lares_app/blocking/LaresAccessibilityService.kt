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
import android.os.PowerManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        onShutdown = { monitor.onShutdown() },
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        timer = TimerOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        refreshKeyboards()
        events.register()
        // Serves Accessibility mode; in Usage access mode it only reports browser URLs.
        controller.attach(BlockingMode.ACCESSIBILITY, this)
        controller.onScreen(getSystemService(PowerManager::class.java).isInteractive)
        // The app already in front sends no window event: report it once the state has loaded.
        main.postDelayed({ rootInActiveWindow?.packageName?.toString()?.let { seedForeground(it) } }, SEED_DELAY_MILLIS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Window list changes (PiP, split screen) come without a package – handled before the
        // package check below, which would otherwise drop them.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleWindowsCheck()
            return
        }
        val pkg = event.packageName?.toString() ?: return
        // Supported browsers: (re-)read the address bar when their content changes – bundled.
        if (pkg in SupportedBrowsers.packages && controller.readsUrls) scheduleUrlRead(pkg)
        // Settings guard: Lares' own pages in the system settings.
        if (pkg in SettingsGuard.packages) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                settingsTitle = event.text.joinToString(" ")
                settingsClass = event.className?.toString()
                BlockingLog.d { "settings window: $pkg / $settingsClass / \"$settingsTitle\" – guard ${controller.settingsGuardStatus()}" }
            }
            if (controller.guardsSettings()) scheduleSettingsCheck(pkg)
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        scheduleWindowsCheck() // a new screen can also change what else is visible
        // Keyboards, the notification shade, popups and dialogs of other apps also send window
        // changes; only a real app screen (activity) changes the app in use.
        if (pkg in TRANSIENT || pkg in keyboards || !isActivity(pkg, event.className?.toString())) {
            BlockingLog.d { "ignored window change: $pkg / ${event.className}" }
            return
        }
        controller.onForeground(BlockingMode.ACCESSIBILITY, pkg)
    }

    private fun seedForeground(pkg: String) {
        if (pkg in TRANSIENT || pkg in keyboards) return
        controller.onForeground(BlockingMode.ACCESSIBILITY, pkg)
    }

    private var pendingWindowsCheck = false

    /** The set of windows changed (PiP, split screen, …): report the apps that are visible – bundled. */
    private fun scheduleWindowsCheck() {
        if (pendingWindowsCheck) return
        pendingWindowsCheck = true
        main.postDelayed({
            pendingWindowsCheck = false
            // Only the package of each app window (nothing of its content).
            val all = windows
            // Notification shade, quick settings, … in front: the apps below are reported as not
            // visible, although they are just covered for a moment – keep the last known apps.
            if (all.any { it.root?.packageName?.toString() in TRANSIENT && (it.isActive || it.isFocused) }) {
                BlockingLog.d { "windows: system window in front – visible apps unchanged" }
                main.postDelayed({ scheduleWindowsCheck() }, WINDOWS_RECHECK_MILLIS) // look again once it's gone
                return@postDelayed
            }
            val appWindows = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            BlockingLog.d {
                "windows: " + appWindows.joinToString { w ->
                    "${w.root?.packageName ?: "?"} (pip=${w.isInPictureInPictureMode})"
                }
            }
            val apps = appWindows.mapNotNull { it.root?.packageName?.toString() }
                .filterNot { it in TRANSIENT || it in keyboards }
                .toSet()
            controller.onVisibleApps(apps)
        }, URL_READ_DELAY_MILLIS)
    }

    private var pendingUrlRead: String? = null
    private var pendingSettingsCheck = false

    /** Title and class of the last settings / installer window (e.g. "Lares" on its accessibility page). */
    private var settingsTitle: String? = null
    private var settingsClass: String? = null

    private fun scheduleSettingsCheck(pkg: String) {
        if (pendingSettingsCheck) return
        pendingSettingsCheck = true
        main.postDelayed({
            pendingSettingsCheck = false
            checkSettingsPage(pkg)
        }, URL_READ_DELAY_MILLIS)
    }

    /** Looks only for texts equal to "Lares" on the settings / installer window (and the page title). */
    private fun checkSettingsPage(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val label = getString(R.string.app_name)
        val matches = findExact(root, label).map { SettingsGuard.LabelMatch(clickable = it.isClickableNearby()) }
        val protected = SettingsGuard.isProtectedPage(pkg, settingsTitle, settingsClass, matches, label)
        BlockingLog.d { "settings check: $pkg / \"$settingsTitle\" / $matches → protected=$protected" }
        if (protected) {
            controller.onProtectedSettings(pkg)
            // Checked again shortly: a quick switch back within the block debounce sends no new
            // events on a still page. Ends by itself once Lares' block screen is in front.
            main.postDelayed({ if (controller.guardsSettings()) checkSettingsPage(pkg) }, RECHECK_MILLIS)
        }
    }

    /**
     * Nodes whose text or description equals [label]. Walks the tree itself: the system search
     * (`findAccessibilityNodeInfosByText`) doesn't find texts in Compose screens, and parts of
     * the settings are Compose since Android 14. Bounded, and only texts equal to [label] are kept.
     */
    private fun findExact(root: AccessibilityNodeInfo, label: String): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SETTINGS_NODES) {
            val node = queue.removeFirst()
            visited++
            if (listOfNotNull(node.text, node.contentDescription).any { it.toString().trim().equals(label, ignoreCase = true) }) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return found
    }

    /** List rows are clickable as a whole: the node itself or one of its closest parents. */
    private fun AccessibilityNodeInfo.isClickableNearby(): Boolean {
        var node: AccessibilityNodeInfo? = this
        repeat(CLICKABLE_PARENT_DEPTH) {
            val n = node ?: return false
            if (n.isClickable) return true
            node = n.parent
        }
        return false
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

    override fun bringToFront(packageName: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        main.post { runCatching { startActivity(launch) } }
        return true
    }

    override fun goHome(delayMillis: Long) {
        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, delayMillis)
    }

    override fun showTimers(items: List<TimerItem>) {
        main.post { timer?.show(items) }
    }

    private companion object {
        val TRANSIENT = setOf("com.android.systemui")
        const val URL_READ_DELAY_MILLIS = 300L
        const val CLICKABLE_PARENT_DEPTH = 4
        const val WINDOWS_RECHECK_MILLIS = 1_000L
        const val SEED_DELAY_MILLIS = 1_500L
        const val RECHECK_MILLIS = 2_200L
        const val MAX_SETTINGS_NODES = 600

        /** The system updates the list of enabled services around the unbind. */
        const val UNBIND_CHECK_DELAY_MILLIS = 2_000L
    }
}
