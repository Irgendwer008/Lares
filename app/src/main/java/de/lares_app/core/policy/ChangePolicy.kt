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
            else -> {
                val b = before.limitMinutesPerDay ?: 0
                val a = after.limitMinutesPerDay ?: 0
                when {
                    a < b -> ChangeKind.TIGHTENING
                    a > b -> ChangeKind.LOOSENING
                    else -> ChangeKind.NONE
                }
            }
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
