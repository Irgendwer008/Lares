/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

import de.lares_app.core.data.CodeRequiredException
import de.lares_app.core.data.TamperEventType
import de.lares_app.core.data.UnlockMethod
import de.lares_app.testing.FakeCipher
import de.lares_app.testing.FakeClock
import de.lares_app.testing.FakeStateStore
import de.lares_app.testing.FakeTamperLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockManagerTest {
    private val clock = FakeClock()
    private val store = FakeStateStore()
    private val tamper = FakeTamperLog()
    private val manager = UnlockManager(store, clock, FakeCipher(), tamper, PasswordHasher(iterations = 1_000))

    private suspend fun success(code: String) = manager.verify(code) as VerifyResult.Success

    @Test fun notSetUpUntilConfigured() = runTest {
        assertEquals(VerifyResult.NotSetUp, manager.verify("anything"))
    }

    @Test fun passwordSetupAndVerify() = runTest {
        val recovery = manager.setupPassword("hunter22")
        assertEquals(UnlockMethod.PASSWORD, store.current().unlock.method)
        assertNotNull(RecoveryCode.normalize(recovery))
        assertFalse(success("hunter22").viaRecoveryCode)
        assertTrue(manager.verify("hunter23") is VerifyResult.Wrong)
    }

    @Test fun changingMethodNeedsEditSession() = runTest {
        manager.setupPassword("hunter22")
        assertFalse(manager.canChangeMethod())
        runCatching { manager.setupPassword("other1") }.let { assertTrue(it.exceptionOrNull() is CodeRequiredException) }

        manager.startEditSession(success("hunter22").grant, 10 * 60_000L)
        manager.setupPassword("other1")
        assertTrue(manager.verify("other1") is VerifyResult.Success)
    }

    @Test fun backoffAfterRepeatedFailures() = runTest {
        manager.setupPassword("hunter22")
        repeat(4) { assertEquals(0L, (manager.verify("nope") as VerifyResult.Wrong).lockoutMillis) }
        assertEquals(60_000L, (manager.verify("nope") as VerifyResult.Wrong).lockoutMillis)
        assertEquals(listOf(TamperEventType.REPEATED_WRONG_CODE), tamper.recorded)

        // Even the correct code is refused while locked out.
        assertTrue(manager.verify("hunter22") is VerifyResult.LockedOut)
        // A reboot doesn't reset the lockout (wall-clock fallback).
        clock.boot = 2
        clock.elapsed = 0
        assertTrue(manager.verify("hunter22") is VerifyResult.LockedOut)

        clock.advance(60_000L)
        assertTrue(manager.verify("hunter22") is VerifyResult.Success)
        assertEquals(0, store.current().unlock.failedAttempts)
    }

    @Test fun recoveryCodeUnlocksAndForcesReSetup() = runTest {
        val recovery = manager.setupPassword("hunter22")
        val result = success(recovery.lowercase())
        assertTrue(result.viaRecoveryCode)
        assertTrue(store.current().unlock.reSetupRequired)
        assertEquals(listOf(TamperEventType.RECOVERY_CODE_USED), tamper.recorded)
        assertTrue(manager.canChangeMethod())

        val newRecovery = manager.setupPassword("fresh-pass")
        assertFalse(store.current().unlock.reSetupRequired)
        // The old recovery code is no longer valid.
        assertTrue(manager.verify(recovery) is VerifyResult.Wrong)
        assertTrue(success(newRecovery).viaRecoveryCode)
    }

    @Test fun totpSetupNeedsConfirmationAndRejectsReplay() = runTest {
        val setup = manager.beginTotpSetup("Phone")
        assertTrue(setup.provisioningUri.startsWith("otpauth://totp/Lares:Phone?secret=" + setup.secretBase32))
        val secret = Base32.decode(setup.secretBase32)

        assertNull(manager.confirmTotpSetup(setup, "000000".takeIf { it != Totp.code(secret, Totp.counterAt(clock.wall)) } ?: "111111"))
        assertFalse(store.current().unlock.isSetUp)

        val setupCode = Totp.code(secret, Totp.counterAt(clock.wall))
        assertNotNull(manager.confirmTotpSetup(setup, setupCode))
        assertEquals(UnlockMethod.TOTP, store.current().unlock.method)

        // The code used for setup can't be reused.
        assertTrue(manager.verify(setupCode) is VerifyResult.Wrong)
        clock.advance(30_000L)
        val next = Totp.code(secret, Totp.counterAt(clock.wall))
        assertTrue(manager.verify(next) is VerifyResult.Success)
        assertTrue(manager.verify(next) is VerifyResult.Wrong) // replay
    }

    @Test fun pauseAndEditSessionNeedFreshGrant() = runTest {
        manager.setupPassword("hunter22")
        val grant = success("hunter22").grant
        manager.startPause(grant, 15 * 60_000L)
        assertTrue(manager.isPauseActive())

        // A grant works only once.
        assertTrue(runCatching { manager.startEditSession(grant, 60_000L) }.isFailure)

        clock.advance(15 * 60_000L)
        assertFalse(manager.isPauseActive())

        // A grant expires.
        val old = success("hunter22").grant
        clock.advance(UnlockManager.GRANT_VALIDITY_MILLIS + 1)
        assertTrue(runCatching { manager.startPause(old, 60_000L) }.isFailure)
    }

    @Test fun endingEarlyIsFree() = runTest {
        manager.setupPassword("hunter22")
        manager.startPause(success("hunter22").grant, 60 * 60_000L)
        manager.endPause()
        assertFalse(manager.isPauseActive())
    }
}
