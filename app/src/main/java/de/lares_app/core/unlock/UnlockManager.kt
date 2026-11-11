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
import de.lares_app.core.policy.AfterReboot
import de.lares_app.core.policy.TimedSession
import de.lares_app.core.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed interface VerifyResult {
    /** Correct code; the app is now unlocked for [UnlockManager.UNLOCKED_DURATION_MILLIS]. */
    data class Success(val viaRecoveryCode: Boolean) : VerifyResult

    /** @property attemptsLeft attempts before a wrong code starts a waiting time; [lockoutMillis] > 0 if one started. */
    data class Wrong(val attemptsLeft: Int, val lockoutMillis: Long) : VerifyResult

    data class LockedOut(val remainingMillis: Long) : VerifyResult

    data object NotSetUp : VerifyResult
}

/** Result of [UnlockManager.beginTotpSetup]; nothing is stored until the guardian confirms a code. */
class TotpSetup internal constructor(internal val secret: ByteArray, val provisioningUri: String) {
    val secretBase32: String get() = Base32.encode(secret)
}

/**
 * Verifies the guardian code (password / TOTP / recovery code) with brute-force protection.
 * A correct code switches the app into **unlocked mode** for a limited time, in which every
 * change is allowed (including starting a pause). See docs/unlock-and-security.md.
 */
