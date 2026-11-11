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
 */
data class Rule(
    val id: Long,
    val type: RuleType,
    val target: String,
    val limitMinutesPerDay: Int? = null,
    val enabled: Boolean = true,
) {
    init {
        require(limitMinutesPerDay == null || limitMinutesPerDay >= 0) { "limit must be >= 0" }
    }

    val isAlwaysOn: Boolean get() = limitMinutesPerDay == null

    /** Identity used to match rules across devices (import) independent of the database id. */
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
