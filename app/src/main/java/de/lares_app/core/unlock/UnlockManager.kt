/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

import de.lares_app.core.data.CodeRequiredException
import de.lares_app.core.data.SecretCipher
import de.lares_app.core.data.StateStore
import de.lares_app.core.data.TamperEventType
import de.lares_app.core.data.TamperLog
import de.lares_app.core.data.UnlockMethod
import de.lares_app.core.data.UnlockState
import de.lares_app.core.policy.TimedSession
import de.lares_app.core.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

sealed interface VerifyResult {
    /** Correct code. Pass [grant] to [UnlockManager.startPause] / [UnlockManager.startEditSession]. */
    data class Success(val grant: UnlockGrant, val viaRecoveryCode: Boolean) : VerifyResult

    data class Wrong(val attemptsBeforeLockout: Int, val lockoutMillis: Long) : VerifyResult

    data class LockedOut(val remainingMillis: Long) : VerifyResult

    data object NotSetUp : VerifyResult
}

/**
 * Proof of a just-verified code, valid for one use within [GRANT_VALIDITY_MILLIS].
 * Exists only in memory, so pause / edit session can't be started without entering the code.
 */
class UnlockGrant internal constructor(internal val issuedElapsedMillis: Long, internal val bootId: Int) {
    internal var used = false
}

/** Result of [UnlockManager.beginTotpSetup]; nothing is stored until the guardian confirms a code. */
class TotpSetup internal constructor(internal val secret: ByteArray, val provisioningUri: String) {
    val secretBase32: String get() = Base32.encode(secret)
}

/**
 * Verifies the guardian code (password / TOTP / recovery code) with brute-force protection and
 * manages pause and edit sessions. See docs/unlock-and-security.md.
 */
