/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.ScheduleWindow
import de.lares_app.core.policy.WindowMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Exported rules and groups. Contains no readable secrets (no guardian code, no channel tokens) and
 * no usage data. Groups are referenced by name. Optionally the TOTP secret, encrypted with the
 * recovery code ([guardianTotp], see [de.lares_app.core.unlock.TotpTransfer]) – useless without it.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val groups: List<BackupGroup> = emptyList(),
    val rules: List<BackupRule> = emptyList(),
    /** Encrypted authenticator secret (older Lares versions ignore it). */
    val guardianTotp: String? = null,
    /** Since 1.2 (older versions ignore it; their rules then fall back to "always blocked"). */
    val schedules: List<BackupSchedule> = emptyList(),
) {
    companion object {
        const val FORMAT = "lares-rules"
    }
}

@Serializable
data class BackupGroup(
    val name: String,
    val limitMinutesPerDay: Int? = null,
    val enabled: Boolean = true,
    /** Name of the schedule that applies instead of the limit (since 1.2). */
    val schedule: String? = null,
)

@Serializable
data class BackupSchedule(
    val name: String,
    val outside: WindowMode = WindowMode.BLOCK,
    val outsideMinutes: Int = 0,
    val windows: List<ScheduleWindow> = emptyList(),
)

@Serializable
data class BackupRule(
    val type: RuleType,
    val target: String,
    val limitMinutesPerDay: Int? = null,
    val enabled: Boolean = true,
    /** Name of the group, or null for a single rule. */
    val group: String? = null,
    /** Name of the schedule that applies instead of the limit (since 1.2). */
    val schedule: String? = null,
)

/** Result of [RuleRepository.importBackup]. */
data class ImportResult(val groupsAdded: Int, val rulesAdded: Int, val skipped: Int)

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** @throws IllegalArgumentException if it isn't a Lares rules file. */
    fun decode(text: String): BackupFile {
        val backup = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("not a Lares rules file", it) }
        require(backup.format == BackupFile.FORMAT) { "not a Lares rules file" }
        return backup
    }
}
