/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/** Everything the engine needs to know about the current state. Built by the Android layer. */
data class PolicySnapshot(
    val rules: List<Rule>,
    /** Foreground time already used in the current usage period, per standalone rule id. */
    val usedMillis: Map<Long, Long> = emptyMap(),
    val groups: Map<Long, RuleGroup> = emptyMap(),
    /** Summed foreground time of all members in the current usage period, per group id. */
    val groupUsedMillis: Map<Long, Long> = emptyMap(),
    val pauseActive: Boolean = false,
    val websiteBlockingEnabled: Boolean = false,
    /** Browsers whose URL bar Lares can read. */
    val supportedBrowsers: Set<String> = emptySet(),
    /** All installed apps that handle `ACTION_VIEW https://…`. */
    val installedBrowsers: Set<String> = emptySet(),
    /** Packages that are never blocked (Lares itself, phone/emergency, launcher …). */
    val exemptPackages: Set<String> = emptySet(),
    val schedules: Map<Long, Schedule> = emptyMap(),
    /** Local time now – needed for schedules (without it, rules with a schedule are blocked). */
    val now: LocalDateTime? = null,
    /** Local end of the current usage period (when the "outside" budgets restart). */
    val periodEnd: LocalDateTime? = null,
    /** Time used per window budget, keyed by [PolicyEngine.windowUsageKey]. */
    val windowUsedMillis: Map<String, Long> = emptyMap(),
    /** For durations across a daylight saving change. */
    val zone: ZoneId = ZoneId.systemDefault(),
)

enum class BlockReason {
    ALWAYS_ON,
    LIMIT_REACHED,
    UNSUPPORTED_BROWSER,

    /** One of Lares' own system-settings pages (settings guard), not a rule. */
    PROTECTED_SETTINGS,

    /** A schedule blocks right now (a "full block" window, or full block outside the windows). */
    SCHEDULE,
}

sealed interface Decision {
    /**
     * @property countedRuleIds standalone rules with a daily limit (or a schedule's "outside"
     *   timer) whose time is consumed while this stays in the foreground.
     * @property remainingMillis time until something blocks (smallest remaining budget, or a
     *   schedule switching to "full block"), or null.
     * @property countedGroupIds groups whose shared time is consumed (each group counts once,
     *   even if several members match, e.g. the YouTube app showing a youtube.com link).
     * @property countedWindowKeys window budgets consumed ([PolicyEngine.windowUsageKey]).
     * @property restrictedOwners rules / groups ([PolicyEngine.ownerKey]) behind [remainingMillis]
     *   (identifies the countdown / warning).
     */
    data class Allow(
        val countedRuleIds: Set<Long> = emptySet(),
        val remainingMillis: Long? = null,
        val countedGroupIds: Set<Long> = emptySet(),
        val countedWindowKeys: Set<String> = emptySet(),
        val restrictedOwners: Set<String> = emptySet(),
    ) : Decision

    /** @property until when it's available again according to a schedule (null: not from a schedule / unknown). */
    data class Block(
        val reason: BlockReason,
        val ruleId: Long? = null,
        val groupId: Long? = null,
        val until: LocalDateTime? = null,
    ) : Decision
}

/** Pure decision logic: (foreground app, optional URL, state) → allow / block. */
object PolicyEngine {

    /**
     * @param packageName foreground app.
     * @param url committed URL-bar contents if [packageName] is a supported browser, else null.
     */
    fun evaluate(packageName: String, url: String?, snapshot: PolicySnapshot): Decision {
        if (snapshot.pauseActive) return Decision.Allow()
        if (packageName in snapshot.exemptPackages) return Decision.Allow()

        val active = snapshot.rules.mapNotNull { it.effective(snapshot.groups) }
        val matching = buildList {
            active.filterTo(this) { it.type == RuleType.APP && it.target == packageName }
            if (snapshot.websiteBlockingEnabled && url != null && packageName in snapshot.supportedBrowsers) {
                val address = UrlMatcher.parse(url)
                if (address != null) {
                    active.filterTo(this) { rule ->
                        rule.type == RuleType.WEBSITE &&
                            UrlMatcher.normalizeRule(rule.target)?.let { UrlMatcher.matches(address, it) } == true
                    }
                }
            }
        }.map { restriction(it, snapshot) }

        matching.firstOrNull { it.segment.mode == WindowMode.BLOCK }?.let {
            return Decision.Block(
                if (it.schedule != null || it.rule.scheduleId != null) BlockReason.SCHEDULE else BlockReason.ALWAYS_ON,
                it.rule.id,
                it.groupId,
                it.schedule?.let { s -> availableAt(s, it.segment.end, outsideUsedUp(it, snapshot), snapshot.periodEnd) },
            )
        }

        if (snapshot.websiteBlockingEnabled &&
            packageName in snapshot.installedBrowsers &&
            packageName !in snapshot.supportedBrowsers
        ) {
            return Decision.Block(BlockReason.UNSUPPORTED_BROWSER)
        }

        var minRemaining: Long? = null
        val countedRules = mutableSetOf<Long>()
        val countedGroups = mutableSetOf<Long>()
        val countedWindows = mutableSetOf<String>()
        val owners = mutableSetOf<String>()
        for (r in matching) {
            var remaining: Long? = null
            if (r.segment.mode == WindowMode.TIMER) {
                val key = r.segment.budgetKey
                val used = when {
                    key != null -> snapshot.windowUsedMillis[windowUsageKey(r.owner, key)] ?: 0L
                    r.groupId != null -> snapshot.groupUsedMillis[r.groupId] ?: 0L
                    else -> snapshot.usedMillis[r.rule.id] ?: 0L
                }
                val budgetLeft = r.segment.minutes * 60_000L - used
                if (budgetLeft <= 0L) {
                    return Decision.Block(
                        BlockReason.LIMIT_REACHED,
                        r.rule.id,
                        r.groupId,
                        r.schedule?.let { s ->
                            // A used-up window: free again when it ends (whatever follows); the
                            // used-up "outside" budget: at the next window or the period's end.
                            if (key != null) availableAt(s, r.segment.end, outsideUsedUp(r, snapshot), snapshot.periodEnd)
                            else availableAt(s, snapshot.now, outsideExhausted = true, snapshot.periodEnd)
                        },
                    )
                }
                remaining = budgetLeft
                when {
                    key != null -> countedWindows += windowUsageKey(r.owner, key)
                    r.groupId != null -> countedGroups += r.groupId
                    else -> countedRules += r.rule.id
                }
            }
            // The schedule blocks at the end of this segment (full block, or a used-up budget follows).
            val now = snapshot.now
            val end = r.segment.end
            if (r.schedule != null && now != null && end != null && blocksAt(r, end, snapshot)) {
                val untilEnd = Duration.between(now.atZone(snapshot.zone), end.atZone(snapshot.zone)).toMillis()
                remaining = minOf(remaining ?: untilEnd, untilEnd)
            }
            if (remaining != null) {
                minRemaining = minOf(minRemaining ?: remaining, remaining)
                owners += r.owner
            }
        }
        return Decision.Allow(countedRules, minRemaining, countedGroups, countedWindows, owners)
    }

