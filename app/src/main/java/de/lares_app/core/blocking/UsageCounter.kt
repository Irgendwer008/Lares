/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.blocking

import de.lares_app.core.policy.Decision
import de.lares_app.core.policy.PolicyEngine

/** Foreground time used in one usage period (keyed by the period's start, see ResetSchedule). */
data class UsageSnapshot(
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val ruleMillis: Map<Long, Long> = emptyMap(),
    val groupMillis: Map<Long, Long> = emptyMap(),
    /**
     * Schedule window budgets ([de.lares_app.core.policy.PolicyEngine.windowUsageKey]). Not tied to
     * the usage period: a window's budget belongs to the window (keyed by its start date), also
     * when it runs across the daily reset.
     */
    val windowMillis: Map<String, Long> = emptyMap(),
)

/**
 * Accumulates foreground time for the rules / groups a [Decision.Allow] counts. A group counts
 * once per tick even if several of its members match (e.g. app + website).
 */
class UsageCounter(initial: UsageSnapshot) {
    var snapshot: UsageSnapshot = initial
        private set

    /** Adds [millis] to everything [decision] counts. @return true if anything changed. */
    fun add(decision: Decision.Allow, millis: Long): Boolean {
        if (millis <= 0 || (decision.countedRuleIds.isEmpty() && decision.countedGroupIds.isEmpty() && decision.countedWindowKeys.isEmpty())) {
            return false
        }
        snapshot = snapshot.copy(
            ruleMillis = snapshot.ruleMillis.plusEach(decision.countedRuleIds, millis),
            groupMillis = snapshot.groupMillis.plusEach(decision.countedGroupIds, millis),
            windowMillis = snapshot.windowMillis.plusEach(decision.countedWindowKeys, millis),
        )
        return true
    }

    /**
     * A rule moves between counters (standalone ↔ group, group ↔ group): the destination never
     * has less time used than the source had, so moving can't start a fresh quota.
     * @return true if anything changed.
     */
    fun carryOver(ruleId: Long, fromGroup: Long?, toGroup: Long?): Boolean {
        if (fromGroup == toGroup) return false
        val windowsChanged = carryOverWindows(PolicyEngine.ownerKey(ruleId, fromGroup), PolicyEngine.ownerKey(ruleId, toGroup))
        val used = (if (fromGroup == null) snapshot.ruleMillis[ruleId] else snapshot.groupMillis[fromGroup]) ?: 0L
        if (used <= 0L) return windowsChanged
        val target = if (toGroup == null) snapshot.ruleMillis[ruleId] ?: 0L else snapshot.groupMillis[toGroup] ?: 0L
        if (target >= used) return windowsChanged
        snapshot = if (toGroup == null) {
            snapshot.copy(ruleMillis = snapshot.ruleMillis + (ruleId to used))
        } else {
            snapshot.copy(groupMillis = snapshot.groupMillis + (toGroup to used))
        }
        return true
    }

    /** The same for schedule window budgets: the new owner's budgets are at least the old owner's. */
    private fun carryOverWindows(fromOwner: String, toOwner: String): Boolean {
        val moved = snapshot.windowMillis.filterKeys { it.startsWith("$fromOwner|") }
            .mapKeys { (key, _) -> "$toOwner|" + key.substringAfter('|') }
            .filter { (key, used) -> used > (snapshot.windowMillis[key] ?: 0L) }
        if (moved.isEmpty()) return false
        snapshot = snapshot.copy(windowMillis = snapshot.windowMillis + moved)
        return true
    }

    /** Forgets window budgets of windows that started before [epochDay] (already removed from storage). */
    fun dropWindowsBefore(epochDay: Long) {
        val kept = snapshot.windowMillis.filterKeys { key ->
            runCatching { java.time.LocalDate.parse(key.substringAfterLast('@')).toEpochDay() >= epochDay }.getOrDefault(true)
        }
        if (kept.size != snapshot.windowMillis.size) snapshot = snapshot.copy(windowMillis = kept)
    }

    private fun <K> Map<K, Long>.plusEach(ids: Set<K>, millis: Long): Map<K, Long> =
        if (ids.isEmpty()) this else toMutableMap().apply { ids.forEach { merge(it, millis, Long::plus) } }
}

/**
 * Decides which optional "limit almost reached" warning each limit should show: the smallest
 * enabled threshold the remaining time is at or below. Only changes are reported, so the
 * notification follows the remaining time – also when it grows again (e.g. a higher limit):
 * back to the 5-minute warning, or gone above 5 minutes (and the warnings fire again later).
 */
class LimitWarnings {
    private val shown = mutableMapOf<String, Long>()

    sealed interface Change {
        data class Show(val thresholdMillis: Long) : Change

        data object Remove : Change
    }

    /**
     * @param subject identifies what is counted (e.g. the counted rule / group ids).
     * @param thresholds enabled thresholds in millis (e.g. 5 min, 1 min); empty = warnings off.
     * @return what to do with the subject's notification, or null if nothing changed.
     */
    fun update(subject: String, remainingMillis: Long, thresholds: Collection<Long>): Change? {
        val wanted = thresholds.filter { remainingMillis in 1..it }.minOrNull()
        if (wanted == shown[subject]) return null
        return if (wanted == null) {
            shown.remove(subject)
            Change.Remove
        } else {
            shown[subject] = wanted
            Change.Show(wanted)
        }
    }

    /** Subjects not in [current] lose their warning. @return those that had one (to remove it). */
    fun removeAllExcept(current: Set<String>): Set<String> {
        val gone = shown.keys - current
        gone.forEach(shown::remove)
        return gone
    }

    /** New period: forget everything. @return the subjects whose warning was shown (to remove it). */
    fun reset(): Set<String> = shown.keys.toSet().also { shown.clear() }

    companion object {
        const val FIVE_MINUTES = 5 * 60_000L
        const val ONE_MINUTE = 60_000L

        /** The countdown overlay is shown during the last 5 minutes. */
        const val TIMER_THRESHOLD = FIVE_MINUTES

        /** The rules / groups behind the countdown, plus the window budgets (a new window = a new warning). */
        fun subjectOf(decision: Decision.Allow): String =
            "o" + decision.restrictedOwners.sorted().joinToString(",") + "w" + decision.countedWindowKeys.sorted().joinToString(",")
    }
}
