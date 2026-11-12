/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.blocking

import de.lares_app.core.policy.Decision

/** Foreground time used in one usage period (keyed by the period's start, see ResetSchedule). */
data class UsageSnapshot(
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val ruleMillis: Map<Long, Long> = emptyMap(),
    val groupMillis: Map<Long, Long> = emptyMap(),
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
        if (millis <= 0 || (decision.countedRuleIds.isEmpty() && decision.countedGroupIds.isEmpty())) return false
        snapshot = snapshot.copy(
            ruleMillis = snapshot.ruleMillis.plusEach(decision.countedRuleIds, millis),
            groupMillis = snapshot.groupMillis.plusEach(decision.countedGroupIds, millis),
        )
        return true
    }

    /** Starts a new period with empty counters. */
    fun startPeriod(startMillis: Long, endMillis: Long) {
        snapshot = UsageSnapshot(startMillis, endMillis)
    }

    private fun Map<Long, Long>.plusEach(ids: Set<Long>, millis: Long): Map<Long, Long> =
        if (ids.isEmpty()) this else toMutableMap().apply { ids.forEach { merge(it, millis, Long::plus) } }
}

/**
 * Decides when to send the optional "limit almost reached" warnings: each threshold fires once
 * per period and limit subject, when the remaining time drops to or below it.
 */
class LimitWarnings {
    private val sent = mutableSetOf<Pair<String, Long>>()

    /**
     * @param subject identifies what is counted (e.g. the counted rule / group ids).
     * @param thresholds enabled thresholds in millis (e.g. 5 min, 1 min).
     * @return the thresholds that were just crossed (usually none or one).
     */
    fun crossed(subject: String, remainingMillis: Long, thresholds: Collection<Long>): List<Long> =
        thresholds.filter { t -> remainingMillis in 1..t && sent.add(subject to t) }

    /** New period: every warning may fire again. */
    fun reset() = sent.clear()

    companion object {
        const val FIVE_MINUTES = 5 * 60_000L
        const val ONE_MINUTE = 60_000L

        /** The countdown overlay is shown during the last 5 minutes. */
        const val TIMER_THRESHOLD = FIVE_MINUTES

        fun subjectOf(decision: Decision.Allow): String =
            "r" + decision.countedRuleIds.sorted().joinToString(",") + "g" + decision.countedGroupIds.sorted().joinToString(",")
    }
}
