/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import de.lares_app.core.blocking.LimitWarnings
import de.lares_app.core.data.AppState
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.GroupStore
import de.lares_app.core.data.RuleStore
import de.lares_app.core.data.StateStore
import de.lares_app.core.policy.BlockReason
import de.lares_app.core.policy.Decision
import de.lares_app.core.policy.PolicyEngine
import de.lares_app.core.policy.PolicySnapshot
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.time.Clock
import de.lares_app.platform.AppCatalog
import de.lares_app.platform.ExemptApps
import de.lares_app.platform.LimitNotifier
import de.lares_app.platform.UsageRepository
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
)

/** Effects the controller asks its host (the accessibility service) to perform. */
interface BlockingHost {
    fun showBlockScreen(info: BlockInfo)

    /** Countdown overlay: [remainingMillis] = null hides it. */
    fun showTimer(remainingMillis: Long?)
}

/**
 * Applies the rules to the app in the foreground: counts time once per second, shows the block
 * screen, the countdown overlay during the last 5 minutes and the optional warnings.
 * Driven by [onForeground] / [onScreen] events from the accessibility service.
 */
class BlockingController(
    private val ruleStore: RuleStore,
    private val groupStore: GroupStore,
    private val store: StateStore,
    private val usage: UsageRepository,
    private val exemptApps: ExemptApps,
    private val catalog: AppCatalog,
    private val notifier: LimitNotifier,
    private val clock: Clock,
) {
    private var scope: CoroutineScope? = null
    private var host: BlockingHost? = null

    @Volatile private var rules: List<Rule> = emptyList()

    @Volatile private var groups: Map<Long, RuleGroup> = emptyMap()

    @Volatile private var state: AppState = AppState()

    @Volatile private var exempt: Set<String> = emptySet()

    @Volatile private var foreground: String? = null

    @Volatile private var screenOn = true

    private var lastTickElapsed = 0L
    private var lastBlocked: Pair<String, Long>? = null
    private val warnings = LimitWarnings()
    private var ticker: Job? = null

    fun start(host: BlockingHost) {
        stop()
        this.host = host
        // One thread: counters, warnings and the block debounce aren't touched concurrently.
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        scope = s
        exempt = exemptApps.packages()
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

    fun stop() {
        scope?.let { s ->
            s.cancel()
            // Save the last seconds without blocking the caller (service teardown on the main thread).
            CoroutineScope(Dispatchers.IO).launch { usage.flush() }
        }
        scope = null
        ticker = null
        host?.showTimer(null)
        host = null
    }

    fun onForeground(packageName: String) {
        if (packageName == foreground) return
        BlockingLog.d { "foreground: $packageName" }
        foreground = packageName
        scope?.launch { evaluate(countMillis = 0) }
    }

    fun onScreen(on: Boolean) {
        screenOn = on
        if (!on) host?.showTimer(null)
        lastTickElapsed = clock.elapsedMillis()
    }

    /** Installed apps changed (new launcher, keyboard …). */
    fun refreshExemptApps() {
        exempt = exemptApps.packages()
    }

    private suspend fun tick() {
        val now = clock.elapsedMillis()
        // Cap the step so a stalled timer (device asleep) doesn't count minutes at once.
        val step = (now - lastTickElapsed).coerceIn(0, MAX_STEP_MILLIS)
        lastTickElapsed = now
        if (usage.refreshPeriod()) warnings.reset()
        evaluate(countMillis = if (screenOn) step else 0)
    }

    private suspend fun evaluate(countMillis: Long) {
        val pkg = foreground ?: return
        val st = state
        val active = st.settings.setupComplete && st.unlock.isSetUp && st.settings.blockingMode == BlockingMode.ACCESSIBILITY
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
            // Website blocking (URL bar) follows in the next step; browsers aren't evaluated yet.
            websiteBlockingEnabled = false,
            exemptPackages = exempt,
        )
        val decision = PolicyEngine.evaluate(pkg, null, policy)
        if (countMillis == 0L || decision is Decision.Block) BlockingLog.d { "$pkg → $decision" }
        when (decision) {
            is Decision.Block -> {
                host?.showTimer(null)
                block(pkg, decision, snapshot.periodEndMillis)
            }
            is Decision.Allow -> {
                if (countMillis > 0) usage.add(decision, countMillis)
                val remaining = decision.remainingMillis?.minus(countMillis)
                host?.showTimer(remaining?.takeIf { st.settings.limitTimer && it in 1..LimitWarnings.TIMER_THRESHOLD })
                if (remaining != null) warn(pkg, decision, remaining)
            }
        }
    }

    private suspend fun block(pkg: String, decision: Decision.Block, periodEndMillis: Long) {
        // The home action takes a moment; don't relaunch the block screen for the same app meanwhile.
        val now = clock.elapsedMillis()
        lastBlocked?.let { (p, at) -> if (p == pkg && now - at < BLOCK_DEBOUNCE_MILLIS) return }
        lastBlocked = pkg to now
        host?.showBlockScreen(
            BlockInfo(
                packageName = pkg,
                label = catalog.describeOrFallback(pkg).label,
                reason = decision.reason,
                groupName = decision.groupId?.let { groups[it]?.name },
                resetAtMillis = periodEndMillis,
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
    }
}
