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

    /** First setup including confirming the recovery code, then locked (ends the first unlocked mode). */
    private suspend fun setUp(password: String = "hunter22"): String =
        manager.setupPassword(password).also {
            manager.completeSetup()
            manager.lock()
        }

    private suspend fun assertUnlocks(code: String, viaRecovery: Boolean = false) {
        assertEquals(VerifyResult.Success(viaRecovery), manager.unlock(code))
        assertTrue(manager.isUnlocked())
    }

    @Test fun notSetUpUntilConfigured() = runTest {
        assertEquals(VerifyResult.NotSetUp, manager.unlock("anything"))
    }

    @Test fun correctCodeUnlocksForTenMinutes() = runTest {
        setUp()
        assertFalse(manager.isUnlocked())
        assertTrue(manager.unlock("hunter23") is VerifyResult.Wrong)
        assertFalse(manager.isUnlocked())

        assertUnlocks("hunter22")
        clock.advance(UnlockManager.UNLOCKED_DURATION_MILLIS - 1)
        assertTrue(manager.isUnlocked())
        clock.advance(1)
        assertFalse(manager.isUnlocked())
    }

    @Test fun interruptedFirstSetupCanBeRepeated() = runTest {
        manager.setupPassword("first1") // app closed before the recovery code was confirmed
        assertFalse(store.current().settings.setupComplete)
        assertTrue(manager.canChangeMethod())
        manager.setupPassword("second2")
        manager.completeSetup()
        assertTrue(store.current().settings.setupComplete)
        manager.lock()
        assertFalse(manager.canChangeMethod())
        assertTrue(manager.unlock("second2") is VerifyResult.Success)
    }

    @Test fun firstSetupEndsInUnlockedMode() = runTest {
        manager.setupPassword("hunter22")
        manager.completeSetup()
        assertTrue(manager.isUnlocked())
        clock.advance(UnlockManager.UNLOCKED_DURATION_MILLIS)
        assertFalse(manager.isUnlocked())
        // Changing the code later doesn't start a new unlocked mode by itself …
        assertUnlocks("hunter22")
        clock.advance(UnlockManager.UNLOCKED_DURATION_MILLIS - 1_000)
        manager.setupPassword("changed1")
        manager.completeSetup()
        clock.advance(1_000)
        assertFalse(manager.isUnlocked())
        // … and finishing it after the unlocked mode ran out needs the code again.
        manager.unlock("changed1")
        manager.setupPassword("changed2")
        clock.advance(UnlockManager.UNLOCKED_DURATION_MILLIS)
        try {
            manager.completeSetup()
            throw AssertionError("expected CodeRequiredException")
        } catch (_: CodeRequiredException) {
        }
    }

    @Test fun lockEndsUnlockedModeImmediately() = runTest {
        setUp()
        assertUnlocks("hunter22")
        manager.lock()
        assertFalse(manager.isUnlocked())
    }

    @Test fun changingMethodNeedsUnlockedMode() = runTest {
        setUp()
        assertFalse(manager.canChangeMethod())
        assertTrue(runCatching { manager.setupPassword("other1") }.exceptionOrNull() is CodeRequiredException)

        assertUnlocks("hunter22")
        manager.setupPassword("other1")
        assertTrue(manager.unlock("other1") is VerifyResult.Success)
    }

    @Test fun backoffAfterRepeatedFailures() = runTest {
        setUp()
        assertEquals(VerifyResult.Wrong(attemptsLeft = 2, lockoutMillis = 0), manager.unlock("nope"))
        assertEquals(VerifyResult.Wrong(attemptsLeft = 1, lockoutMillis = 0), manager.unlock("nope"))
        // The 3rd wrong code starts the waiting time.
        assertEquals(VerifyResult.Wrong(attemptsLeft = 0, lockoutMillis = 60_000L), manager.unlock("nope"))
        assertEquals(listOf(TamperEventType.REPEATED_WRONG_CODE), tamper.recorded)

        // Even the correct code is refused while locked out.
        assertTrue(manager.unlock("hunter22") is VerifyResult.LockedOut)
        // A reboot doesn't skip the waiting time – it starts over in full.
        clock.advance(50_000L)
        clock.boot = 2
        clock.elapsed = 0
        assertEquals(VerifyResult.LockedOut(60_000L), manager.unlock("hunter22"))

        clock.advance(60_000L)
        assertUnlocks("hunter22")
        assertEquals(0, store.current().unlock.failedAttempts)
    }

    @Test fun unlockedModeAndPauseEndOnReboot() = runTest {
        setUp()
        assertUnlocks("hunter22")
        manager.startPause(60 * 60_000L)
        clock.boot = 2
        clock.elapsed = 0
        clock.wall -= 3_600_000L // setting the clock back after the reboot doesn't help either
        assertFalse(manager.isUnlocked())
        assertFalse(manager.isPauseActive())
    }

    @Test fun reSetupAfterRecoveryNeedsUnlockedMode() = runTest {
        val recovery = setUp()
        assertUnlocks(recovery, viaRecovery = true)
        assertTrue(manager.canChangeMethod())
        clock.advance(UnlockManager.UNLOCKED_DURATION_MILLIS) // guardian walked away
        assertFalse(manager.canChangeMethod())
        assertTrue(runCatching { manager.setupPassword("owner1") }.exceptionOrNull() is CodeRequiredException)
    }

    @Test fun recoveryCodeUnlocksAndForcesReSetup() = runTest {
        val recovery = setUp()
        assertTrue(store.current().unlock.recoveryHash!!.startsWith("sha256$")) // fast hash, see UnlockManager
        assertUnlocks(recovery.lowercase(), viaRecovery = true)
        assertTrue(store.current().unlock.reSetupRequired)
        assertEquals(listOf(TamperEventType.RECOVERY_CODE_USED), tamper.recorded)
        assertTrue(manager.canChangeMethod())

        val newRecovery = manager.setupPassword("fresh-pass")
        // Stays in re-setup until the new recovery code has been shown and confirmed.
        assertTrue(store.current().unlock.reSetupRequired)
        manager.completeSetup()
        assertFalse(store.current().unlock.reSetupRequired)
        // The old recovery code is no longer valid.
        assertTrue(manager.unlock(recovery) is VerifyResult.Wrong)
        assertUnlocks(newRecovery, viaRecovery = true)
    }

    @Test fun totpSetupNeedsConfirmationAndRejectsReplay() = runTest {
        val setup = manager.beginTotpSetup("Phone")
        assertTrue(setup.provisioningUri.startsWith("otpauth://totp/Lares:Phone?secret=" + setup.secretBase32))
        val secret = Base32.decode(setup.secretBase32)
        val current = Totp.code(secret, Totp.counterAt(clock.wall))

        assertNull(manager.confirmTotpSetup(setup, if (current == "000000") "111111" else "000000"))
        assertFalse(store.current().unlock.isSetUp)

        assertNotNull(manager.confirmTotpSetup(setup, current))
        assertEquals(UnlockMethod.TOTP, store.current().unlock.method)

        // The code used for setup can't be reused.
        assertTrue(manager.unlock(current) is VerifyResult.Wrong)
        clock.advance(30_000L)
        val next = Totp.code(secret, Totp.counterAt(clock.wall))
        assertUnlocks(next)
        manager.lock()
        assertTrue(manager.unlock(next) is VerifyResult.Wrong) // replay
    }

    @Test fun pauseOnlyWhileUnlockedAndOutlivesLocking() = runTest {
        setUp()
        assertTrue(runCatching { manager.startPause(60_000L) }.exceptionOrNull() is CodeRequiredException)

        assertUnlocks("hunter22")
        manager.startPause(60 * 60_000L)
        manager.lock()
        assertTrue(manager.isPauseActive()) // the pause keeps running after locking

        manager.endPause() // ending early is always allowed
        assertFalse(manager.isPauseActive())
    }

    @Test fun expiredLockoutIsForgottenSoARebootDoesNotRestartIt() = runTest {
        setUp()
        repeat(3) { manager.unlock("wrong-code") }
        assertTrue(manager.unlock("hunter22") is VerifyResult.LockedOut)
        clock.advance(24 * 3_600_000L) // ran out
        manager.clearExpiredLockout() // the blocking loop notices it in this boot
        clock.boot++
        clock.elapsed = 1_000L
        assertTrue(manager.unlock("hunter22") is VerifyResult.Success)
    }

    @Test fun totpCodeCantBeReplayedBySettingTheClockBack() = runTest {
        val setup = manager.beginTotpSetup("Phone")
        val secret = Base32.decode(setup.secretBase32)
        manager.confirmTotpSetup(setup, Totp.code(secret, Totp.counterAt(clock.wall)))
        manager.completeSetup()
        manager.lock()
        // A code from now, saved but not used …
        val saved = Totp.code(secret, Totp.counterAt(clock.wall) + 2)
        clock.advance(60 * 60_000L)
        assertTrue(manager.unlock("000000") !is VerifyResult.Success) // an attempt an hour later is seen
        // … then the clock is set back to when the code was valid.
        clock.wall -= 60 * 60_000L - 60_000L
        assertTrue(manager.unlock(saved) !is VerifyResult.Success)
    }
}
