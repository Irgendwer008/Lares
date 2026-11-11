/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.testing

import de.lares_app.core.data.AppState
import de.lares_app.core.data.GroupStore
import de.lares_app.core.data.RuleStore
import de.lares_app.core.data.SecretCipher
import de.lares_app.core.data.StateStore
import de.lares_app.core.data.TamperEvent
import de.lares_app.core.data.TamperEventType
import de.lares_app.core.data.TamperLog
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Base64

class FakeClock(
    var wall: Long = 1_800_000_000_000L,
    var elapsed: Long = 10_000L,
    var boot: Int = 1,
) : Clock {
    override fun wallMillis() = wall

    override fun elapsedMillis() = elapsed

    override fun bootId() = boot

    fun advance(millis: Long) {
        wall += millis
        elapsed += millis
    }
}

class FakeStateStore(initial: AppState = AppState()) : StateStore {
    private val flow = MutableStateFlow(initial)
    override val state: Flow<AppState> = flow

    override suspend fun current() = flow.value

    override suspend fun update(transform: (AppState) -> AppState): AppState {
        flow.update(transform)
        return flow.value
    }
}

/** Not secure – just reversible, to test the code paths. */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): String = Base64.getEncoder().encodeToString(plain.reversedArray())

    override fun decrypt(encoded: String): ByteArray = Base64.getDecoder().decode(encoded).reversedArray()
}

class FakeTamperLog : TamperLog {
    private val flow = MutableStateFlow<List<TamperEvent>>(emptyList())
    override val events: Flow<List<TamperEvent>> = flow
    val recorded get() = flow.value.map { it.type }

    override suspend fun record(type: TamperEventType, details: String?) {
        flow.update { it + TamperEvent(it.size + 1L, type, 0L, details, false) }
    }

    override suspend fun undelivered() = flow.value.filterNot { it.delivered }

    override suspend fun markDelivered(id: Long) {
        flow.update { list -> list.map { if (it.id == id) it.copy(delivered = true) else it } }
    }
}

class FakeRuleStore : RuleStore {
    private val flow = MutableStateFlow<List<Rule>>(emptyList())
    private var nextId = 1L
    override val rules: Flow<List<Rule>> = flow

    override suspend fun all() = flow.value

    override suspend fun get(id: Long) = flow.value.firstOrNull { it.id == id }

    override suspend fun insert(rule: Rule): Long {
        val id = nextId++
        flow.update { it + rule.copy(id = id) }
        return id
    }

    override suspend fun update(rule: Rule) {
        flow.update { list -> list.map { if (it.id == rule.id) rule else it } }
    }

    override suspend fun delete(id: Long) {
        flow.update { list -> list.filterNot { it.id == id } }
    }
}

/** Shares the rule store so [deleteWithMembers] can remove members like the Room DAO does. */
class FakeGroupStore(private val rules: FakeRuleStore) : GroupStore {
    private val flow = MutableStateFlow<List<RuleGroup>>(emptyList())
    private var nextId = 1L
    override val groups: Flow<List<RuleGroup>> = flow

    override suspend fun all() = flow.value

    override suspend fun get(id: Long) = flow.value.firstOrNull { it.id == id }

    override suspend fun insert(group: RuleGroup): Long {
        val id = nextId++
        flow.update { it + group.copy(id = id) }
        return id
    }

    override suspend fun update(group: RuleGroup) {
        flow.update { list -> list.map { if (it.id == group.id) group else it } }
    }

    override suspend fun deleteWithMembers(id: Long) {
        rules.all().filter { it.groupId == id }.forEach { rules.delete(it.id) }
        flow.update { list -> list.filterNot { it.id == id } }
    }
}
