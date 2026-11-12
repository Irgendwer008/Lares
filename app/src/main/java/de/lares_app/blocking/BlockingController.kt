/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import de.lares_app.core.blocking.LimitWarnings
import de.lares_app.core.data.AppSettings
import de.lares_app.core.data.AppState
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.Protection
import de.lares_app.core.data.GroupStore
import de.lares_app.core.data.RuleStore
import de.lares_app.core.data.StateStore
import de.lares_app.core.policy.BlockReason
import de.lares_app.core.policy.Decision
import de.lares_app.core.policy.PolicyEngine
import de.lares_app.core.policy.PolicySnapshot
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.SupportedBrowsers
import de.lares_app.core.protection.SettingsGuard
import de.lares_app.core.protection.tamperReportable
import de.lares_app.core.time.Clock
import de.lares_app.platform.AppCatalog
import de.lares_app.platform.ExemptApps
import de.lares_app.platform.InstalledBrowsers
import de.lares_app.platform.LimitNotifier
import de.lares_app.platform.ProtectionMonitor
import de.lares_app.platform.UsageRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the block screen shows. */
data class BlockInfo(
    val packageName: String,
    val label: String,
    val reason: BlockReason,
    /** Group whose limit / switch applies, if any. */
    val groupName: String?,
    /** When the daily limits restart (wall clock), for "limit reached". */
    val resetAtMillis: Long,
    /** A website rule (not the browser app) was hit: the block screen offers to open the neutral page. */
    val website: Boolean = false,
)

/**
 * Effects the controller asks its host to perform: the accessibility service (Accessibility mode)
 * or [UsageMonitorService] (Usage access mode).
 */
interface BlockingHost {
    fun showBlockScreen(info: BlockInfo)

    /** Countdown overlay: [remainingMillis] = null hides it. */
    fun showTimer(remainingMillis: Long?)

    /** Go to the home screen after [delayMillis] (lets another app's activity start first). */
    fun goHome(delayMillis: Long)
}

/**
 * Applies the rules to the app in the foreground: counts time once per second, shows the block
 * screen, the countdown overlay during the last 5 minutes and the optional warnings.
 *
 * Hosts [attach] themselves for the mode they serve. The foreground app comes from the host of the
 * chosen blocking mode, which also performs the effects. Website blocking (browser URLs from the
 * accessibility service) exists only in Accessibility mode.
 */
/**
 * Neutral page the website block screen opens in the browser, so the blocked page is no longer the
 * open tab. Placeholder until Lares has its own page.
 */
const val SAFE_PAGE_URL = "https://www.google.com/"

