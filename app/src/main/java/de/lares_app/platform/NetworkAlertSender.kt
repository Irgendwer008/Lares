/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import de.lares_app.core.alerts.AlertSender
import de.lares_app.core.alerts.ChannelDraft
import de.lares_app.core.alerts.ChannelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Sends guardian notifications over HTTPS (plain `HttpsURLConnection`) or encrypted SMTP
 * ([SmtpMailer]) – no extra dependency. Requests go only to the endpoint the user configured;
 * no other data than the message is sent.
 */
class NetworkAlertSender(private val smtp: SmtpMailer = SmtpMailer()) : AlertSender {

    override suspend fun send(draft: ChannelDraft, title: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = draft.trimmed()
            when (d.type) {
                ChannelType.TELEGRAM -> request(
                    "POST",
                    "https://api.telegram.org/bot${d.secret}/sendMessage",
                    json = buildJsonObject {
                        put("chat_id", d.address)
                        put("text", "$title\n\n$text")
                    },
                )
                // JSON publishing (POST to the server root): unlike the Title header, it supports UTF-8.
                ChannelType.NTFY -> request(
                    "POST",
                    d.server,
                    json = buildJsonObject {
                        put("topic", d.address)
                        put("title", title)
                        put("message", text)
                        put("tags", JsonArray(listOf(JsonPrimitive("lock"))))
                    },
                    headers = if (d.secret.isNotBlank()) mapOf("Authorization" to "Bearer ${d.secret}") else emptyMap(),
                )
                ChannelType.WEBHOOK -> request(
                    "POST",
                    d.server,
                    json = buildJsonObject {
                        put("app", "Lares")
                        put("title", title)
                        put("message", text)
                        put("timestamp", System.currentTimeMillis())
                    },
                    headers = if (d.secret.isNotBlank()) mapOf("Authorization" to "Bearer ${d.secret}") else emptyMap(),
                )
                ChannelType.EMAIL -> smtp.send(
                    SmtpSettings(d.server, d.port, d.startTls, d.username, d.secret, from = d.sender, to = d.address),
                    subject = title,
                    body = text,
                )
                ChannelType.MATRIX -> request(
                    "PUT",
                    "${d.server}/_matrix/client/v3/rooms/${enc(d.address)}/send/m.room.message/${UUID.randomUUID()}",
                    json = buildJsonObject {
                        put("msgtype", "m.text")
                        put("body", "$title\n\n$text")
                    },
                    headers = mapOf("Authorization" to "Bearer ${d.secret}"),
                )
            }
            Unit
        }
    }

    override suspend fun detectTelegramChatId(botToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request("GET", "https://api.telegram.org/bot${botToken.trim()}/getUpdates")
            val updates = Json.parseToJsonElement(response).jsonObject["result"]?.jsonArray.orEmpty()
            val chatId = updates.reversed().firstNotNullOfOrNull { update ->
                (update.jsonObject["message"] ?: update.jsonObject["channel_post"])
                    ?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long
            }
            chatId?.toString() ?: throw IOException("no message found")
        }
    }

    /** @return the response body; throws for non-2xx responses or non-HTTPS URLs. */
    private fun request(
        method: String,
        url: String,
        json: JsonObject? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        require(url.startsWith("https://", ignoreCase = true)) { "HTTPS required" }
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false // never forward credentials to another host
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val payload = json?.toString() ?: body
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (json != null) "application/json; charset=utf-8" else "text/plain; charset=utf-8")
                connection.outputStream.use { it.write(payload.encodeToByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}
