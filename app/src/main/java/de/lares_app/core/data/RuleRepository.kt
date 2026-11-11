/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.ChangePolicy
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.UrlMatcher
import kotlinx.coroutines.flow.Flow

/** Persistence for rules (Room in production, in-memory in tests). */
interface RuleStore {
    val rules: Flow<List<Rule>>

    suspend fun all(): List<Rule>

    suspend fun get(id: Long): Rule?

    /** @return the new id. */
    suspend fun insert(rule: Rule): Long

    suspend fun update(rule: Rule)

    suspend fun delete(id: Long)

    suspend fun replaceAll(rules: List<Rule>)
}

sealed interface AddRuleResult {
    data class Added(val id: Long) : AddRuleResult

    /** An enabled rule for the same target exists; edit that one instead. */
    data class Duplicate(val existing: Rule) : AddRuleResult

    data object InvalidTarget : AddRuleResult
}

/**
 * All rule changes go through here. Tightening is always allowed; loosening throws
 * [CodeRequiredException] unless an edit session is open.
 */
class RuleRepository(
    private val store: RuleStore,
    private val gate: EditSessionGate,
) {
    val rules: Flow<List<Rule>> = store.rules

    suspend fun add(type: RuleType, target: String, limitMinutesPerDay: Int? = null): AddRuleResult {
        if (limitMinutesPerDay != null && limitMinutesPerDay < 0) return AddRuleResult.InvalidTarget
        val normalized = normalizeTarget(type, target) ?: return AddRuleResult.InvalidTarget
        val rule = Rule(id = 0, type = type, target = normalized, limitMinutesPerDay = limitMinutesPerDay)
        store.all().firstOrNull { it.enabled && it.key == rule.key }?.let { return AddRuleResult.Duplicate(it) }
        return AddRuleResult.Added(store.insert(rule))
    }

    /** Changes limit / enabled state / target of an existing rule. */
    suspend fun update(rule: Rule) {
        val old = requireNotNull(store.get(rule.id)) { "unknown rule ${rule.id}" }
        val normalized = normalizeTarget(rule.type, rule.target) ?: throw IllegalArgumentException("invalid target")
        val new = rule.copy(target = normalized)
        gate.check(ChangePolicy.classify(old, new))
        store.update(new)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val old = store.get(id) ?: return
        update(old.copy(enabled = enabled))
    }

    suspend fun setLimit(id: Long, limitMinutesPerDay: Int?) {
        val old = store.get(id) ?: return
        update(old.copy(limitMinutesPerDay = limitMinutesPerDay))
    }

    suspend fun remove(id: Long) {
        val old = store.get(id) ?: return
        gate.check(ChangePolicy.classify(old, null))
        store.delete(id)
    }

    /** Replaces all rules (import). Needs the code if anything gets looser. */
    suspend fun replaceAll(newRules: List<Rule>) {
        val normalized = newRules.map { r ->
            r.copy(target = normalizeTarget(r.type, r.target) ?: throw IllegalArgumentException("invalid target ${r.target}"))
        }
        gate.check(ChangePolicy.classifyRuleSet(store.all(), normalized))
        store.replaceAll(normalized)
    }

    companion object {
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun normalizeTarget(type: RuleType, target: String): String? = when (type) {
            RuleType.APP -> target.trim().takeIf { PACKAGE_NAME.matches(it) }
            RuleType.WEBSITE -> UrlMatcher.normalizeRule(target)?.toString()
        }
    }
}
