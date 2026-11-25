/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.ChangeKind
import de.lares_app.core.policy.Schedule
import kotlinx.coroutines.flow.Flow

interface ScheduleStore {
    val schedules: Flow<List<Schedule>>

    suspend fun all(): List<Schedule>

    suspend fun get(id: Long): Schedule?

    /** @return the new id. */
    suspend fun insert(schedule: Schedule): Long

    suspend fun update(schedule: Schedule)

    suspend fun delete(id: Long)
}

/** Why a schedule can't be saved. */
class InvalidScheduleException(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { BLANK_NAME, DUPLICATE_NAME, INVALID_WINDOWS }
}

/** A schedule that rules or groups still use can't be deleted. */
class ScheduleInUseException(val usedBy: List<String>) : IllegalStateException("schedule in use")

/**
 * Schedules are shared by rules and groups, so **every** change to them – creating, editing,
 * deleting, used or not – needs the guardian code (also with "only loosening needs the code").
 */
class ScheduleRepository(
    private val store: ScheduleStore,
    private val rules: RuleStore,
    private val groups: GroupStore,
    private val gate: UnlockGate,
) {
    val schedules: Flow<List<Schedule>> = store.schedules

    suspend fun get(id: Long): Schedule? = store.get(id)

    /** @return the new id. */
    suspend fun create(schedule: Schedule): Long {
        val clean = validated(schedule, ignoreId = null)
        gate.check(ChangeKind.LOOSENING)
        return store.insert(clean.copy(id = 0))
    }

    suspend fun update(schedule: Schedule) {
        requireNotNull(store.get(schedule.id)) { "unknown schedule ${schedule.id}" }
        val clean = validated(schedule, ignoreId = schedule.id)
        gate.check(ChangeKind.LOOSENING)
        store.update(clean)
    }

    /** @throws ScheduleInUseException while rules or groups use it. */
    suspend fun delete(id: Long) {
        store.get(id) ?: return
        val users = usedBy(id)
        if (users.isNotEmpty()) throw ScheduleInUseException(users)
        gate.check(ChangeKind.LOOSENING)
        store.delete(id)
    }

    /**
     * Names of the groups and targets of the rules that use schedule [id]. Group members don't
     * count – the group's choice applies to them (they only keep a copy for leaving the group).
     */
    suspend fun usedBy(id: Long): List<String> {
        val groupList = groups.all()
        val groupIds = groupList.map { it.id }.toSet()
        return groupList.filter { it.scheduleId == id }.map { it.name } +
            rules.all().filter { it.scheduleId == id && (it.groupId == null || it.groupId !in groupIds) }.map { it.target }
    }

    private suspend fun validated(schedule: Schedule, ignoreId: Long?): Schedule {
        val name = schedule.name.trim()
        if (name.isEmpty()) throw InvalidScheduleException(InvalidScheduleException.Reason.BLANK_NAME)
        if (store.all().any { it.id != ignoreId && it.name.trim().equals(name, ignoreCase = true) }) {
            throw InvalidScheduleException(InvalidScheduleException.Reason.DUPLICATE_NAME)
        }
        val clean = schedule.copy(name = name, windows = schedule.windows.sortedWith(compareBy({ it.day }, { it.startMinute })))
        if (!clean.isValid()) throw InvalidScheduleException(InvalidScheduleException.Reason.INVALID_WINDOWS)
        return clean
    }
}
