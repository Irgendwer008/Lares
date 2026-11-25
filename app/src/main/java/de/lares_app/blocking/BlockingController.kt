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
import de.lares_app.core.data.ScheduleStore
import de.lares_app.core.data.StateStore
import de.lares_app.core.policy.BlockReason
import de.lares_app.core.policy.Decision
import de.lares_app.core.policy.PolicyEngine
import de.lares_app.core.policy.PolicySnapshot
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.Schedule
import de.lares_app.core.policy.SupportedBrowsers
import de.lares_app.core.policy.effective
import de.lares_app.core.protection.SettingsGuard
import de.lares_app.core.protection.tamperReportable
import de.lares_app.core.time.Clock
import de.lares_app.core.unlock.UnlockManager
import de.lares_app.platform.AppCatalog
import de.lares_app.platform.ExemptApps
import de.lares_app.platform.InstalledBrowsers
import de.lares_app.platform.LimitNotifier
import de.lares_app.platform.ProtectionMonitor
import de.lares_app.platform.UsageRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    /** Blocked by a schedule ([resetAtMillis] = when it's free again, 0 if unknown). */
    val schedule: Boolean = false,
)

/**
 * One countdown pill: the app whose limit is running out (for a website the browser) – its icon
 * is shown in front of the time.
 */
data class TimerItem(val packageName: String, val remainingMillis: Long)

/**
 * Effects the controller asks its host to perform: the accessibility service (Accessibility mode)
 * or [UsageMonitorService] (Usage access mode).
 */
interface BlockingHost {
    fun showBlockScreen(info: BlockInfo)

    /** Countdown pills, one per limit running out (stacked downward); empty = none. */
    fun showTimers(items: List<TimerItem>)

    /** Go to the home screen after [delayMillis] (lets another app's activity start first). */
    fun goHome(delayMillis: Long)

    /**
     * Brings [packageName] back to the front (out of picture-in-picture / the other half of a split
     * screen), so the normal block screen can cover it. @return false if that's not possible.
     */
    fun bringToFront(packageName: String): Boolean = false
}

/**
 * Neutral page the website block screen opens in the browser, so the blocked page is no longer the
 * open tab. Static page on GitHub Pages (`docs/blocked/index.html`): no scripts, no external resources.
 */
const val SAFE_PAGE_URL = "https://irgendwer008.github.io/Lares/blocked/"

/**
 * Applies the rules to the app in the foreground: counts time once per second, shows the block
 * screen, the countdown overlay during the last 5 minutes and the optional warnings.
 *
 * Hosts [attach] themselves for the mode they serve. The foreground app comes from the host of the
 * chosen blocking mode, which also performs the effects. Website blocking (browser URLs from the
 * accessibility service) exists only in Accessibility mode.
 */