class UnlockManager(
    private val store: StateStore,
    private val clock: Clock,
    private val cipher: SecretCipher,
    private val tamperLog: TamperLog,
    private val hasher: PasswordHasher = PasswordHasher(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val mutex = Mutex()

    // ---- Setup ---------------------------------------------------------------------------------

    /** Allowed on first setup, after recovery, or during an edit session (changing the method is loosening). */
    suspend fun canChangeMethod(): Boolean {
        val unlock = store.current().unlock
        return !unlock.isSetUp || unlock.reSetupRequired || unlock.editSession?.isActive(clock) == true
    }

    /** Sets a static password. @return the new recovery code (show once). */
    suspend fun setupPassword(password: String): String {
        require(password.length >= MIN_PASSWORD_LENGTH) { "password too short" }
        if (!canChangeMethod()) throw CodeRequiredException()
        val passwordHash = hasher.hash(password)
        return storeNewMethod { it.copy(method = UnlockMethod.PASSWORD, passwordHash = passwordHash, totpSecretEncrypted = null) }
    }

    fun beginTotpSetup(account: String): TotpSetup {
        val secret = ByteArray(Totp.SECRET_BYTES).also(random::nextBytes)
        return TotpSetup(secret, Totp.provisioningUri(secret, account))
    }

    /**
     * Stores the TOTP secret once the guardian proves the authenticator app works.
     * @return the new recovery code, or null if [code] was wrong.
     */
    suspend fun confirmTotpSetup(setup: TotpSetup, code: String): String? {
        if (!canChangeMethod()) throw CodeRequiredException()
        val counter = Totp.verify(setup.secret, code, clock.wallMillis(), Long.MIN_VALUE) ?: return null
        val encrypted = cipher.encrypt(setup.secret)
        return storeNewMethod {
            it.copy(method = UnlockMethod.TOTP, totpSecretEncrypted = encrypted, totpLastCounter = counter, passwordHash = null)
        }
    }

    private suspend fun storeNewMethod(apply: (UnlockState) -> UnlockState): String {
        val recovery = RecoveryCode.generate(random)
        val recoveryHash = hasher.hash(RecoveryCode.normalize(recovery)!!)
        store.update { s ->
            s.copy(
                unlock = apply(s.unlock).copy(recoveryHash = recoveryHash, reSetupRequired = false, failedAttempts = 0, lockout = null),
                // Setup stays "incomplete" until the new recovery code has been shown and confirmed.
                settings = s.settings.copy(setupComplete = false),
            )
        }
        return recovery
    }

    // ---- Verification --------------------------------------------------------------------------

    suspend fun verify(input: String): VerifyResult = mutex.withLock {
        val unlock = store.current().unlock
        if (!unlock.isSetUp) return VerifyResult.NotSetUp
        unlock.lockout?.takeIf { it.isActive(clock) }?.let {
            return VerifyResult.LockedOut(it.remainingMillis(clock))
        }

        var matchedTotpCounter: Long? = null
        val primaryOk = when (unlock.method) {
            UnlockMethod.PASSWORD -> unlock.passwordHash?.let { hasher.verify(input, it) } == true
            UnlockMethod.TOTP -> unlock.totpSecretEncrypted?.let { enc ->
                val secret = runCatching { cipher.decrypt(enc) }.getOrNull()
                secret?.let { Totp.verify(it, input, clock.wallMillis(), unlock.totpLastCounter) }
            }?.also { matchedTotpCounter = it } != null
            null -> false
        }
        val recoveryOk = !primaryOk && RecoveryCode.normalize(input)?.let { normalized ->
            unlock.recoveryHash?.let { hasher.verify(normalized, it) }
        } == true

        if (primaryOk || recoveryOk) {
            store.update { s ->
                s.copy(
                    unlock = s.unlock.copy(
                        failedAttempts = 0,
                        lockout = null,
                        totpLastCounter = matchedTotpCounter ?: s.unlock.totpLastCounter,
                        reSetupRequired = s.unlock.reSetupRequired || recoveryOk,
                    ),
                )
            }
            if (recoveryOk) tamperLog.record(TamperEventType.RECOVERY_CODE_USED)
            return VerifyResult.Success(UnlockGrant(clock.elapsedMillis(), clock.bootId()), recoveryOk)
        }

        val failures = unlock.failedAttempts + 1
        val lockoutMillis = AttemptPolicy.lockoutMillisAfter(failures)
        store.update { s ->
            s.copy(
                unlock = s.unlock.copy(
                    failedAttempts = failures,
                    lockout = if (lockoutMillis > 0) TimedSession.start(lockoutMillis, clock) else null,
                ),
            )
        }
        if (AttemptPolicy.shouldReport(failures)) {
            tamperLog.record(TamperEventType.REPEATED_WRONG_CODE, "failures=$failures")
        }
        return VerifyResult.Wrong(AttemptPolicy.attemptsBeforeLockout(failures), lockoutMillis)
    }

    // ---- Pause & edit session ------------------------------------------------------------------

    /** Suspends all blocking for [durationMillis] (no maximum, see decision #17). */
    suspend fun startPause(grant: UnlockGrant, durationMillis: Long) {
        consume(grant)
        val session = TimedSession.start(durationMillis, clock)
        store.update { it.copy(unlock = it.unlock.copy(pause = session)) }
    }

    /** Allows loosening changes for [durationMillis]. */
    suspend fun startEditSession(grant: UnlockGrant, durationMillis: Long) {
        consume(grant)
        val session = TimedSession.start(durationMillis, clock)
        store.update { it.copy(unlock = it.unlock.copy(editSession = session)) }
    }

    /** Ending early is tightening, so no code needed. */
    suspend fun endPause() {
        store.update { it.copy(unlock = it.unlock.copy(pause = null)) }
    }

    suspend fun endEditSession() {
        store.update { it.copy(unlock = it.unlock.copy(editSession = null)) }
    }

    suspend fun isPauseActive(): Boolean = store.current().unlock.pause?.isActive(clock) == true

    private fun consume(grant: UnlockGrant) {
        val fresh = grant.bootId == clock.bootId() &&
            clock.elapsedMillis() - grant.issuedElapsedMillis in 0..GRANT_VALIDITY_MILLIS
        check(!grant.used && fresh) { "unlock grant expired or already used" }
        grant.used = true
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 4
        const val GRANT_VALIDITY_MILLIS = 5 * 60_000L
    }
}
