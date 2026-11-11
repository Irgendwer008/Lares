/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

/** Everything the engine needs to know about the current state. Built by the Android layer. */
data class PolicySnapshot(
    val rules: List<Rule>,
    /** Foreground time already used in the current usage period, per rule id. */
    val usedMillis: Map<Long, Long> = emptyMap(),
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
     * @property countedRuleIds rules with a daily limit whose time is consumed while this
     *   stays in the foreground.
     * @property remainingMillis smallest remaining time among [countedRuleIds], or null.
     */
    data class Allow(
        val countedRuleIds: Set<Long> = emptySet(),
        val remainingMillis: Long? = null,
    ) : Decision

    data class Block(val reason: BlockReason, val ruleId: Long? = null) : Decision
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

        val matching = buildList {
            snapshot.rules.filterTo(this) {
                it.enabled && it.type == RuleType.APP && it.target == packageName
            }
            if (snapshot.websiteBlockingEnabled && url != null && packageName in snapshot.supportedBrowsers) {
                val address = UrlMatcher.parse(url)
                if (address != null) {
                    snapshot.rules.filterTo(this) { rule ->
                        rule.enabled && rule.type == RuleType.WEBSITE &&
                            UrlMatcher.normalizeRule(rule.target)?.let { UrlMatcher.matches(address, it) } == true
                    }
                }
            }
        }

        matching.firstOrNull { it.isAlwaysOn }?.let {
            return Decision.Block(BlockReason.ALWAYS_ON, it.id)
        }

        if (snapshot.websiteBlockingEnabled &&
            packageName in snapshot.installedBrowsers &&
            packageName !in snapshot.supportedBrowsers
        ) {
            return Decision.Block(BlockReason.UNSUPPORTED_BROWSER)
        }

        var minRemaining: Long? = null
        for (rule in matching) {
            val remaining = remainingMillis(rule, snapshot.usedMillis[rule.id] ?: 0L)
            if (remaining <= 0L) return Decision.Block(BlockReason.LIMIT_REACHED, rule.id)
            minRemaining = minOf(minRemaining ?: remaining, remaining)
        }
        return Decision.Allow(matching.mapTo(mutableSetOf()) { it.id }, minRemaining)
    }

    fun remainingMillis(rule: Rule, usedMillis: Long): Long {
        val limit = rule.limitMinutesPerDay ?: return 0L
        return limit * 60_000L - usedMillis
    }
}
