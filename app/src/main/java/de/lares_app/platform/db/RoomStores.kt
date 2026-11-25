/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform.db

import de.lares_app.core.data.GroupStore
import de.lares_app.core.data.RuleStore
import de.lares_app.core.data.ScheduleStore
import de.lares_app.core.data.TamperEvent
import de.lares_app.core.data.TamperEventType
import de.lares_app.core.data.TamperLog
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.Schedule
import de.lares_app.core.policy.ScheduleWindow
import de.lares_app.core.policy.WindowMode
import de.lares_app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class RoomRuleStore(
    private val dao: RuleDao,
    private val clock: Clock,
) : RuleStore {
    override val rules: Flow<List<Rule>> = dao.observeAll().map { list -> list.map { it.toRule() } }

    override suspend fun all(): List<Rule> = dao.all().map { it.toRule() }

    override suspend fun get(id: Long): Rule? = dao.get(id)?.toRule()

    override suspend fun insert(rule: Rule): Long = dao.insert(rule.toEntity(clock.wallMillis()).copy(id = 0))

    override suspend fun update(rule: Rule) {
        val existing = dao.get(rule.id) ?: return
        dao.update(rule.toEntity(existing.createdAtMillis))
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    private fun RuleEntity.toRule() = Rule(id, RuleType.valueOf(type), target, limitMinutesPerDay, enabled, groupId, scheduleId)

    private fun Rule.toEntity(createdAt: Long) =
        RuleEntity(id, type.name, target, limitMinutesPerDay, enabled, createdAt, groupId, scheduleId)
}

class RoomGroupStore(
    private val dao: GroupDao,
    private val clock: Clock,
) : GroupStore {
    override val groups: Flow<List<RuleGroup>> = dao.observeAll().map { list -> list.map { it.toGroup() } }

    override suspend fun all(): List<RuleGroup> = dao.all().map { it.toGroup() }

    override suspend fun get(id: Long): RuleGroup? = dao.get(id)?.toGroup()

    override suspend fun insert(group: RuleGroup): Long =
        dao.insert(RuleGroupEntity(0, group.name, group.limitMinutesPerDay, group.enabled, clock.wallMillis(), group.scheduleId))

    override suspend fun update(group: RuleGroup) {
        val existing = dao.get(group.id) ?: return
        dao.update(
            existing.copy(name = group.name, limitMinutesPerDay = group.limitMinutesPerDay, enabled = group.enabled, scheduleId = group.scheduleId),
        )
    }

    override suspend fun deleteWithMembers(id: Long) = dao.deleteWithMembers(id)

    private fun RuleGroupEntity.toGroup() = RuleGroup(id, name, limitMinutesPerDay, enabled, scheduleId)
}

class RoomScheduleStore(
    private val dao: ScheduleDao,
    private val clock: Clock,
) : ScheduleStore {
    private val json = Json { ignoreUnknownKeys = true }

    override val schedules: Flow<List<Schedule>> = dao.observeAll().map { list -> list.map { it.toSchedule() } }

    override suspend fun all(): List<Schedule> = dao.all().map { it.toSchedule() }

    override suspend fun get(id: Long): Schedule? = dao.get(id)?.toSchedule()

    override suspend fun insert(schedule: Schedule): Long = dao.insert(schedule.toEntity(0, clock.wallMillis()))

    override suspend fun update(schedule: Schedule) {
        val existing = dao.get(schedule.id) ?: return
        dao.update(schedule.toEntity(schedule.id, existing.createdAtMillis))
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    private fun ScheduleEntity.toSchedule(): Schedule {
        val windows = runCatching { json.decodeFromString(ListSerializer(ScheduleWindow.serializer()), windowsJson) }.getOrNull()
        val mode = runCatching { WindowMode.valueOf(outside) }.getOrNull()
        // Damaged data: no windows and "full block" outside – never less strict than intended.
        if (windows == null || mode == null) return Schedule(id, name, emptyList(), WindowMode.BLOCK, 0)
        return Schedule(id, name, windows, mode, outsideMinutes)
    }

    private fun Schedule.toEntity(id: Long, createdAt: Long) = ScheduleEntity(
        id = id,
        name = name,
        outside = outside.name,
        outsideMinutes = outsideMinutes,
        windowsJson = json.encodeToString(ListSerializer(ScheduleWindow.serializer()), windows),
        createdAtMillis = createdAt,
    )
}

class RoomTamperLog(
    private val dao: TamperEventDao,
    private val clock: Clock,
) : TamperLog {
    override val events: Flow<List<TamperEvent>> = dao.observeAll().map { list -> list.map { it.toEvent() } }

    override suspend fun record(type: TamperEventType, details: String?) {
        dao.insert(TamperEventEntity(type = type.name, timestampMillis = clock.wallMillis(), details = details))
    }

    override suspend fun undelivered(): List<TamperEvent> = dao.undelivered().map { it.toEvent() }

    override suspend fun markDelivered(id: Long) = dao.markDelivered(id)

    private fun TamperEventEntity.toEvent() =
        TamperEvent(id, TamperEventType.valueOf(type), timestampMillis, details, delivered)
}
