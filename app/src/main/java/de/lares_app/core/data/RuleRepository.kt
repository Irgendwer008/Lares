/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.ChangePolicy
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
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
}

/** Persistence for rule groups. */
interface GroupStore {
    val groups: Flow<List<RuleGroup>>

    suspend fun all(): List<RuleGroup>

    suspend fun get(id: Long): RuleGroup?

    /** @return the new id. */
    suspend fun insert(group: RuleGroup): Long

    suspend fun update(group: RuleGroup)

    /** Deletes the group together with all its member rules. */
    suspend fun deleteWithMembers(id: Long)
}

sealed interface AddRuleResult {
    data class Added(val id: Long) : AddRuleResult

    /** The same target is already a rule here (among single rules, or in the same group). */
    data class Duplicate(val existing: Rule) : AddRuleResult

    data object InvalidTarget : AddRuleResult
}

/**
 * All rule and group changes go through here. Tightening is always allowed; loosening throws
 * [CodeRequiredException] unless the app is unlocked.
 */
class RuleRepository(
    private val store: RuleStore,
    private val groupStore: GroupStore,
    private val gate: UnlockGate,
) {
    val rules: Flow<List<Rule>> = store.rules
    val groups: Flow<List<RuleGroup>> = groupStore.groups

    // ---- Rules ---------------------------------------------------------------------------------

    /** @param groupId add the rule as a member of this group (its limit then applies). */
    suspend fun add(type: RuleType, target: String, limitMinutesPerDay: Int? = null, groupId: Long? = null): AddRuleResult {
        if (limitMinutesPerDay != null && limitMinutesPerDay < 0) return AddRuleResult.InvalidTarget
        val normalized = normalizeTarget(type, target) ?: return AddRuleResult.InvalidTarget
        val group = groupId?.let { groupStore.get(it) ?: return AddRuleResult.InvalidTarget }
        val rule = Rule(
            id = 0,
            type = type,
            target = normalized,
            // Keep the group's limit as the rule's own limit, in case it's taken out of the group later.
            limitMinutesPerDay = group?.limitMinutesPerDay ?: limitMinutesPerDay,
            groupId = groupId,
        )
        // Duplicates only count within the same scope: a target may be a single rule *and* a group
        // member at the same time – then both limits apply (whichever is reached first blocks).
        store.all().firstOrNull { it.key == rule.key && it.groupId == groupId }?.let { return AddRuleResult.Duplicate(it) }
        return AddRuleResult.Added(store.insert(rule))
    }

    /**
     * Adds several rules at once (templates). Targets that already exist in the same scope
     * (single rules, or the same group) are skipped.
     * @return how many rules were added.
     */
    suspend fun addAll(targets: List<Pair<RuleType, String>>, limitMinutesPerDay: Int?, groupId: Long? = null): Int =
        targets.count { (type, target) -> add(type, target, limitMinutesPerDay, groupId) is AddRuleResult.Added }

    /** Changes limit / enabled state / target / group of an existing rule. */
    suspend fun update(rule: Rule) {
        val old = requireNotNull(store.get(rule.id)) { "unknown rule ${rule.id}" }
        val normalized = normalizeTarget(rule.type, rule.target) ?: throw IllegalArgumentException("invalid target")
        val new = rule.copy(target = normalized)
        require(new.groupId == null || groupStore.get(new.groupId) != null) { "unknown group ${new.groupId}" }
        gate.check(ChangePolicy.classifyInGroups(old, new, groupMap()))
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

    /** Limit and on/off in one atomic change (one policy check for both). */
    suspend fun updateRule(id: Long, limitMinutesPerDay: Int?, enabled: Boolean) {
        val old = store.get(id) ?: return
        update(old.copy(limitMinutesPerDay = limitMinutesPerDay, enabled = enabled))
    }

    /**
     * Moves a rule into a group, or out of it ([groupId] = null). If the destination already has a
     * rule for the same target, nothing happens (no duplicates, and removing this one could loosen).
     */
    suspend fun setGroup(id: Long, groupId: Long?) {
        val old = store.get(id) ?: return
        if (store.all().any { it.id != id && it.groupId == groupId && it.key == old.key }) return
        update(old.copy(groupId = groupId))
    }

    suspend fun remove(id: Long) {
        val old = store.get(id) ?: return
        gate.check(ChangePolicy.classifyInGroups(old, null, groupMap()))
        store.delete(id)
    }

    // ---- Groups --------------------------------------------------------------------------------

    /** Creating a group is free (it restricts nothing until members are added). */
    suspend fun createGroup(name: String, limitMinutesPerDay: Int?): Long {
        require(name.isNotBlank()) { "name must not be blank" }
        return groupStore.insert(RuleGroup(id = 0, name = name.trim(), limitMinutesPerDay = limitMinutesPerDay))
    }

    /** Creates a group and adds [targets] as members. @return the group id. */
    suspend fun createGroupWith(name: String, limitMinutesPerDay: Int?, targets: List<Pair<RuleType, String>>): Long {
        val id = createGroup(name, limitMinutesPerDay)
        addAll(targets, limitMinutesPerDay, id)
        return id
    }

    suspend fun updateGroup(group: RuleGroup) {
        require(group.name.isNotBlank()) { "name must not be blank" }
        val old = requireNotNull(groupStore.get(group.id)) { "unknown group ${group.id}" }
        gate.check(ChangePolicy.classifyGroup(old, group, hasActiveMembers(group.id)))
        groupStore.update(group.copy(name = group.name.trim()))
    }

    suspend fun setGroupEnabled(id: Long, enabled: Boolean) {
        val old = groupStore.get(id) ?: return
        updateGroup(old.copy(enabled = enabled))
    }

    /** Deletes the group and its members (loosening if the group restricts anything). */
    suspend fun deleteGroup(id: Long) {
        val old = groupStore.get(id) ?: return
        gate.check(ChangePolicy.classifyGroup(old, null, hasActiveMembers(id)))
        groupStore.deleteWithMembers(id)
    }

    // ---- Backup --------------------------------------------------------------------------------

    suspend fun exportBackup(): BackupFile {
        val groups = groupStore.all()
        val names = groups.associate { it.id to it.name }
        return BackupFile(
            groups = groups.map { BackupGroup(it.name, it.limitMinutesPerDay, it.enabled) },
            rules = store.all().map { BackupRule(it.type, it.target, it.limitMinutesPerDay, it.enabled, it.groupId?.let(names::get)) },
        )
    }

    /**
     * Imports by **adding only**: groups that don't exist yet (matched by name) are created, rules
     * that don't exist yet in their scope are added. Nothing existing is removed or loosened, so no
     * guardian code is needed. Existing groups keep their own limit.
     */
    suspend fun importBackup(backup: BackupFile): ImportResult {
        val existing = groupStore.all().associate { it.name.trim() to it.id }.toMutableMap()
        var groupsAdded = 0
        for (g in backup.groups) {
            val name = g.name.trim()
            if (name.isEmpty() || name in existing) continue
            existing[name] = groupStore.insert(RuleGroup(0, name, g.limitMinutesPerDay?.coerceAtLeast(0), g.enabled))
            groupsAdded++
        }
        var added = 0
        var skipped = 0
        for (r in backup.rules) {
            val groupId = r.group?.trim()?.let { existing[it] }
            val target = normalizeTarget(r.type, r.target)
            val duplicate = target != null && store.all().any { it.groupId == groupId && it.key == Rule(0, r.type, target).key }
            if (target == null || duplicate || (r.limitMinutesPerDay ?: 0) < 0) {
                skipped++
                continue
            }
            // Inserting directly keeps a disabled rule disabled (adding a disabled rule restricts nothing).
            store.insert(Rule(0, r.type, target, r.limitMinutesPerDay, r.enabled, groupId))
            added++
        }
        return ImportResult(groupsAdded, added, skipped)
    }

    private suspend fun groupMap(): Map<Long, RuleGroup> = groupStore.all().associateBy { it.id }

    private suspend fun hasActiveMembers(groupId: Long) = store.all().any { it.groupId == groupId && it.enabled }

    companion object {
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun normalizeTarget(type: RuleType, target: String): String? = when (type) {
            RuleType.APP -> target.trim().takeIf { PACKAGE_NAME.matches(it) }
            RuleType.WEBSITE -> UrlMatcher.normalizeRule(target)?.toString()
        }
    }
}