class BlockingController(
    private val ruleStore: RuleStore,
    private val groupStore: GroupStore,
    private val scheduleStore: ScheduleStore,
    private val store: StateStore,
    private val usage: UsageRepository,
    private val exemptApps: ExemptApps,
    private val browsers: InstalledBrowsers,
    private val catalog: AppCatalog,
    private val notifier: LimitNotifier,
    private val monitor: ProtectionMonitor,
    private val unlockManager: UnlockManager,
    private val clock: Clock,
) {
    private var scope: CoroutineScope? = null
    private val hosts = ConcurrentHashMap<BlockingMode, BlockingHost>()

    /**
     * One thread for everything the loop touches (counters, warnings, debounce) – shared by every
     * start, so an old loop still finishing after a quick detach / attach can't run in parallel.
     */
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Last foreground app per source, so a mode switch starts with the right app. */
    private val lastForeground = ConcurrentHashMap<BlockingMode, String>()

    /** Host that showed the countdown last – hidden when the mode (and thus the host) changes. */
    private var timerHost: BlockingHost? = null

    @Volatile private var rules: List<Rule> = emptyList()

    @Volatile private var groups: Map<Long, RuleGroup> = emptyMap()

    @Volatile private var schedules: Map<Long, Schedule> = emptyMap()

    @Volatile private var state: AppState = AppState()

    @Volatile private var exempt: Set<String> = emptySet()

    @Volatile private var installedBrowsers: Set<String> = emptySet()

    /** Last committed address-bar URL of the foreground browser (and which browser it belongs to). */
    @Volatile private var url: Pair<String, String>? = null

    @Volatile private var foreground: String? = null

    /** All apps with a visible window (picture-in-picture, split screen) – Accessibility mode only. */
    @Volatile private var visibleApps: Set<String> = emptySet()
    private val lastBroughtToFront = HashMap<String, Long>()
    private val bringToFrontTries = HashMap<String, Int>()
    private val ownPackage: String = exemptApps.ownPackage

    /** URL of the last blocked page (browser, URL), and an ignored URL while the neutral page loads. */
    @Volatile private var blockedUrl: Pair<String, String>? = null

    @Volatile private var ignoredUrl: Pair<Pair<String, String>, Long>? = null

    /** A URL that arrived while it was ignored – applied once the grace is over (if still shown). */
    @Volatile private var deferredUrl: Pair<String, String>? = null

    @Volatile private var screenOn = true

    /** Protection of the chosen mode running (for the gap check / heartbeat). */
    private var protecting = false
    private var ticks = 0L

    private var lastTickElapsed = 0L
    private var lastBlocked: Pair<String, Long>? = null
    private val warnings = LimitWarnings()

    /** [host] runs (service connected) and serves [mode]. */
    @Synchronized
    fun attach(mode: BlockingMode, host: BlockingHost) {
        hosts[mode] = host
        if (scope == null) start()
    }

    /** The host of [mode] stopped. */
    @Synchronized
    fun detach(mode: BlockingMode) {
        hosts.remove(mode)?.showTimers(emptyList())
        if (hosts.isEmpty()) stop()
    }

    /** Host of the chosen blocking mode, if it's running. */
    private val activeHost: BlockingHost?
        get() = state.settings.blockingMode?.let { hosts[it] }

    private fun start() {
        // One thread: counters, warnings and the block debounce aren't touched concurrently.
        val s = CoroutineScope(SupervisorJob() + dispatcher)
        scope = s
        exempt = exemptApps.packages()
        installedBrowsers = browsers.packages()
        s.launch {
            // Schedules in the same combine: nothing is evaluated before they're loaded (a rule
            // with an unknown schedule would be blocked).
            combine(ruleStore.rules, groupStore.groups, scheduleStore.schedules, store.state) { r, g, sc, st -> Loaded(r, g, sc, st) }.collect { (r, g, sc, st) ->
                rules = r
                groups = g.associateBy { it.id }
                schedules = sc.associateBy { it.id }
                val modeChanged = st.settings.blockingMode != state.settings.blockingMode
                state = st
                if (modeChanged) {
                    // The other source's app is stale: use what the new mode's source saw last.
                    foreground = st.settings.blockingMode?.let { lastForeground[it] }
                    url = null
                }
                evaluate(countMillis = 0) // rules / pause may have changed
            }
        }
        lastTickElapsed = clock.elapsedMillis()
        s.launch {
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
        timerHost = null
        foreground = null
        visibleApps = emptySet()
        protecting = false
    }

    /** [source] saw [packageName] in the foreground; only the source of the chosen mode counts. */
    fun onForeground(source: BlockingMode, packageName: String) {
        lastForeground[source] = packageName
        if (source != state.settings.blockingMode || packageName == foreground) return
        BlockingLog.d { "foreground: $packageName" }
        foreground = packageName
        // The window list belongs to the previous screen until the service re-reads it (a moment
        // later) – otherwise e.g. the app just blocked would count as "visible beside" its own block
        // screen and be brought back over it.
        visibleApps = emptySet()
        if (url?.first != packageName) url = null
        scope?.launch { evaluate(countMillis = 0) }
    }

    /** Apps with a visible window right now (from the accessibility service's window list). */
    fun onVisibleApps(packages: Set<String>) {
        if (packages == visibleApps) return
        BlockingLog.d { "visible apps: $packages" }
        visibleApps = packages
        bringToFrontTries.keys.retainAll(packages) // apps that went away start counting again
        scope?.launch { evaluate(countMillis = 0) }
    }

    fun onScreen(on: Boolean) {
        screenOn = on
        // The rest on the loop's thread (it owns these fields).
        scope?.launch {
            if (!on) timerHost?.showTimers(emptyList())
            lastTickElapsed = clock.elapsedMillis()
            if (protecting) monitor.beat()
        }
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
            if (clock.elapsedMillis() > until) {
                ignoredUrl = null
            } else if (ignored == browserPackage to newUrl) {
                deferredUrl = ignored // checked again when the grace is over
                return
            }
        }
        deferredUrl = null
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
        if (protecting && screenOn && ticks % HEARTBEAT_TICKS == 0L) monitor.beat()
        // The neutral page had its time to load: if the blocked page is still shown, apply it now.
        ignoredUrl?.let { (_, until) ->
            if (clock.elapsedMillis() > until) {
                ignoredUrl = null
                deferredUrl?.let { (pkg, u) -> deferredUrl = null; url = pkg to u }
            }
        }
        if (ticks % HEARTBEAT_TICKS == 0L && state.unlock.lockout != null) unlockManager.clearExpiredLockout()
        val now = clock.elapsedMillis()
        // Cap the step so a stalled timer (device asleep) doesn't count minutes at once.
        val step = (now - lastTickElapsed).coerceIn(0, MAX_STEP_MILLIS)
        lastTickElapsed = now
        if (usage.refreshPeriod()) warnings.reset().forEach(notifier::cancel)
        evaluate(countMillis = if (screenOn) step else 0)
    }

    /** The host of the chosen mode appeared (start, mode switch): check for a gap, then beat. */
    private fun updateProtecting() {
        val now = activeHost != null && state.settings.setupComplete && state.unlock.isSetUp
        if (now && !protecting) monitor.onProtectionStarted()
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
    private var graceCheckedAt: Long? = null
    private var graceStillNeeded = true

    /** The settings came to the front since the grace started (only then can they be "left"). */
    private var graceSawSettings = false

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
        graceCheckedAt = null
        graceStillNeeded = true
        graceSawSettings = false
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
        val fg = foreground
        if (fg != null && fg in SettingsGuard.packages) graceSawSettings = true
        graceFixedAt?.let { fixedAt ->
            // Foreground still Lares (the settings didn't come up yet, or the source is slow) isn't "left".
            val leftSettings = graceSawSettings && fg != null && fg !in SettingsGuard.packages
            if (leftSettings || now - fixedAt >= FIXED_TAIL_MILLIS) {
                endSettingsGrace()
                return false
            }
            return true
        }
        val checkedAt = graceCheckedAt
        if (checkedAt == null || now - checkedAt >= GRACE_RECHECK_MILLIS) {
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
            // The block screen shows its own title for settings pages (no label needed).
            host.showBlockScreen(BlockInfo(packageName, "", BlockReason.PROTECTED_SETTINGS, null, 0L))
        }
    }

    private suspend fun evaluate(countMillis: Long) {
        val st = state
        val host = activeHost
        if (timerHost !== host) {
            timerHost?.showTimers(emptyList()) // mode switched: the old host's countdown goes away
            timerHost = host
        }
        val pkg = foreground ?: return
        val active = st.settings.setupComplete && st.unlock.isSetUp && host != null
        if (!active || !screenOn) {
            host?.showTimers(emptyList())
            return
        }
        val snapshot = usage.usage.value ?: run { usage.refreshPeriod(); usage.usage.value } ?: return
        val zone = ZoneId.systemDefault()
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
            schedules = schedules,
            now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.wallMillis()), zone),
            periodEnd = LocalDateTime.ofInstant(Instant.ofEpochMilli(snapshot.periodEndMillis), zone),
            windowUsedMillis = snapshot.windowMillis,
            zone = zone,
        )
        val currentUrl = url?.takeIf { it.first == pkg }?.second
        val decision = PolicyEngine.evaluate(pkg, currentUrl, policy)
        if (countMillis == 0L || decision is Decision.Block) BlockingLog.d { "$pkg → $decision" }
        // Other visible apps (picture-in-picture, the other half of a split screen): blocked ones
        // are brought back to the front (then the normal block screen covers them), allowed ones
        // count their time too – a video in a PiP window uses up the limit like a full-screen one.
        // Lares itself in front (block screen, or Lares next to another app in split screen):
        // nothing is pulled forward over it.
        val others = if (st.settings.blockingMode == BlockingMode.ACCESSIBILITY && pkg != ownPackage) visibleApps - pkg else emptySet()
        val otherAllowed = mutableListOf<Pair<String, Decision.Allow>>()
        for (other in others) {
            // A browser half in split screen keeps its address bar URL (website rules apply there too).
            val otherUrl = url?.takeIf { it.first == other }?.second
            when (val d = PolicyEngine.evaluate(other, otherUrl, policy)) {
                is Decision.Block -> bringToFront(host, other, d, snapshot.periodEndMillis)
                is Decision.Allow -> otherAllowed += other to d
            }
        }
        if (countMillis > 0 && otherAllowed.isNotEmpty()) {
            // One add for everything visible, so a group shown twice (e.g. app + PiP) counts once.
            val counted = (otherAllowed.map { it.second } + listOfNotNull(decision as? Decision.Allow))
            usage.add(
                Decision.Allow(
                    countedRuleIds = counted.flatMap { it.countedRuleIds }.toSet(),
                    countedGroupIds = counted.flatMap { it.countedGroupIds }.toSet(),
                    countedWindowKeys = counted.flatMap { it.countedWindowKeys }.toSet(),
                ),
                countMillis,
            )
        }
        // Countdowns: the foreground app first, then the other visible apps – one pill per limit
        // (a group visible twice, e.g. app + PiP, gets one pill).
        val timers = mutableListOf<TimerItem>()
        val timerSubjects = mutableSetOf<String>()
        val warnedSubjects = mutableSetOf<String>()
        suspend fun countdown(app: String, d: Decision.Allow) {
            val remaining = d.remainingMillis?.minus(countMillis) ?: return
            warnedSubjects += LimitWarnings.subjectOf(d)
            warn(app, d, remaining)
            if (st.settings.limitTimer && remaining in 1..LimitWarnings.TIMER_THRESHOLD && timerSubjects.add(LimitWarnings.subjectOf(d))) {
                timers += TimerItem(app, remaining)
            }
        }
        when (decision) {
            is Decision.Block -> block(host, pkg, decision, snapshot.periodEndMillis)
            is Decision.Allow -> {
                if (countMillis > 0 && otherAllowed.isEmpty()) usage.add(decision, countMillis)
                countdown(pkg, decision)
            }
        }
        for ((app, d) in otherAllowed) countdown(app, d)
        host.showTimers(timers)
        // Limits not evaluated any more with time left (app closed, pause started, subject changed):
        // their warning goes away.
        warnings.removeAllExcept(warnedSubjects).forEach(notifier::cancel)
    }

    /** A blocked app is visible next to the foreground app: bring it to the front (debounced). */
    private suspend fun bringToFront(host: BlockingHost, pkg: String, decision: Decision.Block, periodEndMillis: Long) {
        val now = clock.elapsedMillis()
        if ((lastBroughtToFront[pkg] ?: Long.MIN_VALUE / 2) + BLOCK_DEBOUNCE_MILLIS > now) return
        lastBroughtToFront[pkg] = now
        // Still visible after several tries (e.g. its PiP lives in its own task) or no launch intent:
        // show the block screen directly instead of retrying forever.
        val tries = (bringToFrontTries[pkg] ?: 0) + 1
        bringToFrontTries[pkg] = tries
        BlockingLog.d { "blocked app visible beside the foreground app: $pkg (try $tries)" }
        if (tries > MAX_BRING_TO_FRONT_TRIES || !host.bringToFront(pkg)) block(host, pkg, decision, periodEndMillis)
    }

    private suspend fun block(host: BlockingHost, pkg: String, decision: Decision.Block, periodEndMillis: Long) {
        // The home action takes a moment; don't relaunch the block screen for the same app meanwhile.
        val now = clock.elapsedMillis()
        lastBlocked?.let { (p, at) -> if (p == pkg && now - at < BLOCK_DEBOUNCE_MILLIS) return }
        lastBlocked = pkg to now
        val websiteRule = rules.firstOrNull { it.id == decision.ruleId }?.takeIf { it.type == RuleType.WEBSITE }
        // The rule as enforced (in a group: the group's schedule).
        val fromSchedule = decision.reason == BlockReason.SCHEDULE ||
            rules.firstOrNull { it.id == decision.ruleId }?.effective(groups)?.scheduleId != null
        blockedUrl = if (websiteRule != null) url else null
        host.showBlockScreen(
            BlockInfo(
                packageName = pkg,
                label = websiteRule?.target ?: catalog.describeOrFallback(pkg).label,
                reason = decision.reason,
                groupName = decision.groupId?.let { groups[it]?.name },
                // A schedule says when it's free again; otherwise the daily limits restart.
                resetAtMillis = decision.until?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                    ?: if (fromSchedule) 0L else periodEndMillis,
                website = websiteRule != null,
                schedule = fromSchedule,
            ),
        )
    }

    private suspend fun warn(pkg: String, decision: Decision.Allow, remainingMillis: Long) {
        val settings = state.settings
        val thresholds = buildList {
            if (settings.warnFiveMinutes) add(LimitWarnings.FIVE_MINUTES)
            if (settings.warnOneMinute) add(LimitWarnings.ONE_MINUTE)
        }
        val subject = LimitWarnings.subjectOf(decision)
        // Warnings switched off: still evaluated, so a shown one is removed.
        when (val change = warnings.update(subject, remainingMillis, thresholds)) {
            is LimitWarnings.Change.Show -> {
                // What the limit is about: the website (not the browser), the group, or the app.
                val owners = decision.restrictedOwners
                val label = rules.firstOrNull { "r${it.id}" in owners && it.type == RuleType.WEBSITE }?.target
                    ?: owners.firstNotNullOfOrNull { o -> o.takeIf { it.startsWith("g") }?.drop(1)?.toLongOrNull()?.let { groups[it]?.name } }
                    ?: catalog.describeOrFallback(pkg).label
                notifier.warn(subject, label, (change.thresholdMillis / 60_000L).toInt())
            }
            LimitWarnings.Change.Remove -> notifier.cancel(subject)
            null -> Unit
        }
    }

    private data class Loaded(val rules: List<Rule>, val groups: List<RuleGroup>, val schedules: List<Schedule>, val state: AppState)

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val MAX_STEP_MILLIS = 2_000L
        const val BLOCK_DEBOUNCE_MILLIS = 2_000L
        const val SAFE_PAGE_GRACE_MILLIS = 5_000L
        const val HOME_DELAY_MILLIS = 700L
        const val HEARTBEAT_TICKS = 60L
        const val PERMISSION_CHECK_TICKS = 30L
        const val SETTINGS_GRACE_MILLIS = 2 * 60_000L
        const val MAX_BRING_TO_FRONT_TRIES = 3
        const val GRACE_RECHECK_MILLIS = 1_000L
        const val FIXED_TAIL_MILLIS = 30_000L
    }
}
