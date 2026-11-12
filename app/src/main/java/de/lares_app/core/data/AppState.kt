/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.alerts.AlertChannelConfig
import de.lares_app.core.policy.PendingReset
import de.lares_app.core.policy.ResetSchedule
import de.lares_app.core.policy.TimedSession
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime

@Serializable
enum class BlockingMode { ACCESSIBILITY, USAGE_STATS }

@Serializable
enum class UnlockMethod { PASSWORD, TOTP }

/** Tamper protections, each individually toggleable (disabling = loosening). */
@Serializable
enum class Protection { UNINSTALL, SETTINGS_GUARD, RESTART_AFTER_REBOOT, GUARDIAN_ALERTS }

@Serializable
data class AppSettings(
    val setupComplete: Boolean = false,
    /** null until chosen during onboarding. */
    val blockingMode: BlockingMode? = null,
    val websiteBlocking: Boolean = false,
    val protections: Set<Protection> = setOf(Protection.RESTART_AFTER_REBOOT),
    val resetMinuteOfDay: Int = 0,
    val pendingResetMinuteOfDay: Int? = null,
    val pendingResetEffectiveFromMillis: Long? = null,
    /** Guardian notification channels (secrets encrypted, see AlertChannelConfig). */
    val alertChannels: List<AlertChannelConfig> = emptyList(),
    /** Countdown overlay in the last 5 minutes of a limit. */
    val limitTimer: Boolean = true,
    /** Optional notifications before a limit ends (need the notification permission on Android 13+). */
    val warnFiveMinutes: Boolean = false,
    val warnOneMinute: Boolean = false,
) {
    val resetSchedule: ResetSchedule
        get() = ResetSchedule(
            resetTime = LocalTime.ofSecondOfDay(resetMinuteOfDay * 60L),
            pending = if (pendingResetMinuteOfDay != null && pendingResetEffectiveFromMillis != null) {
                PendingReset(
                    LocalTime.ofSecondOfDay(pendingResetMinuteOfDay * 60L),
                    Instant.ofEpochMilli(pendingResetEffectiveFromMillis),
                )
            } else {
                null
            },
        )

    fun withResetSchedule(schedule: ResetSchedule): AppSettings = copy(
        resetMinuteOfDay = schedule.resetTime.toSecondOfDay() / 60,
        pendingResetMinuteOfDay = schedule.pending?.resetTime?.let { it.toSecondOfDay() / 60 },
        pendingResetEffectiveFromMillis = schedule.pending?.effectiveFrom?.toEpochMilli(),
    )
}

/** Credentials and unlock state. Secrets are only stored as hashes or Keystore-encrypted. */
@Serializable
data class UnlockState(
    val method: UnlockMethod? = null,
    val passwordHash: String? = null,
    /** TOTP secret, encrypted with a Keystore key (see [SecretCipher]). */
    val totpSecretEncrypted: String? = null,
    val totpLastCounter: Long = Long.MIN_VALUE,
    val recoveryHash: String? = null,
    /** Set after the recovery code was used: the guardian must set up a new code. */
    val reSetupRequired: Boolean = false,
    val failedAttempts: Int = 0,
    val lockout: TimedSession? = null,
    val pause: TimedSession? = null,
    val unlockedSession: TimedSession? = null,
) {
    val isSetUp: Boolean get() = method != null
}

/** The usage period currently counted (advanced monotonically, see ResetSchedule.advance). */
@Serializable
data class TrackedPeriod(val startMillis: Long, val endMillis: Long)

@Serializable
data class AppState(
    val settings: AppSettings = AppSettings(),
    val unlock: UnlockState = UnlockState(),
    val trackedPeriod: TrackedPeriod? = null,
)

/** Persistent, atomically updated app state (DataStore in production, in-memory in tests). */
interface StateStore {
    val state: Flow<AppState>

    suspend fun current(): AppState

    /** Applies [transform] atomically and returns the new state. */
    suspend fun update(transform: (AppState) -> AppState): AppState
}

/** Encrypts small secrets with a key that never leaves the device (Android Keystore). */
interface SecretCipher {
    fun encrypt(plain: ByteArray): String

    fun decrypt(encoded: String): ByteArray
}

/** Thrown when a loosening change is attempted while the app isn't unlocked. */
class CodeRequiredException : IllegalStateException("This change loosens restrictions and needs the guardian code")