    fun remainingMillis(rule: Rule, usedMillis: Long): Long {
        val limit = rule.limitMinutesPerDay ?: return 0L
        return limit * 60_000L - usedMillis
    }

    /** "r12" for a standalone rule, "g3" for a group (whose budgets its members share). */
    fun ownerKey(ruleId: Long, groupId: Long?): String = if (groupId != null) "g$groupId" else "r$ruleId"

    fun windowUsageKey(owner: String, budgetKey: String): String = "$owner|$budgetKey"

    /**
     * What restricts [rule] right now: its schedule's current segment, or "always blocked" / the
     * daily limit as a schedule without windows. An unknown schedule (or no time) blocks.
     */
    fun restriction(rule: Rule, snapshot: PolicySnapshot): Restriction {
        val groupId = rule.groupId?.takeIf { it in snapshot.groups }
        val owner = ownerKey(rule.id, groupId)
        val scheduleId = rule.scheduleId
        if (scheduleId != null) {
            val schedule = snapshot.schedules[scheduleId]
            val now = snapshot.now
            if (schedule == null || now == null) {
                return Restriction(rule, groupId, owner, null, Schedule.alwaysBlocked.segmentAt(LocalDateTime.MIN))
            }
            return Restriction(rule, groupId, owner, schedule, schedule.segmentAt(now))
        }
        val asSchedule = rule.limitMinutesPerDay?.let(Schedule::dailyLimit) ?: Schedule.alwaysBlocked
        return Restriction(rule, groupId, owner, null, asSchedule.segmentAt(LocalDateTime.MIN))
    }

    /** @property schedule the rule's schedule (null for "always blocked" / daily limit). */
    data class Restriction(
        val rule: Rule,
        val groupId: Long?,
        val owner: String,
        val schedule: Schedule?,
        val segment: Schedule.Segment,
    )

    /** Whether [r]'s schedule blocks right at [time]: full block, or a timer without time left. */
    private fun blocksAt(r: Restriction, time: LocalDateTime, snapshot: PolicySnapshot): Boolean {
        val next = r.schedule?.segmentAt(time) ?: return false
        return when (next.mode) {
            WindowMode.BLOCK -> true
            WindowMode.FREE -> false
            WindowMode.TIMER -> {
                val key = next.budgetKey
                val used = if (key != null) snapshot.windowUsedMillis[windowUsageKey(r.owner, key)] ?: 0L else periodUsed(r, snapshot)
                used >= next.minutes * 60_000L
            }
        }
    }

    /** Time [r]'s owner used in the current usage period (the "outside" / daily budget). */
    private fun periodUsed(r: Restriction, snapshot: PolicySnapshot): Long =
        if (r.groupId != null) snapshot.groupUsedMillis[r.groupId] ?: 0L else snapshot.usedMillis[r.rule.id] ?: 0L

    /** The schedule's "outside" timer is used up for this period. */
    private fun outsideUsedUp(r: Restriction, snapshot: PolicySnapshot): Boolean {
        val s = r.schedule ?: return false
        return s.outside == WindowMode.TIMER && periodUsed(r, snapshot) >= s.outsideMinutes * 60_000L
    }

    /**
     * When [schedule] allows use again, starting the search at [from]: the first segment that is
     * free or has a fresh budget. [outsideExhausted]: the "outside" budget is used up until
     * [periodEnd]. Null if unknown (no end in sight within a week).
     */
    fun availableAt(schedule: Schedule, from: LocalDateTime?, outsideExhausted: Boolean, periodEnd: LocalDateTime?): LocalDateTime? {
        var t = from ?: return null
        repeat(MAX_SEGMENTS) {
            val seg = schedule.segmentAt(t)
            val outsideUsedUp = seg.mode == WindowMode.TIMER && seg.budgetKey == null &&
                outsideExhausted && (periodEnd == null || t.isBefore(periodEnd))
            val blocked = when (seg.mode) {
                WindowMode.BLOCK -> true
                WindowMode.FREE -> false
                WindowMode.TIMER -> seg.minutes == 0 || outsideUsedUp
            }
            if (!blocked) return t
            val budgetBack = periodEnd?.takeIf { outsideUsedUp && it.isAfter(t) }
            t = listOfNotNull(seg.end, budgetBack).minOrNull() ?: return null
        }
        return null
    }

    private const val MAX_SEGMENTS = 40
}
