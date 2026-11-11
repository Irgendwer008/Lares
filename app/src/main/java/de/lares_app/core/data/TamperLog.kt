/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import kotlinx.coroutines.flow.Flow

/** See docs/unlock-and-security.md, "Tamper events". */
enum class TamperEventType {
    DEVICE_ADMIN_DISABLED,
    ACCESSIBILITY_DISABLED,
    PERMISSION_REVOKED,
    PROTECTION_GAP,
    CLOCK_CHANGED,
    TIMEZONE_CHANGED,
    REPEATED_WRONG_CODE,
    RECOVERY_CODE_USED,
}

data class TamperEvent(
    val id: Long,
    val type: TamperEventType,
    val timestampMillis: Long,
    val details: String?,
    val delivered: Boolean,
)

/** Records tamper events; the alert dispatcher later delivers undelivered ones to the guardian. */
interface TamperLog {
    val events: Flow<List<TamperEvent>>

    suspend fun record(type: TamperEventType, details: String? = null)

    suspend fun undelivered(): List<TamperEvent>

    suspend fun markDelivered(id: Long)
}