class UnlockManager(
    private val store: StateStore,
    private val clock: Clock,
    private val cipher: SecretCipher,
    private val tamperLog: TamperLog,
    private val hasher: PasswordHasher = PasswordHasher(),
    private val random: SecureRandom = SecureRandom(),
    /** Slow key derivation runs here, never on the UI thread. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()

    // ---- Setup ---------------------------------------------------------------------------------

    /**
     * Allowed during first setup (until [completeSetup] – also if the app was closed in between)
     * or while unlocked (changing the method is loosening). After the recovery code was used, the
     * recovery itself opened unlocked mode; if that ran out, the code has to be entered again –
     * so nobody can set their own code on a phone the guardian left half-way through re-setup.
     */
    suspend fun canChangeMethod(): Boolean {
        val s = store.current()
        return !s.unlock.isSetUp || !s.settings.setupComplete || isUnlocked(s.unlock)
    }

    /** Sets a static password. @return the new recovery code (show once). */
    suspend fun setupPassword(password: String): String {
        require(password.length >= MIN_PASSWORD_LENGTH) { "password too short" }
        if (!canChangeMethod()) throw CodeRequiredException()
        val passwordHash = withContext(cpu) { hasher.hash(password) }
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
        val encrypted = withContext(cpu) { cipher.encrypt(setup.secret) }
        return storeNewMethod {
            it.copy(method = UnlockMethod.TOTP, totpSecretEncrypted = encrypted, totpLastCounter = counter, passwordHash = null)
        }
    }

    /**
     * Stores the new method and recovery code. "Setup complete" / "re-setup required" are left
     * untouched until [completeSetup], so the screen showing the new recovery code stays open
     * (and setup can be repeated if the app is closed before the code was confirmed).
     */
    private suspend fun storeNewMethod(apply: (UnlockState) -> UnlockState): String {
        val recovery = RecoveryCode.generate(random)
        val recoveryHash = hashRecovery(RecoveryCode.normalize(recovery)!!)
        store.update { s ->
            s.copy(unlock = apply(s.unlock).copy(recoveryHash = recoveryHash, failedAttempts = 0, lockout = null))
        }
        return recovery
    }

    /** Called once the guardian confirmed storing the recovery code (first setup, recovery, change). */
    suspend fun completeSetup() {
        check(store.current().unlock.isSetUp) { "no unlock method set up" }
        store.update { s ->
            s.copy(
                unlock = s.unlock.copy(reSetupRequired = false),
                settings = s.settings.copy(setupComplete = true),
            )
        }
    }

    // ---- Unlocking -----------------------------------------------------------------------------

    /** Checks the code; on success the app is unlocked for [UNLOCKED_DURATION_MILLIS] (ends on reboot). */
    suspend fun unlock(input: String): VerifyResult = mutex.withLock {
        val unlock = store.current().unlock
        if (!unlock.isSetUp) return VerifyResult.NotSetUp
        var lockout = unlock.lockout
        if (lockout != null && lockout.isFromEarlierBoot(clock)) {
            // Restarted phone: the waiting time starts over (there's no trustworthy clock across reboots).
            lockout = lockout.restarted(clock)
            store.update { it.copy(unlock = it.unlock.copy(lockout = lockout)) }
        }
        lockout?.takeIf { it.isActive(clock) }?.let {
            return VerifyResult.LockedOut(it.remainingMillis(clock))
        }

        var matchedTotpCounter: Long? = null
        val primaryOk = withContext(cpu) {
            when (unlock.method) {
                UnlockMethod.PASSWORD -> unlock.passwordHash?.let { hasher.verify(input, it) } == true
                UnlockMethod.TOTP -> unlock.totpSecretEncrypted?.let { enc ->
                    val secret = runCatching { cipher.decrypt(enc) }.getOrNull()
                    secret?.let { Totp.verify(it, input, clock.wallMillis(), unlock.totpLastCounter) }
                }?.also { matchedTotpCounter = it } != null
                null -> false
            }
        }
        val recoveryOk = !primaryOk && RecoveryCode.normalize(input)?.let { normalized ->
            unlock.recoveryHash?.let { verifyRecovery(normalized, it) }
        } == true

        if (primaryOk || recoveryOk) {
            val session = TimedSession.start(UNLOCKED_DURATION_MILLIS, clock)
            store.update { s ->
                s.copy(
                    unlock = s.unlock.copy(
                        failedAttempts = 0,
                        lockout = null,
                        totpLastCounter = matchedTotpCounter ?: s.unlock.totpLastCounter,
                        reSetupRequired = s.unlock.reSetupRequired || recoveryOk,
                        unlockedSession = session,
                    ),
                )
            }
            if (recoveryOk) tamperLog.record(TamperEventType.RECOVERY_CODE_USED)
            return VerifyResult.Success(recoveryOk)
        }

        val failures = unlock.failedAttempts + 1
        val lockoutMillis = AttemptPolicy.lockoutMillisAfter(failures)
        store.update { s ->
            s.copy(
                unlock = s.unlock.copy(
                    failedAttempts = failures,
                    lockout = if (lockoutMillis > 0) TimedSession.start(lockoutMillis, clock, AfterReboot.RESTART) else null,
                ),
            )
        }
        if (AttemptPolicy.shouldReport(failures)) {
            tamperLog.record(TamperEventType.REPEATED_WRONG_CODE, "failures=$failures")
        }
        return VerifyResult.Wrong(AttemptPolicy.attemptsLeft(failures), lockoutMillis)
    }

    /** Back to restricted mode (no code needed – it's tightening). */
    suspend fun lock() {
        store.update { it.copy(unlock = it.unlock.copy(unlockedSession = null)) }
    }

    suspend fun isUnlocked(): Boolean = isUnlocked(store.current().unlock)

    private fun isUnlocked(unlock: UnlockState) = unlock.unlockedSession?.isActive(clock) == true

    // ---- Pause ---------------------------------------------------------------------------------

    /** Suspends all blocking for [durationMillis] (no maximum; ends on reboot). Only while unlocked. */
    suspend fun startPause(durationMillis: Long) {
        if (!isUnlocked()) throw CodeRequiredException()
        val session = TimedSession.start(durationMillis, clock)
        store.update { it.copy(unlock = it.unlock.copy(pause = session)) }
    }

    /** Ending early is tightening, so no code needed. */
    suspend fun endPause() {
        store.update { it.copy(unlock = it.unlock.copy(pause = null)) }
    }

    suspend fun isPauseActive(): Boolean = store.current().unlock.pause?.isActive(clock) == true

    // ---- Recovery code hashing -----------------------------------------------------------------

    /**
     * The recovery code has 100 random bits, so brute force is impossible even with a fast hash.
     * Salted SHA-256 keeps setup and unlock quick (no second slow PBKDF2 run).
     */
    private fun hashRecovery(canonical: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "$RECOVERY_PREFIX$" + b64(salt) + "$" + b64(sha256(salt, canonical))
    }

    private fun verifyRecovery(canonical: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 3 || parts[0] != RECOVERY_PREFIX) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(sha256(salt, canonical), expected)
    }

    private fun sha256(salt: ByteArray, text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(text.toByteArray())
        }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        const val MIN_PASSWORD_LENGTH = 4
        const val UNLOCKED_DURATION_MILLIS = 10 * 60_000L
        private const val RECOVERY_PREFIX = "sha256"
    }
}
