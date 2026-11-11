/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.RuleType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Exported rules and groups. Contains no secrets (no guardian code, no channel tokens) and no
 * usage data. Groups are referenced by name.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val groups: List<BackupGroup> = emptyList(),
    val rules: List<BackupRule> = emptyList(),
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
)

@Serializable
data class BackupRule(
    val type: RuleType,
    val target: String,
    val limitMinutesPerDay: Int? = null,
    val enabled: Boolean = true,
    /** Name of the group, or null for a single rule. */
    val group: String? = null,
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
