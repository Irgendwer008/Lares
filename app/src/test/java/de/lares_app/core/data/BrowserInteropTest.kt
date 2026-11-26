/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.WindowMode
import de.lares_app.testing.FakeClock
import de.lares_app.testing.FakeGroupStore
import de.lares_app.testing.FakeRuleStore
import de.lares_app.testing.FakeScheduleStore
import de.lares_app.testing.FakeStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app and the browser extension (Lares Browser Edition) must always read each other's rules
 * files. `browser-export-1.2.json` is in the exact shape the extension's `BackupCodec.encode`
 * writes (field order, 4-space indent, defaults and nulls written out); the extension has the same
 * test the other way round (its `tests/fixtures/android-export-1.2*.json`).
 */
class BrowserInteropTest {
    private val fixture = requireNotNull(javaClass.classLoader?.getResource("browser-export-1.2.json")).readText()

    // Before the first setup nothing is protected, so the import needs no code.
    private val gate = UnlockGate(FakeStateStore(), FakeClock())
    private val ruleStore = FakeRuleStore()
    private val groupStore = FakeGroupStore(ruleStore)
    private val scheduleStore = FakeScheduleStore()
    private val rules = RuleRepository(ruleStore, groupStore, gate, schedules = scheduleStore)

    @Test fun importsAnExtensionExport() = runTest {
        val result = rules.importBackup(BackupCodec.decode(fixture))
        assertEquals(2, result.groupsAdded)
        assertEquals(5, result.rulesAdded)
        assertEquals(0, result.skipped)

        val schedule = scheduleStore.all().single()
        assertEquals("School days", schedule.name)
        assertEquals(WindowMode.TIMER, schedule.outside)
        assertEquals(60, schedule.outsideMinutes)
        assertEquals(5, schedule.windows.size)
        assertTrue(schedule.isValid())
        assertEquals(setOf(2, 3), schedule.windows.filter { it.shareGroup == 1 }.map { it.id }.toSet())
        assertEquals(1320 + 540, schedule.windows.single { it.id == 4 }.endMinute) // Fri night into Saturday

        val groups = groupStore.all().associateBy { it.name }
        assertEquals(schedule.id, groups.getValue("Social").scheduleId)
        assertNull(groups.getValue("Social").limitMinutesPerDay)
        assertEquals(45, groups.getValue("Video").limitMinutesPerDay)
        assertFalse(groups.getValue("Video").enabled)

        val byTarget = ruleStore.all().associateBy { it.target }
        assertEquals(schedule.id, byTarget.getValue("youtube.com/shorts").scheduleId)
        assertEquals(20, byTarget.getValue("news.example").limitMinutesPerDay)
        assertEquals(RuleType.APP, byTarget.getValue("com.instagram.android").type)
        assertEquals(groups.getValue("Social").id, byTarget.getValue("reddit.com").groupId)
    }

    @Test fun reExportKeepsEverythingTheExtensionReads() = runTest {
        rules.importBackup(BackupCodec.decode(fixture))
        val again = BackupCodec.decode(BackupCodec.encode(rules.exportBackup()))
        val original = BackupCodec.decode(fixture)
        assertEquals(original.schedules, again.schedules)
        assertEquals(original.groups.sortedBy { it.name }, again.groups.sortedBy { it.name })
        assertEquals(original.rules.sortedBy { it.target }, again.rules.sortedBy { it.target })
        assertNull(again.guardianTotp)
    }
}
