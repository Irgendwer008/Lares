/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.Schedule
import de.lares_app.core.policy.ScheduleWindow
import de.lares_app.core.policy.TimedSession
import de.lares_app.core.policy.WindowMode
import de.lares_app.testing.FakeClock
import de.lares_app.testing.FakeGroupStore
import de.lares_app.testing.FakeRuleStore
import de.lares_app.testing.FakeScheduleStore
import de.lares_app.testing.FakeStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulesTest {
    private val clock = FakeClock()
    // "Only loosening" on: schedules need the code anyway.
    private val state = FakeStateStore(
        AppState(unlock = UnlockState(method = UnlockMethod.PASSWORD, passwordHash = "x"), settings = AppSettings(setupComplete = true, onlyLooseningNeedsCode = true)),
    )
    private val gate = UnlockGate(state, clock)
    private val ruleStore = FakeRuleStore()
    private val groupStore = FakeGroupStore(ruleStore)
    private val scheduleStore = FakeScheduleStore()
    private val schedules = ScheduleRepository(scheduleStore, ruleStore, groupStore, gate)
    private val rules = RuleRepository(ruleStore, groupStore, gate, schedules = scheduleStore)

    private val school = Schedule(
        0,
        "School",
        listOf(ScheduleWindow(1, 1, 9 * 60, 8 * 60, WindowMode.BLOCK), ScheduleWindow(2, 1, 18 * 60, 60, WindowMode.TIMER, 20)),
        WindowMode.TIMER,
        60,
    )

    private suspend fun unlock() = state.update { it.copy(unlock = it.unlock.copy(unlockedSession = TimedSession.start(60_000L, clock))) }
    private suspend fun lock() = state.update { it.copy(unlock = it.unlock.copy(unlockedSession = null)) }

    private suspend fun assertNeedsCode(block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected CodeRequiredException, got $e", e is CodeRequiredException)
    }

    @Test fun everyScheduleChangeNeedsTheCode() = runTest {
        assertNeedsCode { schedules.create(school) }
        unlock()
        val id = schedules.create(school)
        lock()
        assertNeedsCode { schedules.update(school.copy(id = id, outsideMinutes = 30)) } // even stricter
        assertNeedsCode { schedules.delete(id) } // even unused
        assertEquals(1, scheduleStore.all().size)
    }

    @Test fun validationAndDeletingOnlyWhenUnused() = runTest {
        unlock()
        val id = schedules.create(school)
        val e = runCatching { schedules.create(school.copy(name = " school ")) }.exceptionOrNull() as InvalidScheduleException
        assertEquals(InvalidScheduleException.Reason.DUPLICATE_NAME, e.reason)
        val bad = school.copy(name = "Bad", windows = school.windows + ScheduleWindow(3, 1, 10 * 60, 60, WindowMode.FREE))
        assertEquals(InvalidScheduleException.Reason.INVALID_WINDOWS, (runCatching { schedules.create(bad) }.exceptionOrNull() as InvalidScheduleException).reason)

        rules.add(RuleType.APP, "com.instagram.android", scheduleId = id)
        val inUse = runCatching { schedules.delete(id) }.exceptionOrNull() as ScheduleInUseException
        assertEquals(listOf("com.instagram.android"), inUse.usedBy)
    }

    @Test fun exportAndImportKeepSchedulesAndFallBackForOldVersions() = runTest {
        unlock()
        val id = schedules.create(school)
        rules.add(RuleType.APP, "com.instagram.android", limitMinutesPerDay = 30, scheduleId = id)
        val groupId = rules.createGroup("Social", 45, scheduleId = id)
        rules.add(RuleType.APP, "com.reddit.frontpage", groupId = groupId)
        val backup = rules.exportBackup()
        val text = BackupCodec.encode(backup)

        // Older versions read the limit: null = always blocked (never less strict).
        assertNull(backup.rules.first { it.target == "com.instagram.android" }.limitMinutesPerDay)
        assertNull(backup.groups.single().limitMinutesPerDay)
        assertEquals("School", backup.groups.single().schedule)

        // A fresh phone.
        val ruleStore2 = FakeRuleStore()
        val groupStore2 = FakeGroupStore(ruleStore2)
        val scheduleStore2 = FakeScheduleStore()
        val rules2 = RuleRepository(ruleStore2, groupStore2, gate, schedules = scheduleStore2)
        rules2.importBackup(BackupCodec.decode(text))
        val imported = scheduleStore2.all().single()
        assertEquals(school.windows, imported.windows)
        assertEquals(imported.id, ruleStore2.all().first { it.target == "com.instagram.android" }.scheduleId)
        assertEquals(imported.id, groupStore2.all().single().scheduleId)

        // Importing schedules needs the code even with "only loosening".
        lock()
        val rules3 = RuleRepository(FakeRuleStore(), FakeGroupStore(FakeRuleStore()), gate, schedules = FakeScheduleStore())
        assertNeedsCode { rules3.importBackup(BackupCodec.decode(text)) }
    }

    @Test fun olderFilesWithoutSchedulesStillImport() = runTest {
        val old = """{"format":"lares-rules","rules":[{"type":"APP","target":"com.instagram.android","limitMinutesPerDay":20}]}"""
        val result = rules.importBackup(BackupCodec.decode(old))
        assertEquals(1, result.rulesAdded)
        assertNull(ruleStore.all().single().scheduleId)
    }

    @Test fun membersFollowTheGroupSoTheirOldScheduleCanBeDeleted() = runTest {
        unlock()
        val s1 = schedules.create(school)
        val s2 = schedules.create(school.copy(name = "Weekend"))
        val g = rules.createGroup("Social", null, scheduleId = s1)
        rules.add(RuleType.APP, "com.instagram.android", groupId = g) // keeps a copy of s1
        rules.updateGroup(groupStore.get(g)!!.copy(scheduleId = s2))
        schedules.delete(s1) // not "in use" by the member
        assertTrue(scheduleStore.get(s1) == null)
    }
}
