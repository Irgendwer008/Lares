/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.BlockReason
import de.lares_app.core.policy.ChangeKind
import de.lares_app.core.policy.ChangePolicy
import de.lares_app.core.policy.Decision
import de.lares_app.core.policy.PolicyEngine
import de.lares_app.core.policy.PolicySnapshot
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.policy.RuleTemplates
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.TimedSession
import de.lares_app.testing.FakeClock
import de.lares_app.testing.FakeGroupStore
import de.lares_app.testing.FakeRuleStore
import de.lares_app.testing.FakeStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {

    // ---- Engine: shared time, switch ------------------------------------------------------------

    private val social = RuleGroup(10, "Social", limitMinutesPerDay = 30)
    private val ytApp = Rule(1, RuleType.APP, "com.google.android.youtube", groupId = 10)
    private val ytWeb = Rule(2, RuleType.WEBSITE, "youtube.com", groupId = 10)
    private val chrome = "com.android.chrome"
    private val snapshot = PolicySnapshot(
        rules = listOf(ytApp, ytWeb),
        groups = mapOf(10L to social),
        websiteBlockingEnabled = true,
        supportedBrowsers = setOf(chrome),
        installedBrowsers = setOf(chrome),
    )

    @Test fun membersShareTheGroupLimit() {
        assertEquals(Decision.Allow(remainingMillis = 30 * 60_000L, countedGroupIds = setOf(10L)), PolicyEngine.evaluate(ytApp.target, null, snapshot))

        val used = snapshot.copy(groupUsedMillis = mapOf(10L to 30 * 60_000L))
        assertEquals(Decision.Block(BlockReason.LIMIT_REACHED, 1, 10), PolicyEngine.evaluate(ytApp.target, null, used))
        // Time used in the app also blocks the website.
        assertEquals(Decision.Block(BlockReason.LIMIT_REACHED, 2, 10), PolicyEngine.evaluate(chrome, "https://youtube.com/watch?v=1", used))
    }

    @Test fun groupLimitOverridesMemberLimit() {
        val s = snapshot.copy(rules = listOf(ytApp.copy(limitMinutesPerDay = 999)), groups = mapOf(10L to social.copy(limitMinutesPerDay = null)))
        assertEquals(Decision.Block(BlockReason.ALWAYS_ON, 1, 10), PolicyEngine.evaluate(ytApp.target, null, s))
    }

    @Test fun groupSwitchTurnsAllMembersOff() {
        val off = snapshot.copy(groups = mapOf(10L to social.copy(enabled = false)), groupUsedMillis = mapOf(10L to 999 * 60_000L))
        assertEquals(Decision.Allow(), PolicyEngine.evaluate(ytApp.target, null, off))
        assertEquals(Decision.Allow(), PolicyEngine.evaluate(chrome, "youtube.com", off))
    }

    // ---- Policy: what needs the code ------------------------------------------------------------

    @Test fun membershipChanges() {
        val groups = mapOf(10L to social)
        val standalone = ytApp.copy(groupId = null, limitMinutesPerDay = 60)
        assertEquals(ChangeKind.TIGHTENING, ChangePolicy.classifyInGroups(standalone, standalone.copy(groupId = 10), groups)) // 60 → 30
        assertEquals(ChangeKind.LOOSENING, ChangePolicy.classifyInGroups(standalone.copy(limitMinutesPerDay = null), standalone.copy(limitMinutesPerDay = null, groupId = 10), groups))
        // Taking a member out ends the time sharing → loosening even with the same limit …
        assertEquals(ChangeKind.LOOSENING, ChangePolicy.classifyInGroups(ytApp, ytApp.copy(groupId = null, limitMinutesPerDay = 30), groups))
        // … unless the new own limit is stricter.
        assertEquals(ChangeKind.TIGHTENING, ChangePolicy.classifyInGroups(ytApp, ytApp.copy(groupId = null, limitMinutesPerDay = 10), groups))
        // Members of a disabled group restrict nothing.
        assertEquals(ChangeKind.NONE, ChangePolicy.classifyInGroups(ytApp, null, mapOf(10L to social.copy(enabled = false))))
    }

    @Test fun groupChanges() {
        assertEquals(ChangeKind.LOOSENING, ChangePolicy.classifyGroup(social, social.copy(enabled = false), hasActiveMembers = true))
        assertEquals(ChangeKind.TIGHTENING, ChangePolicy.classifyGroup(social.copy(enabled = false), social, hasActiveMembers = true))
        assertEquals(ChangeKind.LOOSENING, ChangePolicy.classifyGroup(social, social.copy(limitMinutesPerDay = 60), hasActiveMembers = true))
        assertEquals(ChangeKind.TIGHTENING, ChangePolicy.classifyGroup(social, social.copy(limitMinutesPerDay = null), hasActiveMembers = true))
        assertEquals(ChangeKind.LOOSENING, ChangePolicy.classifyGroup(social, null, hasActiveMembers = true))
        assertEquals(ChangeKind.NONE, ChangePolicy.classifyGroup(social, null, hasActiveMembers = false))
    }

    // ---- Repository -----------------------------------------------------------------------------

    private val clock = FakeClock()
    private val state = FakeStateStore(AppState(unlock = UnlockState(method = UnlockMethod.PASSWORD, passwordHash = "x")))
    private val ruleStore = FakeRuleStore()
    private val groupStore = FakeGroupStore(ruleStore)
    private val repo = RuleRepository(ruleStore, groupStore, UnlockGate(state, clock))

    private suspend fun assertNeedsCode(block: suspend () -> Unit) =
        assertTrue(runCatching { block() }.exceptionOrNull() is CodeRequiredException)

    @Test fun groupLifecycleFollowsThePolicy() = runTest {
        val id = repo.createGroup("Social", 30) // free
        repo.add(RuleType.APP, "com.instagram.android", groupId = id) // free
        val member = ruleStore.all().single()
        assertEquals(id, member.groupId)
        assertEquals(30, member.limitMinutesPerDay) // remembers the group limit

        repo.updateGroup(groupStore.get(id)!!.copy(name = "Social media", limitMinutesPerDay = 15)) // rename + tighter: free
        assertNeedsCode { repo.updateGroup(groupStore.get(id)!!.copy(limitMinutesPerDay = 60)) }
        assertNeedsCode { repo.setGroupEnabled(id, false) }
        assertNeedsCode { repo.setGroup(member.id, null) }
        assertNeedsCode { repo.remove(member.id) }
        assertNeedsCode { repo.deleteGroup(id) }

        state.update { it.copy(unlock = it.unlock.copy(unlockedSession = TimedSession.start(60_000L, clock))) }
        repo.setGroupEnabled(id, false)
        repo.deleteGroup(id)
        assertTrue(ruleStore.all().isEmpty())
        assertTrue(groupStore.all().isEmpty())
    }

    @Test fun singleRuleAndGroupMemberCoexistAndBothLimitsApply() = runTest {
        repo.add(RuleType.APP, "com.google.android.youtube", limitMinutesPerDay = 60)
        val youtube = RuleTemplates.all.first { it.id == "youtube" }
        val id = repo.createGroupWith(youtube.name, 30, youtube.targets)
        // The single rule stays, and the group gets its own member for the same app.
        assertEquals(youtube.targets.size, ruleStore.all().count { it.groupId == id })
        assertEquals(1, ruleStore.all().count { it.groupId == null })
        // Within the same scope it's still a duplicate.
        assertTrue(repo.add(RuleType.APP, "com.google.android.youtube", groupId = id) is AddRuleResult.Duplicate)
        assertTrue(repo.add(RuleType.APP, "com.google.android.youtube", 10) is AddRuleResult.Duplicate)

        val snapshot = PolicySnapshot(ruleStore.all(), groups = groupStore.all().associateBy { it.id })
        val single = ruleStore.all().first { it.groupId == null }
        // Both are counted …
        assertEquals(
            Decision.Allow(setOf(single.id), 30 * 60_000L, setOf(id)),
            PolicyEngine.evaluate("com.google.android.youtube", null, snapshot),
        )
        // … and the group's 30 shared minutes block even though the single rule allows 60.
        val used = snapshot.copy(groupUsedMillis = mapOf(id to 30 * 60_000L), usedMillis = mapOf(single.id to 30 * 60_000L))
        assertEquals(BlockReason.LIMIT_REACHED, (PolicyEngine.evaluate("com.google.android.youtube", null, used) as Decision.Block).reason)
    }

    @Test fun templateAsGroup() = runTest {
        val tiktok = RuleTemplates.all.first { it.id == "tiktok" }
        val id = repo.createGroupWith(tiktok.name, 20, tiktok.targets)
        assertEquals("TikTok", groupStore.get(id)!!.name)
        assertEquals(tiktok.targets.size, ruleStore.all().count { it.groupId == id })
    }
}
