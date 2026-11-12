/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.TimedSession
import de.lares_app.testing.FakeClock
import de.lares_app.testing.FakeGroupStore
import de.lares_app.testing.FakeRuleStore
import de.lares_app.testing.FakeStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class RepositoriesTest {
    private val clock = FakeClock()
    private val zone = ZoneId.of("Europe/Berlin")
    private val store = FakeStateStore(AppState(unlock = UnlockState(method = UnlockMethod.PASSWORD, passwordHash = "x")))
    private val gate = UnlockGate(store, clock)
    private val ruleStore = FakeRuleStore()
    private val rules = RuleRepository(ruleStore, FakeGroupStore(ruleStore), gate)
    private val settings = SettingsRepository(store, gate, clock) { zone }

    private suspend fun unlockApp() {
        store.update { it.copy(unlock = it.unlock.copy(unlockedSession = TimedSession.start(60_000L, clock))) }
    }

    private suspend fun assertNeedsCode(block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected CodeRequiredException, got $e", e is CodeRequiredException)
    }

    @Test fun addingRulesIsFreeAndNormalized() = runTest {
        val added = rules.add(RuleType.WEBSITE, "https://www.Reddit.com/") as AddRuleResult.Added
        assertEquals("reddit.com", ruleStore.get(added.id)!!.target)
        assertTrue(rules.add(RuleType.WEBSITE, "reddit.com") is AddRuleResult.Duplicate)
        assertEquals(AddRuleResult.InvalidTarget, rules.add(RuleType.WEBSITE, "not a url"))
        assertEquals(AddRuleResult.InvalidTarget, rules.add(RuleType.APP, "notapackage"))
        assertTrue(rules.add(RuleType.APP, " com.instagram.android ") is AddRuleResult.Added)
    }

    @Test fun looseningRulesNeedsUnlockedMode() = runTest {
        val id = (rules.add(RuleType.APP, "com.instagram.android", limitMinutesPerDay = 30) as AddRuleResult.Added).id

        rules.setLimit(id, 10) // tighter: fine
        rules.setLimit(id, null) // always-on: fine
        assertNeedsCode { rules.setLimit(id, 60) }
        assertNeedsCode { rules.setEnabled(id, false) }
        assertNeedsCode { rules.remove(id) }
        assertEquals(1, ruleStore.all().size)

        unlockApp()
        rules.setLimit(id, 60)
        rules.remove(id)
        assertTrue(ruleStore.all().isEmpty())

        clock.advance(60_000L) // edit session over
        rules.add(RuleType.APP, "com.instagram.android")
    }

    @Test fun nothingIsProtectedBeforeSetup() = runTest {
        val fresh = FakeStateStore()
        val freshStore = FakeRuleStore()
        val freshRules = RuleRepository(freshStore, FakeGroupStore(freshStore), UnlockGate(fresh, clock))
        val id = (freshRules.add(RuleType.APP, "com.instagram.android") as AddRuleResult.Added).id
        freshRules.remove(id)
    }

    @Test fun settingsPolicy() = runTest {
        settings.setBlockingMode(BlockingMode.ACCESSIBILITY) // first choice: free
        assertNeedsCode { settings.setBlockingMode(BlockingMode.USAGE_STATS) }

        settings.setWebsiteBlocking(true)
        assertNeedsCode { settings.setWebsiteBlocking(false) }

        settings.setProtection(Protection.UNINSTALL, true)
        assertNeedsCode { settings.setProtection(Protection.UNINSTALL, false) }
        assertNeedsCode { settings.setProtection(Protection.RESTART_AFTER_REBOOT, false) }
        assertEquals(setOf(Protection.RESTART_AFTER_REBOOT, Protection.UNINSTALL), store.current().settings.protections)

        assertNeedsCode { settings.setResetTime(LocalTime.of(4, 0)) }

        unlockApp()
        settings.setBlockingMode(BlockingMode.USAGE_STATS)
        settings.setWebsiteBlocking(false)
        settings.setWebsiteBlocking(true) // ignored: website blocking needs Accessibility mode
        settings.setProtection(Protection.UNINSTALL, false)
        assertFalse(store.current().settings.websiteBlocking)
    }

    @Test fun resetTimeChangeIsPendingUntilNextReset() = runTest {
        clock.wall = LocalDateTime.parse("2026-11-11T21:00").atZone(zone).toInstant().toEpochMilli()
        unlockApp()
        settings.setResetTime(LocalTime.of(4, 0))
        val s = store.current().settings
        assertEquals(0, s.resetMinuteOfDay)
        assertEquals(4 * 60, s.pendingResetMinuteOfDay)
        assertEquals(LocalDateTime.parse("2026-11-12T00:00").atZone(zone).toInstant().toEpochMilli(), s.pendingResetEffectiveFromMillis)
        assertEquals(LocalTime.MIDNIGHT, s.resetSchedule.resetTime)
    }
}
