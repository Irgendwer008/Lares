/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.alerts

import kotlinx.serialization.Serializable

/** Where guardian notifications go. See docs/notifications.md. */
@Serializable
enum class ChannelType { TELEGRAM, NTFY, EMAIL, WEBHOOK, MATRIX }

/**
 * A configured notification channel as stored. The secret (bot token / access token) is only kept
 * encrypted with a Keystore key ([secretEncrypted]); everything else is plain.
 *
 * Field use per type:
 * - TELEGRAM: [address] = chat id, secret = bot token
 * - NTFY:     [server] = server URL, [address] = topic, secret = optional access token
 * - WEBHOOK:  [server] = URL, secret = optional bearer token
 * - MATRIX:   [server] = homeserver URL, [address] = room id, secret = access token
 * - EMAIL:    [server] = SMTP host, [port], [startTls], [username], [sender] = From address,
 *             [address] = recipient, secret = SMTP password
 */
@Serializable
data class AlertChannelConfig(
    val id: String,
    val type: ChannelType,
    /** Message language (BCP 47, e.g. "en", "de") – the guardian may speak another language. */
    val language: String,
    val server: String? = null,
    val address: String? = null,
    val secretEncrypted: String? = null,
    val port: Int? = null,
    val startTls: Boolean = false,
    val username: String? = null,
    val sender: String? = null,
    /** Highest tamper event id sent through this channel (each channel catches up on its own). */
    val deliveredUpTo: Long = 0,
)

/** Editable, decrypted form of a channel (only in memory). */
data class ChannelDraft(
    val type: ChannelType,
    val language: String = "en",
    val server: String = "",
    val address: String = "",
    val secret: String = "",
    /** EMAIL only. 465 = implicit TLS ("SSL/TLS"), 587 = STARTTLS. */
    val port: Int = SMTPS_PORT,
    val startTls: Boolean = false,
    val username: String = "",
    val sender: String = "",
) {
    /** Required fields filled in and URLs use HTTPS (credentials never go over plain HTTP). */
    val isComplete: Boolean
        get() = listOf(server, address, secret, username, sender).none { field -> field.any { it.isISOControl() } } && when (type) {
            ChannelType.TELEGRAM -> TELEGRAM_TOKEN.matches(secret.trim()) && TELEGRAM_CHAT_ID.matches(address.trim())
            ChannelType.NTFY -> isHttps(server) && address.isNotBlank()
            ChannelType.WEBHOOK -> isHttps(server)
            ChannelType.MATRIX -> isHttps(server) && address.startsWith("!") && address.contains(':') && secret.isNotBlank()
            ChannelType.EMAIL -> server.isNotBlank() && !server.contains("://") && port in 1..65535 &&
                isEmail(sender) && isEmail(address) && (username.isBlank() || secret.isNotBlank())
        }

    fun trimmed() = copy(
        server = server.trim().trimEnd('/'),
        address = address.trim(),
        // SMTP passwords may legitimately contain leading / trailing spaces; tokens never do.
        secret = if (type == ChannelType.EMAIL) secret else secret.trim(),
        username = username.trim(),
        sender = sender.trim(),
    )

    companion object {
        const val NTFY_DEFAULT_SERVER = "https://ntfy.sh"
        const val SMTPS_PORT = 465
        const val SUBMISSION_PORT = 587

        /** e.g. `123456789:AAF…` (bot id, colon, secret). */
        private val TELEGRAM_TOKEN = Regex("^[0-9]+:[A-Za-z0-9_-]{20,}$")

        /** Numeric chat id; groups / channels are negative (e.g. `-1001234567890`). */
        private val TELEGRAM_CHAT_ID = Regex("^-?[0-9]+$")

        fun empty(type: ChannelType, language: String) =
            ChannelDraft(type, language, server = if (type == ChannelType.NTFY) NTFY_DEFAULT_SERVER else "")

        private fun isEmail(s: String): Boolean {
            val t = s.trim()
            val at = t.indexOf('@')
            return at > 0 && at == t.lastIndexOf('@') && t.indexOf('.', at) > at + 1 && !t.endsWith(".") && t.none { it.isWhitespace() }
        }

        /** An https URL with a real host (no spaces, no empty host like `https://@`). */
        private fun isHttps(url: String): Boolean {
            val u = url.trim()
            if (!u.startsWith("https://", ignoreCase = true) || u.any { it.isWhitespace() }) return false
            return runCatching { java.net.URI(u).host }.getOrNull()?.isNotBlank() == true
        }
    }
}

/** Sends messages through a channel (HTTPS; implemented in the platform layer). */
interface AlertSender {
    suspend fun send(draft: ChannelDraft, title: String, text: String): Result<Unit>

    /** Telegram helper: reads the chat id of the latest message sent to the bot (e.g. "/start"). */
    suspend fun detectTelegramChatId(botToken: String): Result<String>
}
