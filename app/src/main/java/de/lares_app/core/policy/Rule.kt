/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

enum class RuleType { APP, WEBSITE }

/**
 * A blocked app (target = package name) or website (target = domain, optionally with a
 * path prefix, e.g. `youtube.com/shorts`).
 *
 * @property limitMinutesPerDay `null` = always blocked, otherwise allowed minutes per usage day.
 *   Ignored while the rule belongs to a group (the group's limit applies).
 * @property groupId group this rule belongs to, or null for a standalone rule.
 */
data class Rule(
    val id: Long,
    val type: RuleType,
    val target: String,
    val limitMinutesPerDay: Int? = null,
    val enabled: Boolean = true,
    val groupId: Long? = null,
) {
    init {
        require(limitMinutesPerDay == null || limitMinutesPerDay >= 0) { "limit must be >= 0" }
    }

    val isAlwaysOn: Boolean get() = limitMinutesPerDay == null

    /** Identity of the blocked target independent of the database id (duplicate detection, import). */
    val key: RuleKey
        get() = RuleKey(
            type,
            when (type) {
                RuleType.APP -> target.trim()
                RuleType.WEBSITE -> UrlMatcher.normalizeRule(target)?.toString() ?: target.trim().lowercase()
            },
        )
}

data class RuleKey(val type: RuleType, val normalizedTarget: String)

/**
 * Several apps / websites sharing one limit: their foreground time is summed up, and the group
 * switch turns all members on or off at once.
 *
 * @property limitMinutesPerDay `null` = always blocked, otherwise combined minutes per usage day.
 */
data class RuleGroup(
    val id: Long,
    val name: String,
    val limitMinutesPerDay: Int? = null,
    val enabled: Boolean = true,
) {
    init {
        require(limitMinutesPerDay == null || limitMinutesPerDay >= 0) { "limit must be >= 0" }
    }
}

/**
 * The rule as it is actually enforced: inside a group, the group's switch and limit apply.
 * Returns null if the rule is not active at all.
 */
fun Rule.effective(groups: Map<Long, RuleGroup>): Rule? {
    if (!enabled) return null
    val group = groupId?.let { groups[it] } ?: return this
    if (!group.enabled) return null
    return copy(limitMinutesPerDay = group.limitMinutesPerDay)
}
