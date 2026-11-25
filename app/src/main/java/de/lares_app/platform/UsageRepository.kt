/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import de.lares_app.core.blocking.UsageCounter
import de.lares_app.core.blocking.UsageSnapshot
import de.lares_app.core.data.StateStore
import de.lares_app.core.data.UsageTransfer
import de.lares_app.core.data.TrackedPeriod
import de.lares_app.core.policy.Decision
import de.lares_app.core.policy.UsagePeriod
import de.lares_app.core.time.Clock
import de.lares_app.platform.db.GroupDao
import de.lares_app.platform.db.GroupUsageEntity
import de.lares_app.platform.db.RuleDao
import de.lares_app.platform.db.ScheduleDao
import de.lares_app.platform.db.WindowUsageEntity
import de.lares_app.platform.db.UsageDao
import de.lares_app.platform.db.UsageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Foreground time per rule / group for the current usage period. Counted in memory (every second)
 * and written to the database at most every [FLUSH_MILLIS] and on period change.
 */
class UsageRepository(
    private val usageDao: UsageDao,
    private val groupDao: GroupDao,
    private val ruleDao: RuleDao,
    private val scheduleDao: ScheduleDao,
    private val store: StateStore,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : UsageTransfer {
    private val mutex = Mutex()
    private var counter: UsageCounter? = null
    private var lastFlushElapsed = 0L
    private val _usage = MutableStateFlow<UsageSnapshot?>(null)

    /** Current period's usage (null until first loaded). */
    val usage: StateFlow<UsageSnapshot?> = _usage.asStateFlow()

    /** Loads / advances the period; call regularly (e.g. every tick). @return true if a new period started. */
    suspend fun refreshPeriod(): Boolean = mutex.withLock { refreshLocked() }

    suspend fun add(decision: Decision.Allow, millis: Long) = mutex.withLock {
        refreshLocked()
        val c = counter ?: return@withLock
        if (c.add(decision, millis)) {
            _usage.value = c.snapshot
            if (clock.elapsedMillis() - lastFlushElapsed >= FLUSH_MILLIS) flushLocked()
        }
    }

    suspend fun flush() = mutex.withLock { flushLocked() }

    /** A rule changed group: carry its used time over (saved right away). */
    override suspend fun ruleMoved(ruleId: Long, fromGroup: Long?, toGroup: Long?) = mutex.withLock {
        refreshLocked()
        val c = counter ?: return@withLock
        if (c.carryOver(ruleId, fromGroup, toGroup)) {
            _usage.value = c.snapshot
            flushLocked()
        }
    }

    private suspend fun refreshLocked(): Boolean {
        val state = store.current()
        val now = Instant.ofEpochMilli(clock.wallMillis())
        val schedule = state.settings.resetSchedule.settle(now, zone())
        val stored = state.trackedPeriod
        val tracked = stored?.let { UsagePeriod(Instant.ofEpochMilli(it.startMillis), Instant.ofEpochMilli(it.endMillis)) }
        val elapsedNow = clock.elapsedMillis()
        val boot = clock.bootId()
        val period = schedule.advance(tracked, now, zone(), stored?.trustedNowMillis(elapsedNow, boot))
        val periodChanged = period != tracked
        // The anchor stays for the whole boot; a new one only after a reboot (or for data from 1.0).
        val keepAnchor = stored?.hasAnchor(boot) == true
        if (periodChanged || !keepAnchor) {
            store.update {
                it.copy(
                    trackedPeriod = TrackedPeriod(
                        period.start.toEpochMilli(),
                        period.end.toEpochMilli(),
                        startElapsedMillis = if (keepAnchor) stored.startElapsedMillis else elapsedNow,
                        startBootId = boot,
                        anchorWallMillis = if (keepAnchor) stored.anchorWallMillis else now.toEpochMilli(),
                    ),
                    settings = it.settings.withResetSchedule(schedule),
                )
            }
        }
        val c = counter
        if (c == null || c.snapshot.periodStartMillis != period.start.toEpochMilli()) {
            if (c != null) flushLocked() // finish the old period
            counter = UsageCounter(load(period))
            _usage.value = counter!!.snapshot
            return true
        }
        return periodChanged
    }

    private suspend fun load(period: UsagePeriod): UsageSnapshot {
        val start = period.start.toEpochMilli()
        return UsageSnapshot(
            periodStartMillis = start,
            periodEndMillis = period.end.toEpochMilli(),
            ruleMillis = usageDao.period(start).associate { it.ruleId to it.foregroundMillis },
            groupMillis = groupDao.usage(start).associate { it.groupId to it.foregroundMillis },
            // Window budgets aren't tied to the period (see UsageSnapshot.windowMillis).
            windowMillis = scheduleDao.windowUsage().associate { it.key to it.foregroundMillis },
        )
    }

    private suspend fun flushLocked() {
        val snapshot = counter?.snapshot ?: return
        lastFlushElapsed = clock.elapsedMillis()
        // Rules / groups deleted meanwhile are skipped (foreign keys).
        val ruleIds = ruleDao.all().map { it.id }.toSet()
        val groupIds = groupDao.all().map { it.id }.toSet()
        snapshot.ruleMillis.filterKeys { it in ruleIds }.forEach { (id, ms) ->
            usageDao.upsert(UsageEntity(id, snapshot.periodStartMillis, ms))
        }
        snapshot.groupMillis.filterKeys { it in groupIds }.forEach { (id, ms) ->
            groupDao.upsertUsage(GroupUsageEntity(id, snapshot.periodStartMillis, ms))
        }
        snapshot.windowMillis.forEach { (key, ms) ->
            scheduleDao.upsertWindowUsage(WindowUsageEntity(key, ms, windowDate(key)))
        }
        // Old periods aren't needed (no statistics yet).
        usageDao.deleteOlderThan(snapshot.periodStartMillis)
        groupDao.deleteUsageOlderThan(snapshot.periodStartMillis)
        // Windows last at most 24 h: budgets of windows that started over two days ago are done.
        // The earlier of wall clock and monotonic clock decides, so setting the date forward
        // doesn't delete today's budgets.
        val trusted = store.current().trackedPeriod?.trustedNowMillis(clock.elapsedMillis(), clock.bootId())
        val nowMillis = minOf(clock.wallMillis(), trusted ?: Long.MAX_VALUE)
        val cutoff = Instant.ofEpochMilli(nowMillis).atZone(zone()).toLocalDate().minusDays(2).toEpochDay()
        scheduleDao.deleteWindowUsageBefore(cutoff)
        counter?.dropWindowsBefore(cutoff)
    }

    private companion object {
        const val FLUSH_MILLIS = 15_000L

        /** Start date of a window budget key ("r12|w3@2026-11-25"); unknown → today's (kept a while). */
        fun windowDate(key: String): Long =
            runCatching { LocalDate.parse(key.substringAfterLast('@')).toEpochDay() }.getOrDefault(LocalDate.now().toEpochDay())
    }
}
