/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import de.lares_app.core.data.UnlockGate
import de.lares_app.core.data.RuleRepository
import de.lares_app.testing.FakeClock
import de.lares_app.testing.FakeGroupStore
import de.lares_app.testing.FakeRuleStore
import de.lares_app.testing.FakeStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleTemplatesTest {

    @Test fun allTargetsAreValidAndUnique() {
        val ids = RuleTemplates.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (template in RuleTemplates.all) {
            assertTrue(template.name, template.apps.isNotEmpty() && template.websites.isNotEmpty())
            for ((type, target) in template.targets) {
                val normalized = RuleRepository.normalizeTarget(type, target)
                assertNotNull("${template.id}: invalid $target", normalized)
                assertEquals("${template.id}: $target is not in normalized form", target, normalized)
            }
        }
    }

    @Test fun everyTemplateAppHasADisplayName() {
        val missing = RuleTemplates.all.flatMap { it.apps }.filterNot { it in RuleTemplates.knownAppNames }
        assertTrue("no display name for $missing", missing.isEmpty())
    }

    @Test fun brandColorLookup() {
        val youtubeRed = 0xFFFF0000
        assertEquals(youtubeRed, RuleTemplates.colorFor(RuleType.APP, "org.schabi.newpipe"))
        assertEquals(youtubeRed, RuleTemplates.colorFor(RuleType.WEBSITE, "youtube.com/shorts"))
        assertEquals(youtubeRed, RuleTemplates.colorFor(RuleType.WEBSITE, "m.youtube.com"))
        assertEquals(0xFF0A66C2, RuleTemplates.colorFor(RuleType.WEBSITE, "linkedin.com"))
        assertEquals(null, RuleTemplates.colorFor(RuleType.WEBSITE, "example.org"))
        assertEquals(null, RuleTemplates.colorFor(RuleType.APP, "org.example.app"))
    }

    @Test fun addingATemplateSkipsWhatIsAlreadyBlocked() = runTest {
        val store = FakeRuleStore()
        val repo = RuleRepository(store, FakeGroupStore(store), UnlockGate(FakeStateStore(), FakeClock()))
        val youtube = RuleTemplates.all.first { it.id == "youtube" }
        repo.add(RuleType.WEBSITE, "youtube.com")

        val added = repo.addAll(youtube.targets, limitMinutesPerDay = 30)
        assertEquals(youtube.targets.size - 1, added)
        assertEquals(youtube.targets.size, store.all().size)
        assertEquals(0, repo.addAll(youtube.targets, null))
    }
}
