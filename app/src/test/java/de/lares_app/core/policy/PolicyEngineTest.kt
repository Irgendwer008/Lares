/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyEngineTest {

    private val instagram = Rule(1, RuleType.APP, "com.instagram.android")
    private val youtubeLimited = Rule(2, RuleType.APP, "com.google.android.youtube", limitMinutesPerDay = 30)
    private val reddit = Rule(3, RuleType.WEBSITE, "reddit.com")
    private val shortsLimited = Rule(4, RuleType.WEBSITE, "youtube.com/shorts", limitMinutesPerDay = 10)
    private val chrome = "com.android.chrome"
    private val unsupported = "org.example.browser"

    private val base = PolicySnapshot(
        rules = listOf(instagram, youtubeLimited, reddit, shortsLimited),
        websiteBlockingEnabled = true,
        supportedBrowsers = setOf(chrome),
        installedBrowsers = setOf(chrome, unsupported),
        exemptPackages = setOf("de.lares_app"),
    )

    private fun eval(pkg: String, url: String? = null, s: PolicySnapshot = base) = PolicyEngine.evaluate(pkg, url, s)

    @Test fun alwaysOnAppIsBlocked() {
        assertEquals(Decision.Block(BlockReason.ALWAYS_ON, 1), eval(instagram.target))
    }

    @Test fun unrelatedAppIsAllowedWithoutCounting() {
        assertEquals(Decision.Allow(), eval("org.example.notes"))
    }

    @Test fun limitedAppCountsUntilLimitReached() {
        assertEquals(Decision.Allow(setOf(2L), 30 * 60_000L), eval(youtubeLimited.target))
        val used = base.copy(usedMillis = mapOf(2L to 29 * 60_000L))
        assertEquals(Decision.Allow(setOf(2L), 60_000L), eval(youtubeLimited.target, s = used))
        val exhausted = base.copy(usedMillis = mapOf(2L to 30 * 60_000L))
        assertEquals(Decision.Block(BlockReason.LIMIT_REACHED, 2), eval(youtubeLimited.target, s = exhausted))
    }

    @Test fun pauseAllowsEverything() {
        val paused = base.copy(pauseActive = true)
        assertEquals(Decision.Allow(), eval(instagram.target, s = paused))
        assertEquals(Decision.Allow(), eval(unsupported, s = paused))
    }

    @Test fun exemptPackagesAreNeverBlocked() {
        val s = base.copy(rules = base.rules + Rule(9, RuleType.APP, "de.lares_app"))
        assertEquals(Decision.Allow(), eval("de.lares_app", s = s))
    }

    @Test fun disabledRulesAreIgnored() {
        val s = base.copy(rules = listOf(instagram.copy(enabled = false)))
        assertEquals(Decision.Allow(), eval(instagram.target, s = s))
    }

    @Test fun websiteRulesApplyInSupportedBrowsers() {
        assertEquals(Decision.Block(BlockReason.ALWAYS_ON, 3), eval(chrome, "https://old.reddit.com/r/all"))
        assertEquals(Decision.Allow(setOf(4L), 10 * 60_000L), eval(chrome, "youtube.com/shorts/abc"))
        assertEquals(Decision.Allow(), eval(chrome, "youtube.com/watch?v=1"))
        assertEquals(Decision.Allow(), eval(chrome, null))
    }

    @Test fun websiteRulesIgnoredWhenWebsiteBlockingDisabled() {
        val s = base.copy(websiteBlockingEnabled = false)
        assertEquals(Decision.Allow(), eval(chrome, "reddit.com", s))
        assertEquals(Decision.Allow(), eval(unsupported, s = s))
    }

    @Test fun unsupportedBrowsersAreBlockedWhileWebsiteBlockingIsOn() {
        assertEquals(Decision.Block(BlockReason.UNSUPPORTED_BROWSER), eval(unsupported))
    }

    @Test fun alwaysOnWinsOverLimit() {
        val s = base.copy(rules = listOf(youtubeLimited, youtubeLimited.copy(id = 7, limitMinutesPerDay = null)))
        assertEquals(Decision.Block(BlockReason.ALWAYS_ON, 7), eval(youtubeLimited.target, s = s))
    }

    @Test fun smallestRemainingTimeIsReported() {
        val appRuleForChrome = Rule(8, RuleType.APP, chrome, limitMinutesPerDay = 60)
        val s = base.copy(rules = base.rules + appRuleForChrome, usedMillis = mapOf(4L to 5 * 60_000L))
        assertEquals(Decision.Allow(setOf(4L, 8L), 5 * 60_000L), eval(chrome, "youtube.com/shorts", s))
    }
}
