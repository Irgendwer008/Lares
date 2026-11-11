/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

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
)

enum class BlockReason { ALWAYS_ON, LIMIT_REACHED, UNSUPPORTED_BROWSER }

sealed interface Decision {
    /**
     * @property countedRuleIds standalone rules with a daily limit whose time is consumed while
     *   this stays in the foreground.
     * @property remainingMillis smallest remaining time among everything counted, or null.
     * @property countedGroupIds groups whose shared time is consumed (each group counts once,
     *   even if several members match, e.g. the YouTube app showing a youtube.com link).
     */
    data class Allow(
        val countedRuleIds: Set<Long> = emptySet(),
        val remainingMillis: Long? = null,
        val countedGroupIds: Set<Long> = emptySet(),
    ) : Decision

    data class Block(val reason: BlockReason, val ruleId: Long? = null, val groupId: Long? = null) : Decision
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
        }

        matching.firstOrNull { it.isAlwaysOn }?.let {
            return Decision.Block(BlockReason.ALWAYS_ON, it.id, groupOf(it, snapshot))
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
        for (rule in matching) {
            val groupId = groupOf(rule, snapshot)
            val used = if (groupId != null) snapshot.groupUsedMillis[groupId] ?: 0L else snapshot.usedMillis[rule.id] ?: 0L
            val remaining = remainingMillis(rule, used)
            if (remaining <= 0L) return Decision.Block(BlockReason.LIMIT_REACHED, rule.id, groupId)
            minRemaining = minOf(minRemaining ?: remaining, remaining)
            if (groupId != null) countedGroups += groupId else countedRules += rule.id
        }
        return Decision.Allow(countedRules, minRemaining, countedGroups)
    }

    fun remainingMillis(rule: Rule, usedMillis: Long): Long {
        val limit = rule.limitMinutesPerDay ?: return 0L
        return limit * 60_000L - usedMillis
    }

    /** The group whose limit applies to [rule] (null for standalone rules or unknown groups). */
    private fun groupOf(rule: Rule, snapshot: PolicySnapshot): Long? = rule.groupId?.takeIf { it in snapshot.groups }
}
