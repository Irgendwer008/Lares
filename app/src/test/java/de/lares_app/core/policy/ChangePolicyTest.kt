/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import de.lares_app.core.policy.ChangeKind.LOOSENING
import de.lares_app.core.policy.ChangeKind.NONE
import de.lares_app.core.policy.ChangeKind.TIGHTENING
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class ChangePolicyTest {

    private val app = Rule(1, RuleType.APP, "com.instagram.android")
    private val limited = app.copy(limitMinutesPerDay = 30)

    @Test fun addingAndRemoving() {
        assertEquals(TIGHTENING, ChangePolicy.classify(null, app))
        assertEquals(NONE, ChangePolicy.classify(null, app.copy(enabled = false)))
        assertEquals(LOOSENING, ChangePolicy.classify(app, null))
        assertEquals(NONE, ChangePolicy.classify(app.copy(enabled = false), null))
    }

    @Test fun enablingAndDisabling() {
        assertEquals(LOOSENING, ChangePolicy.classify(app, app.copy(enabled = false)))
        assertEquals(TIGHTENING, ChangePolicy.classify(app.copy(enabled = false), app))
    }

    @Test fun limits() {
        assertEquals(TIGHTENING, ChangePolicy.classify(limited, limited.copy(limitMinutesPerDay = 10)))
        assertEquals(LOOSENING, ChangePolicy.classify(limited, limited.copy(limitMinutesPerDay = 60)))
        assertEquals(TIGHTENING, ChangePolicy.classify(limited, app))
        assertEquals(LOOSENING, ChangePolicy.classify(app, limited))
        assertEquals(NONE, ChangePolicy.classify(app.copy(limitMinutesPerDay = 0), app))
    }

    @Test fun retargetingIsLoosening() {
        assertEquals(LOOSENING, ChangePolicy.classify(app, app.copy(target = "com.zhiliaoapp.musically")))
        val web = Rule(2, RuleType.WEBSITE, "reddit.com")
        assertEquals(NONE, ChangePolicy.classify(web, web.copy(target = "https://www.reddit.com/")))
    }

    @Test fun togglesAndResetTime() {
        assertEquals(TIGHTENING, ChangePolicy.classifyToggle(false, true))
        assertEquals(LOOSENING, ChangePolicy.classifyToggle(true, false))
        assertEquals(LOOSENING, ChangePolicy.classifyResetTime(LocalTime.MIDNIGHT, LocalTime.of(4, 0)))
        assertEquals(LOOSENING, ChangePolicy.classifyResetTime(LocalTime.of(4, 0), LocalTime.MIDNIGHT))
        assertEquals(NONE, ChangePolicy.classifyResetTime(LocalTime.NOON, LocalTime.NOON))
    }
}
