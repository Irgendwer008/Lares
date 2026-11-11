/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import java.time.LocalTime

enum class ChangeKind {
    NONE, TIGHTENING, LOOSENING;

    val needsCode: Boolean get() = this == LOOSENING

    fun combine(other: ChangeKind): ChangeKind = if (other.ordinal > ordinal) other else this
}

/**
 * Classifies changes as tightening (free) or loosening (needs the guardian code).
 * See docs/unlock-and-security.md, "Tighten vs. loosen policy".
 */
object ChangePolicy {

    /** Strictness of an enabled rule: always-on is as strict as a 0 minute limit. */
    private fun allowedMinutes(rule: Rule): Int = rule.limitMinutesPerDay ?: 0

    /** @param old null = rule is being added. @param new null = rule is being removed. */
    fun classify(old: Rule?, new: Rule?): ChangeKind {
        val oldActive = old?.takeIf { it.enabled }
        val newActive = new?.takeIf { it.enabled }
        return when {
            oldActive == null && newActive == null -> ChangeKind.NONE
            oldActive == null -> ChangeKind.TIGHTENING
            newActive == null -> ChangeKind.LOOSENING
            oldActive.key != newActive.key -> ChangeKind.LOOSENING // retargeting removes the old restriction
            else -> {
                val before = allowedMinutes(oldActive)
                val after = allowedMinutes(newActive)
                when {
                    after < before -> ChangeKind.TIGHTENING
                    after > before -> ChangeKind.LOOSENING
                    else -> ChangeKind.NONE
                }
            }
        }
    }

    /**
     * Classifies replacing a whole rule set (e.g. import). Rules are matched by [Rule.key];
     * if several rules share a key, the strictest one counts.
     */
    fun classifyRuleSet(old: Collection<Rule>, new: Collection<Rule>): ChangeKind {
        val before = strictestByKey(old)
        val after = strictestByKey(new)
        return (before.keys + after.keys).fold(ChangeKind.NONE) { acc, key ->
            acc.combine(classify(before[key], after[key]))
        }
    }

    private fun strictestByKey(rules: Collection<Rule>): Map<RuleKey, Rule> =
        rules.filter { it.enabled }
            .groupBy { it.key }
            .mapValues { (_, group) -> group.minBy { allowedMinutes(it) } }

    /** For protections and website blocking: turning on is tightening, off is loosening. */
    fun classifyToggle(old: Boolean, new: Boolean): ChangeKind = when {
        old == new -> ChangeKind.NONE
        new -> ChangeKind.TIGHTENING
        else -> ChangeKind.LOOSENING
    }

    /** Moving the daily reset in either direction can grant a fresh quota → always loosening. */
    fun classifyResetTime(old: LocalTime, new: LocalTime): ChangeKind =
        if (old == new) ChangeKind.NONE else ChangeKind.LOOSENING
}
