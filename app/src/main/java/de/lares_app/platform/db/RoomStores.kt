/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform.db

import de.lares_app.core.data.RuleStore
import de.lares_app.core.data.TamperEvent
import de.lares_app.core.data.TamperEventType
import de.lares_app.core.data.TamperLog
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleType
import de.lares_app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    override suspend fun replaceAll(rules: List<Rule>) {
        val now = clock.wallMillis()
        dao.replaceAll(rules.mapIndexed { i, r -> r.toEntity(now + i).copy(id = 0) })
    }

    private fun RuleEntity.toRule() = Rule(id, RuleType.valueOf(type), target, limitMinutesPerDay, enabled)

    private fun Rule.toEntity(createdAt: Long) =
        RuleEntity(id, type.name, target, limitMinutesPerDay, enabled, createdAt)
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