class BlockingController(
    private val ruleStore: RuleStore,
    private val groupStore: GroupStore,
    private val store: StateStore,
    private val usage: UsageRepository,
    private val exemptApps: ExemptApps,
    private val browsers: InstalledBrowsers,
    private val catalog: AppCatalog,
    private val notifier: LimitNotifier,
    private val monitor: ProtectionMonitor,
    private val clock: Clock,
) {
    private var scope: CoroutineScope? = null
    private val hosts = ConcurrentHashMap<BlockingMode, BlockingHost>()

    /** Host that showed the countdown last – hidden when the mode (and thus the host) changes. */
    private var timerHost: BlockingHost? = null

    @Volatile private var rules: List<Rule> = emptyList()

    @Volatile private var groups: Map<Long, RuleGroup> = emptyMap()

    @Volatile private var state: AppState = AppState()

    @Volatile private var exempt: Set<String> = emptySet()

    @Volatile private var installedBrowsers: Set<String> = emptySet()

    /** Last committed address-bar URL of the foreground browser (and which browser it belongs to). */
    @Volatile private var url: Pair<String, String>? = null

    @Volatile private var foreground: String? = null

    /** URL of the last blocked page (browser, URL), and an ignored URL while the neutral page loads. */
    @Volatile private var blockedUrl: Pair<String, String>? = null

    @Volatile private var ignoredUrl: Pair<Pair<String, String>, Long>? = null

    @Volatile private var screenOn = true

    /** Protection of the chosen mode running (for the gap check / heartbeat). */
    private var protecting = false
    private var ticks = 0L

    private var lastTickElapsed = 0L
    private var lastBlocked: Pair<String, Long>? = null
    private val warnings = LimitWarnings()
    private var ticker: Job? = null

    /** [host] runs (service connected) and serves [mode]. */
    @Synchronized
    fun attach(mode: BlockingMode, host: BlockingHost) {
        hosts[mode] = host
        if (scope == null) start()
    }

    /** The host of [mode] stopped. */
    @Synchronized
    fun detach(mode: BlockingMode) {
        hosts.remove(mode)?.showTimer(null)
        if (hosts.isEmpty()) stop()
    }

    /** Host of the chosen blocking mode, if it's running. */
    private val activeHost: BlockingHost?
        get() = state.settings.blockingMode?.let { hosts[it] }

    private fun start() {
        // One thread: counters, warnings and the block debounce aren't touched concurrently.
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        scope = s
        exempt = exemptApps.packages()
        installedBrowsers = browsers.packages()
        s.launch {
            combine(ruleStore.rules, groupStore.groups, store.state) { r, g, st -> Triple(r, g, st) }.collect { (r, g, st) ->
                rules = r
                groups = g.associateBy { it.id }
                state = st
                evaluate(countMillis = 0) // rules / pause may have changed
            }
        }
        lastTickElapsed = clock.elapsedMillis()
        ticker = s.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                tick()
            }
        }
    }

    private fun stop() {
        scope?.let { s ->
            s.cancel()
            // Save the last seconds without blocking the caller (service teardown on the main thread).
            CoroutineScope(Dispatchers.IO).launch { usage.flush() }
        }
        scope = null
        ticker = null
        timerHost = null
        foreground = null
        protecting = false
    }

    /** [source] saw [packageName] in the foreground; only the source of the chosen mode counts. */
    fun onForeground(source: BlockingMode, packageName: String) {
        if (source != state.settings.blockingMode || packageName == foreground) return
        BlockingLog.d { "foreground: $packageName" }
        foreground = packageName
        if (url?.first != packageName) url = null
        scope?.launch { evaluate(countMillis = 0) }
    }

    fun onScreen(on: Boolean) {
        screenOn = on
        if (!on) timerHost?.showTimer(null)
        lastTickElapsed = clock.elapsedMillis()
        if (protecting) monitor.beat(on)
    }

    /** Installed apps changed (new launcher, keyboard, browser …). */
    fun refreshExemptApps() {
        exempt = exemptApps.packages()
        installedBrowsers = browsers.packages()
    }

    /** Address bars are only read while website blocking is on (Accessibility mode only). */
    val readsUrls: Boolean
        get() = state.settings.let { it.websiteBlockingActive && it.setupComplete && it.blockingMode == BlockingMode.ACCESSIBILITY }

    /** The address bar of a supported browser shows [newUrl] (only committed URLs, not while typing). */
    fun onUrl(browserPackage: String, newUrl: String) {
        if (url == browserPackage to newUrl) return
        ignoredUrl?.let { (ignored, until) ->
            if (clock.elapsedMillis() > until) ignoredUrl = null else if (ignored == browserPackage to newUrl) return
        }
        BlockingLog.d { "url: $browserPackage → $newUrl" }
        url = browserPackage to newUrl
        scope?.launch { evaluate(countMillis = 0) }
    }

    /**
     * The block screen opened [SAFE_PAGE_URL] in [browserPackage]: until it loaded, the address bar
     * may still show the blocked page – don't block that again for a few seconds.
     * [thenHome]: "Close browser" – go home once the browser took the link.
     */
    fun onSafePageOpened(browserPackage: String, thenHome: Boolean) {
        blockedUrl?.takeIf { it.first == browserPackage }?.let { ignoredUrl = it to clock.elapsedMillis() + SAFE_PAGE_GRACE_MILLIS }
        if (thenHome) activeHost?.goHome(HOME_DELAY_MILLIS)
    }

    private suspend fun tick() {
        ticks++
        updateProtecting()
        if (ticks % PERMISSION_CHECK_TICKS == 0L) monitor.checkPermissions()
        if (protecting && screenOn && ticks % HEARTBEAT_TICKS == 0L) monitor.beat(true)
        val now = clock.elapsedMillis()
        // Cap the step so a stalled timer (device asleep) doesn't count minutes at once.
        val step = (now - lastTickElapsed).coerceIn(0, MAX_STEP_MILLIS)
        lastTickElapsed = now
        if (usage.refreshPeriod()) warnings.reset()
        evaluate(countMillis = if (screenOn) step else 0)
    }

    /** The host of the chosen mode appeared (start, mode switch): check for a gap, then beat. */
    private fun updateProtecting() {
        val now = activeHost != null && state.settings.setupComplete && state.unlock.isSetUp
        if (now && !protecting) monitor.onProtectionStarted(screenOn)
        protecting = now
    }

    /**
     * The settings guard is on and applies now: locked (unlocked mode allows everything) and no
     * setup issue is being fixed right now ([allowSettingsWhile]).
     */
    fun guardsSettings(): Boolean {
        val st = state
        return Protection.SETTINGS_GUARD in st.settings.protections && st.tamperReportable(clock) && !settingsGraceActive()
    }

    /** For the diagnostics log: why the guard does or doesn't apply. */
    fun settingsGuardStatus(): String {
        val st = state
        return when {
            Protection.SETTINGS_GUARD !in st.settings.protections -> "off (setting)"
            !st.settings.setupComplete || !st.unlock.isSetUp -> "off (setup not complete)"
            !st.tamperReportable(clock) -> "off (unlocked mode)"
            settingsGraceActive() -> if (graceFixedAt != null) "off (setup issue fixed, until leaving the settings)" else
                "off (fixing a setup issue, max. ${(settingsAllowedUntil - clock.elapsedMillis()) / 1000} s)"
            else -> "active"
        }
    }

    @Volatile private var settingsAllowedUntil = 0L

    @Volatile private var settingsGraceCheck: ((AppSettings) -> Boolean)? = null
    private var graceCheckedAt = Long.MIN_VALUE
    private var graceStillNeeded = false

    /** When the issue got fixed – the user is usually still on that page, so leaving it stays possible. */
    private var graceFixedAt: Long? = null

    /**
     * Lares opens a system settings page to fix a setup issue: the guard lets the settings through
     * while [stillMissing] (the issue isn't fixed yet), at most [SETTINGS_GRACE_MILLIS], and only
     * until Lares is opened again ([endSettingsGrace]). Otherwise the guard couldn't be passed
     * to grant the permission it protects. Once fixed, the user is still on that (protected) page:
     * it stays open until they leave the settings, at most [FIXED_TAIL_MILLIS].
     */
    fun allowSettingsWhile(stillMissing: (AppSettings) -> Boolean) {
        settingsGraceCheck = stillMissing
        graceCheckedAt = Long.MIN_VALUE
        graceFixedAt = null
        settingsAllowedUntil = clock.elapsedMillis() + SETTINGS_GRACE_MILLIS
    }

    fun endSettingsGrace() {
        settingsGraceCheck = null
        settingsAllowedUntil = 0L
        graceFixedAt = null
    }

    /** Checked often (every settings event) – the permission check is cached for a second. */
    @Synchronized
    private fun settingsGraceActive(): Boolean {
        val check = settingsGraceCheck ?: return false
        val now = clock.elapsedMillis()
        if (now >= settingsAllowedUntil) {
            endSettingsGrace()
            return false
        }
        graceFixedAt?.let { fixedAt ->
            val fg = foreground
            val leftSettings = fg != null && fg !in SettingsGuard.packages
            if (leftSettings || now - fixedAt >= FIXED_TAIL_MILLIS) {
                endSettingsGrace()
                return false
            }
            return true
        }
        if (now - graceCheckedAt >= GRACE_RECHECK_MILLIS) {
            graceStillNeeded = check(state.settings)
            graceCheckedAt = now
        }
        if (!graceStillNeeded) graceFixedAt = now // fixed: guard again once the user leaves the page
        return true
    }

    /** The accessibility service saw one of Lares' own settings pages. */
    fun onProtectedSettings(packageName: String) {
        scope?.launch {
            val host = hosts[BlockingMode.ACCESSIBILITY] ?: return@launch
            val now = clock.elapsedMillis()
            lastBlocked?.let { (p, at) -> if (p == packageName && now - at < BLOCK_DEBOUNCE_MILLIS) return@launch }
            lastBlocked = packageName to now
            host.showBlockScreen(BlockInfo(packageName, catalog.describeOrFallback(packageName).label, BlockReason.PROTECTED_SETTINGS, null, 0L))
        }
    }

    private suspend fun evaluate(countMillis: Long) {
        val st = state
        val host = activeHost
        if (timerHost !== host) {
            timerHost?.showTimer(null) // mode switched: the old host's countdown goes away
            timerHost = host
        }
        val pkg = foreground ?: return
        val active = st.settings.setupComplete && st.unlock.isSetUp && host != null
        if (!active || !screenOn) {
            host?.showTimer(null)
            return
        }
        val snapshot = usage.usage.value ?: run { usage.refreshPeriod(); usage.usage.value } ?: return
        val policy = PolicySnapshot(
            rules = rules,
            usedMillis = snapshot.ruleMillis,
            groups = groups,
            groupUsedMillis = snapshot.groupMillis,
            pauseActive = st.unlock.pause?.isActive(clock) == true,
            websiteBlockingEnabled = st.settings.websiteBlockingActive,
            supportedBrowsers = SupportedBrowsers.packages,
            installedBrowsers = installedBrowsers,
            exemptPackages = exempt,
        )
        val currentUrl = url?.takeIf { it.first == pkg }?.second
        val decision = PolicyEngine.evaluate(pkg, currentUrl, policy)
        if (countMillis == 0L || decision is Decision.Block) BlockingLog.d { "$pkg → $decision" }
        when (decision) {
            is Decision.Block -> {
                host.showTimer(null)
                block(host, pkg, decision, snapshot.periodEndMillis)
            }
            is Decision.Allow -> {
                if (countMillis > 0) usage.add(decision, countMillis)
                val remaining = decision.remainingMillis?.minus(countMillis)
                host.showTimer(remaining?.takeIf { st.settings.limitTimer && it in 1..LimitWarnings.TIMER_THRESHOLD })
                if (remaining != null) warn(pkg, decision, remaining)
            }
        }
    }

    private suspend fun block(host: BlockingHost, pkg: String, decision: Decision.Block, periodEndMillis: Long) {
        // The home action takes a moment; don't relaunch the block screen for the same app meanwhile.
        val now = clock.elapsedMillis()
        lastBlocked?.let { (p, at) -> if (p == pkg && now - at < BLOCK_DEBOUNCE_MILLIS) return }
        lastBlocked = pkg to now
        val websiteRule = rules.firstOrNull { it.id == decision.ruleId }?.takeIf { it.type == RuleType.WEBSITE }
        blockedUrl = if (websiteRule != null) url else null
        host.showBlockScreen(
            BlockInfo(
                packageName = pkg,
                label = websiteRule?.target ?: catalog.describeOrFallback(pkg).label,
                reason = decision.reason,
                groupName = decision.groupId?.let { groups[it]?.name },
                resetAtMillis = periodEndMillis,
                website = websiteRule != null,
            ),
        )
    }

    private suspend fun warn(pkg: String, decision: Decision.Allow, remainingMillis: Long) {
        val settings = state.settings
        val thresholds = buildList {
            if (settings.warnFiveMinutes) add(LimitWarnings.FIVE_MINUTES)
            if (settings.warnOneMinute) add(LimitWarnings.ONE_MINUTE)
        }
        if (thresholds.isEmpty()) return
        val crossed = warnings.crossed(LimitWarnings.subjectOf(decision), remainingMillis, thresholds)
        crossed.minOrNull()?.let { t -> notifier.warn(catalog.describeOrFallback(pkg).label, (t / 60_000L).toInt()) }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val MAX_STEP_MILLIS = 2_000L
        const val BLOCK_DEBOUNCE_MILLIS = 2_000L
        const val SAFE_PAGE_GRACE_MILLIS = 5_000L
        const val HOME_DELAY_MILLIS = 700L
        const val HEARTBEAT_TICKS = 60L
        const val PERMISSION_CHECK_TICKS = 30L
        const val SETTINGS_GRACE_MILLIS = 2 * 60_000L
        const val GRACE_RECHECK_MILLIS = 1_000L
        const val FIXED_TAIL_MILLIS = 30_000L
    }
}
