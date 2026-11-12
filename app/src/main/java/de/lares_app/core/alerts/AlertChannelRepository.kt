/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.alerts

import de.lares_app.core.data.UnlockGate
import de.lares_app.core.data.SecretCipher
import de.lares_app.core.data.StateStore
import de.lares_app.core.policy.ChangeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Guardian notification channels. Adding one is free (more oversight = tightening); editing or
 * removing needs the code (decision: changing alert channels is loosening).
 */
class AlertChannelRepository(
    private val store: StateStore,
    private val gate: UnlockGate,
    private val cipher: SecretCipher,
) {
    val channels: Flow<List<AlertChannelConfig>> = store.state.map { it.settings.alertChannels }

    suspend fun get(id: String): AlertChannelConfig? = store.current().settings.alertChannels.firstOrNull { it.id == id }

    /** Decrypted form for editing / sending. */
    fun draftOf(config: AlertChannelConfig): ChannelDraft = ChannelDraft(
        type = config.type,
        language = config.language,
        server = config.server.orEmpty(),
        address = config.address.orEmpty(),
        secret = config.secretEncrypted?.let { runCatching { cipher.decrypt(it).decodeToString() }.getOrNull() }.orEmpty(),
        port = config.port ?: ChannelDraft.SMTPS_PORT,
        startTls = config.startTls,
        username = config.username.orEmpty(),
        sender = config.sender.orEmpty(),
    )

    /** @return the new channel id. */
    suspend fun add(draft: ChannelDraft): String {
        val clean = draft.trimmed()
        require(clean.isComplete) { "incomplete channel" }
        val config = toConfig(UUID.randomUUID().toString(), clean)
        store.update { it.copy(settings = it.settings.copy(alertChannels = it.settings.alertChannels + config)) }
        return config.id
    }

    suspend fun update(id: String, draft: ChannelDraft) {
        val clean = draft.trimmed()
        require(clean.isComplete) { "incomplete channel" }
        val old = requireNotNull(get(id)) { "unknown channel $id" }
        gate.check(ChangeKind.LOOSENING)
        val config = toConfig(id, clean).copy(deliveredUpTo = old.deliveredUpTo)
        store.update { s -> s.copy(settings = s.settings.copy(alertChannels = s.settings.alertChannels.map { if (it.id == id) config else it })) }
    }

    suspend fun remove(id: String) {
        get(id) ?: return
        gate.check(ChangeKind.LOOSENING)
        store.update { s -> s.copy(settings = s.settings.copy(alertChannels = s.settings.alertChannels.filterNot { it.id == id })) }
    }

    private fun toConfig(id: String, draft: ChannelDraft) = AlertChannelConfig(
        id = id,
        type = draft.type,
        language = draft.language,
        server = draft.server.ifBlank { null },
        address = draft.address.ifBlank { null },
        secretEncrypted = draft.secret.ifBlank { null }?.let { cipher.encrypt(it.encodeToByteArray()) },
        port = draft.port.takeIf { draft.type == ChannelType.EMAIL },
        startTls = draft.type == ChannelType.EMAIL && draft.startTls,
        username = draft.username.ifBlank { null },
        sender = draft.sender.ifBlank { null },
    )
}
