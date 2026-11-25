/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.ChangeKind
import de.lares_app.core.policy.ChangePolicy
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.Schedule
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
 * All rule and group changes go through here. They throw [CodeRequiredException] unless the app
 * is unlocked – every change by default, only loosening ones with "only loosening needs the code"
 * ([UnlockGate.checkRuleChange]). Creating an empty group restricts nothing and stays free.
 */
class RuleRepository(
    private val store: RuleStore,
    private val groupStore: GroupStore,
    private val gate: UnlockGate,
    private val usage: UsageTransfer = UsageTransfer.None,
    /** For the export / import of schedules (null: schedules are left out). */
    private val schedules: ScheduleStore? = null,
) {
    val rules: Flow<List<Rule>> = store.rules
    val groups: Flow<List<RuleGroup>> = groupStore.groups

    // ---- Rules ---------------------------------------------------------------------------------

    /**
     * @param groupId add the rule as a member of this group (its limit / schedule then applies).
     * @param scheduleId use this schedule instead of [limitMinutesPerDay].
     */
    suspend fun add(
        type: RuleType,
        target: String,
        limitMinutesPerDay: Int? = null,
        groupId: Long? = null,
        scheduleId: Long? = null,
    ): AddRuleResult {
        if (limitMinutesPerDay != null && limitMinutesPerDay < 0) return AddRuleResult.InvalidTarget
        val normalized = normalizeTarget(type, target) ?: return AddRuleResult.InvalidTarget
        val group = groupId?.let { groupStore.get(it) ?: return AddRuleResult.InvalidTarget }
        val rule = Rule(
            id = 0,
            type = type,
            target = normalized,
            // Keep the group's limit / schedule as the rule's own, in case it's taken out of the group later.
            limitMinutesPerDay = if (group != null) group.limitMinutesPerDay else limitMinutesPerDay,
            groupId = groupId,
            scheduleId = if (group != null) group.scheduleId else scheduleId,
        )
        // Duplicates only count within the same scope: a target may be a single rule *and* a group
        // member at the same time – then both limits apply (whichever is reached first blocks).
        store.all().firstOrNull { it.key == rule.key && it.groupId == groupId }?.let { return AddRuleResult.Duplicate(it) }
        gate.checkRuleChange(ChangeKind.TIGHTENING)
        return AddRuleResult.Added(store.insert(rule))
    }

    /**
     * Adds several rules at once (templates). Targets that already exist in the same scope
     * (single rules, or the same group) are skipped.
     * @return how many rules were added.
     */
    suspend fun addAll(targets: List<Pair<RuleType, String>>, limitMinutesPerDay: Int?, groupId: Long? = null, scheduleId: Long? = null): Int =
        targets.count { (type, target) -> add(type, target, limitMinutesPerDay, groupId, scheduleId) is AddRuleResult.Added }

    /** Changes limit / enabled state / target / group of an existing rule. */
    suspend fun update(rule: Rule) {
        val old = requireNotNull(store.get(rule.id)) { "unknown rule ${rule.id}" }
        val normalized = normalizeTarget(rule.type, rule.target) ?: throw IllegalArgumentException("invalid target")
        val new = rule.copy(target = normalized)
        require(new.groupId == null || groupStore.get(new.groupId) != null) { "unknown group ${new.groupId}" }
        gate.checkRuleChange(ChangePolicy.classifyInGroups(old, new, groupMap()))
        store.update(new)
        if (old.groupId != new.groupId) usage.ruleMoved(new.id, old.groupId, new.groupId)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val old = store.get(id) ?: return
        update(old.copy(enabled = enabled))
    }

    /** Mode (limit / schedule) and on/off in one atomic change (one policy check for both). */
    suspend fun updateRule(id: Long, limitMinutesPerDay: Int?, enabled: Boolean, scheduleId: Long? = null) {
        val old = store.get(id) ?: return
        update(old.copy(limitMinutesPerDay = limitMinutesPerDay, enabled = enabled, scheduleId = scheduleId))
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
        gate.checkRuleChange(ChangePolicy.classifyInGroups(old, null, groupMap()))
        store.delete(id)
    }

    // ---- Groups --------------------------------------------------------------------------------

    /** Creating a group is free (it restricts nothing until members are added). */
    suspend fun createGroup(name: String, limitMinutesPerDay: Int?, scheduleId: Long? = null): Long {
        require(name.isNotBlank()) { "name must not be blank" }
        return groupStore.insert(RuleGroup(id = 0, name = name.trim(), limitMinutesPerDay = limitMinutesPerDay, scheduleId = scheduleId))
    }

    /** Creates a group and adds [targets] as members. @return the group id. */
    suspend fun createGroupWith(name: String, limitMinutesPerDay: Int?, targets: List<Pair<RuleType, String>>, scheduleId: Long? = null): Long {
        // Checked first, so no empty group is left behind if the code is needed for the members.
        if (targets.isNotEmpty()) gate.checkRuleChange(ChangeKind.TIGHTENING)
        val id = createGroup(name, limitMinutesPerDay, scheduleId)
        addAll(targets, limitMinutesPerDay, id)
        return id
    }

    suspend fun updateGroup(group: RuleGroup) {
        require(group.name.isNotBlank()) { "name must not be blank" }
        val old = requireNotNull(groupStore.get(group.id)) { "unknown group ${group.id}" }
        gate.checkRuleChange(ChangePolicy.classifyGroup(old, group, hasActiveMembers(group.id)))
        groupStore.update(group.copy(name = group.name.trim()))
    }

    suspend fun setGroupEnabled(id: Long, enabled: Boolean) {
        val old = groupStore.get(id) ?: return
        updateGroup(old.copy(enabled = enabled))
    }

    /** Deletes the group and its members (loosening if the group restricts anything). */
    suspend fun deleteGroup(id: Long) {
        val old = groupStore.get(id) ?: return
        gate.checkRuleChange(ChangePolicy.classifyGroup(old, null, hasActiveMembers(id)))
        groupStore.deleteWithMembers(id)
    }

    // ---- Backup --------------------------------------------------------------------------------

    suspend fun exportBackup(): BackupFile {
        val groups = groupStore.all()
        val names = groups.associate { it.id to it.name }
        val scheduleList = schedules?.all().orEmpty()
        val scheduleNames = scheduleList.associate { it.id to it.name }
        // With a schedule the limit is written as null (= always blocked): older versions that
        // don't know schedules fall back to the strictest mode.
        fun limitFor(limit: Int?, scheduleId: Long?) = if (scheduleId != null) null else limit
        return BackupFile(
            groups = groups.map {
                BackupGroup(it.name, limitFor(it.limitMinutesPerDay, it.scheduleId), it.enabled, it.scheduleId?.let(scheduleNames::get))
            },
            rules = store.all().map {
                BackupRule(
                    it.type,
                    it.target,
                    limitFor(it.limitMinutesPerDay, it.scheduleId),
                    it.enabled,
                    it.groupId?.let(names::get),
                    it.scheduleId?.let(scheduleNames::get),
                )
            },
            schedules = scheduleList.map { BackupSchedule(it.name, it.outside, it.outsideMinutes, it.windows) },
        )
    }

    /**
     * Imports by **adding only**: groups that don't exist yet (matched by name) are created, rules
     * that don't exist yet in their scope are added. Nothing existing is removed or loosened, so it
     * only needs the code if every rule change does. Existing groups keep their own limit.
     * Schedules that don't exist yet (by name) are added too – that always needs the code; a
     * reference to a schedule that isn't there (or invalid) becomes "always blocked".
     */
    suspend fun importBackup(backup: BackupFile): ImportResult {
        // Adding rules is a rule change (needs the code unless "only loosening needs the code").
        if (backup.rules.isNotEmpty()) gate.checkRuleChange(ChangeKind.TIGHTENING)
        val scheduleIds = importSchedules(backup.schedules)
        fun scheduleOf(name: String?) = name?.trim()?.let { scheduleIds[it.lowercase()] }
        val existing = groupStore.all().associate { it.name.trim() to it.id }.toMutableMap()
        var groupsAdded = 0
        for (g in backup.groups) {
            val name = g.name.trim()
            if (name.isEmpty() || name in existing) continue
            val scheduleId = scheduleOf(g.schedule)
            val limit = if (g.schedule != null) null else g.limitMinutesPerDay?.coerceAtLeast(0)
            existing[name] = groupStore.insert(RuleGroup(0, name, limit, g.enabled, scheduleId))
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
            val limit = if (r.schedule != null) null else r.limitMinutesPerDay
            store.insert(Rule(0, r.type, target, limit, r.enabled, groupId, scheduleOf(r.schedule)))
            added++
        }
        return ImportResult(groupsAdded, added, skipped)
    }

    /** @return lower-case name → id for all schedules (existing ones are kept as they are). */
    private suspend fun importSchedules(imported: List<BackupSchedule>): Map<String, Long> {
        val store = schedules ?: return emptyMap()
        val ids = store.all().associate { it.name.trim().lowercase() to it.id }.toMutableMap()
        val new = imported
            .map { Schedule(0, it.name.trim(), it.windows, it.outside, it.outsideMinutes) }
            .filter { it.name.isNotEmpty() && it.name.lowercase() !in ids && it.isValid() }
            .distinctBy { it.name.lowercase() }
        if (new.isNotEmpty()) gate.check(ChangeKind.LOOSENING) // every schedule change needs the code
        for (s in new) ids[s.name.lowercase()] = store.insert(s)
        return ids
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

/**
 * Moves the time already used along with a rule that changes group (see
 * [de.lares_app.core.blocking.UsageCounter.carryOver]) – otherwise moving it into a fresh group
 * with a lower limit would be "tightening" and still start a new quota.
 */
fun interface UsageTransfer {
    suspend fun ruleMoved(ruleId: Long, fromGroup: Long?, toGroup: Long?)

    companion object {
        val None = UsageTransfer { _, _, _ -> }
    }
}
