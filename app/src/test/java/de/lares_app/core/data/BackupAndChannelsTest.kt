/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.alerts.AlertChannelRepository
import de.lares_app.core.alerts.ChannelDraft
import de.lares_app.core.alerts.ChannelType
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.TimedSession
import de.lares_app.testing.FakeCipher
import de.lares_app.testing.FakeClock
import de.lares_app.testing.FakeGroupStore
import de.lares_app.testing.FakeRuleStore
import de.lares_app.testing.FakeStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAndChannelsTest {
    private val clock = FakeClock()
    private val state = FakeStateStore(AppState(unlock = UnlockState(method = UnlockMethod.PASSWORD, passwordHash = "x"), settings = AppSettings(setupComplete = true)))
    private val gate = UnlockGate(state, clock)

    private fun newRepo(): Pair<RuleRepository, FakeRuleStore> {
        val store = FakeRuleStore()
        return RuleRepository(store, FakeGroupStore(store), gate) to store
    }

    private suspend fun assertNeedsCode(block: suspend () -> Unit) =
        assertTrue(runCatching { block() }.exceptionOrNull() is CodeRequiredException)

    // ---- Backup ---------------------------------------------------------------------------------

    @Test fun exportImportRoundTrip() = runTest {
        val (source, _) = newRepo()
        val g = source.createGroup("Social", 30)
        source.add(RuleType.APP, "com.instagram.android", groupId = g)
        source.add(RuleType.WEBSITE, "reddit.com", 15)
        val json = BackupCodec.encode(source.exportBackup())
        assertFalse("no secrets in the file", json.contains("passwordHash") || json.contains("secret"))

        val (target, targetStore) = newRepo()
        val result = target.importBackup(BackupCodec.decode(json))
        assertEquals(ImportResult(groupsAdded = 1, rulesAdded = 2, skipped = 0), result)
        assertEquals(setOf("com.instagram.android", "reddit.com"), targetStore.all().map { it.target }.toSet())
        assertEquals(2, targetStore.all().size)
    }

    @Test fun importOnlyAddsAndNeverLoosens() = runTest {
        val (repo, store) = newRepo()
        val existingGroup = repo.createGroup("Social", 10)
        repo.add(RuleType.WEBSITE, "reddit.com") // always blocked
        val backup = BackupFile(
            groups = listOf(BackupGroup("Social", limitMinutesPerDay = 999)),
            rules = listOf(
                BackupRule(RuleType.WEBSITE, "reddit.com", limitMinutesPerDay = 999), // already there → skipped
                BackupRule(RuleType.APP, "com.instagram.android", group = "Social"),
                BackupRule(RuleType.WEBSITE, "not a url"),
            ),
        )
        assertEquals(ImportResult(groupsAdded = 0, rulesAdded = 1, skipped = 2), repo.importBackup(backup))
        assertEquals(null, store.all().first { it.target == "reddit.com" }.limitMinutesPerDay) // still always blocked
        assertEquals(existingGroup, store.all().first { it.target == "com.instagram.android" }.groupId)
    }

    @Test fun rejectsForeignFiles() {
        assertTrue(runCatching { BackupCodec.decode("""{"format":"something-else"}""") }.isFailure)
        assertTrue(runCatching { BackupCodec.decode("hello") }.isFailure)
    }

    // ---- Notification channels ------------------------------------------------------------------

    @Test fun channelValidation() {
        val token = "123456789:AAFabcdefghijklmnopqrstuvwxyz_-0"
        assertTrue(ChannelDraft(ChannelType.TELEGRAM, secret = token, address = "42").isComplete)
        assertTrue(ChannelDraft(ChannelType.TELEGRAM, secret = token, address = "-1001234567890").isComplete) // group chat
        assertFalse(ChannelDraft(ChannelType.TELEGRAM, secret = token).isComplete)
        assertFalse(ChannelDraft(ChannelType.TELEGRAM, secret = "not a token", address = "42").isComplete)
        assertFalse(ChannelDraft(ChannelType.TELEGRAM, secret = token, address = "@someone").isComplete)
        assertTrue(ChannelDraft.empty(ChannelType.NTFY, "en").copy(address = "lares-xyz").isComplete)
        assertFalse(ChannelDraft(ChannelType.NTFY, server = "http://ntfy.sh", address = "t").isComplete) // no plain HTTP
        assertTrue(ChannelDraft(ChannelType.WEBHOOK, server = "https://example.org/hook").isComplete)
        assertTrue(ChannelDraft(ChannelType.MATRIX, server = "https://matrix.org", address = "!room:matrix.org", secret = "tok").isComplete)
        assertFalse(ChannelDraft(ChannelType.MATRIX, server = "https://matrix.org", address = "#alias:matrix.org", secret = "tok").isComplete)
    }

    @Test fun addingIsFreeChangingNeedsCodeAndTokensAreEncrypted() = runTest {
        val repo = AlertChannelRepository(state, gate, FakeCipher())
        val draft = ChannelDraft(ChannelType.TELEGRAM, "de", address = " 42 ", secret = "123456789:AAFsecretsecretsecretsecret")
        val id = repo.add(draft)
        val stored = repo.get(id)!!
        assertEquals("42", stored.address)
        assertNotEquals("123456789:AAFsecretsecretsecretsecret", stored.secretEncrypted)
        assertEquals("123456789:AAFsecretsecretsecretsecret", repo.draftOf(stored).secret)

        assertNeedsCode { repo.update(id, draft.copy(address = "43")) }
        assertNeedsCode { repo.remove(id) }

        state.update { it.copy(unlock = it.unlock.copy(unlockedSession = TimedSession.start(60_000L, clock))) }
        repo.update(id, draft.copy(address = "43"))
        assertEquals("43", repo.get(id)!!.address)
        repo.remove(id)
        assertTrue(state.current().settings.alertChannels.isEmpty())
    }
}
