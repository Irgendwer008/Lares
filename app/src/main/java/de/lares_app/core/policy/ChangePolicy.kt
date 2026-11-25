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

    /**
     * Compares two modes (always blocked / daily limit / schedule). Always blocked is as strict as
     * a 0 minute limit; switching to always blocked is tightening. A schedule can't be compared
     * with a limit or another schedule, so any other switch involving one is loosening (keeping
     * the same schedule changes nothing – its contents are protected separately).
     */
    private fun compareModes(oldLimit: Int?, oldSchedule: Long?, newLimit: Int?, newSchedule: Long?): ChangeKind = when {
        oldSchedule != null && oldSchedule == newSchedule -> ChangeKind.NONE
        oldSchedule != null && newSchedule == null && newLimit == null -> ChangeKind.TIGHTENING
        oldSchedule != null || newSchedule != null -> ChangeKind.LOOSENING
        else -> {
            val before = oldLimit ?: 0
            val after = newLimit ?: 0
            when {
                after < before -> ChangeKind.TIGHTENING
                after > before -> ChangeKind.LOOSENING
                else -> ChangeKind.NONE
            }
        }
    }

    /** [broader] blocks at least everything [narrower] blocks (website rules only). */
    private fun covers(broader: Rule, narrower: Rule): Boolean {
        if (broader.type != RuleType.WEBSITE || narrower.type != RuleType.WEBSITE) return false
        val b = UrlMatcher.normalizeRule(broader.target) ?: return false
        val n = UrlMatcher.normalizeRule(narrower.target) ?: return false
        return UrlMatcher.matches(n, b)
    }

    /** @param old null = rule is being added. @param new null = rule is being removed. */
    fun classify(old: Rule?, new: Rule?): ChangeKind {
        val oldActive = old?.takeIf { it.enabled }
        val newActive = new?.takeIf { it.enabled }
        return when {
            oldActive == null && newActive == null -> ChangeKind.NONE
            oldActive == null -> ChangeKind.TIGHTENING
            newActive == null -> ChangeKind.LOOSENING
            // Retargeting removes the old restriction – unless the new website rule still covers
            // everything the old one blocked (e.g. youtube.com/shorts → youtube.com).
            oldActive.key != newActive.key && !covers(newActive, oldActive) -> ChangeKind.LOOSENING
            else -> compareModes(oldActive.limitMinutesPerDay, oldActive.scheduleId, newActive.limitMinutesPerDay, newActive.scheduleId)
        }
    }

    /**
     * Like [classify], but compares the rules as enforced inside their groups (see [effective]).
     * Leaving an active group ends the time sharing, so it counts as loosening unless the
     * rule's new limit is strictly lower.
     */
    fun classifyInGroups(old: Rule?, new: Rule?, groups: Map<Long, RuleGroup>): ChangeKind {
        val oldEffective = old?.effective(groups)
        val base = classify(oldEffective, new?.effective(groups))
        val leftActiveGroup = oldEffective != null && old.groupId != null && old.groupId in groups && old.groupId != new?.groupId
        return if (leftActiveGroup && base != ChangeKind.TIGHTENING) ChangeKind.LOOSENING else base
    }

    /**
     * Group switch / limit changes. Only matters if the group has active members.
     * @param old null = group is being created. @param new null = group is being deleted.
     */
    fun classifyGroup(old: RuleGroup?, new: RuleGroup?, hasActiveMembers: Boolean): ChangeKind {
        if (!hasActiveMembers) return ChangeKind.NONE
        val before = old?.takeIf { it.enabled }
        val after = new?.takeIf { it.enabled }
        return when {
            before == null && after == null -> ChangeKind.NONE
            before == null -> ChangeKind.TIGHTENING
            after == null -> ChangeKind.LOOSENING
            else -> compareModes(before.limitMinutesPerDay, before.scheduleId, after.limitMinutesPerDay, after.scheduleId)
        }
    }

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
