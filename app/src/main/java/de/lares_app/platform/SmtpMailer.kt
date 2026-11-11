/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** SMTP settings of an email channel. */
data class SmtpSettings(
    val host: String,
    val port: Int,
    /** false = implicit TLS from the start (port 465), true = STARTTLS upgrade (port 587). */
    val startTls: Boolean,
    val username: String,
    val password: String,
    val from: String,
    val to: String,
)

/**
 * Minimal SMTP client for guardian emails – no extra dependency. Always encrypted: implicit TLS or
 * STARTTLS; a server without STARTTLS is refused, so the password never travels in plain text.
 * The server certificate is checked including the host name.
 */
class SmtpMailer(private val socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory) {

    fun send(settings: SmtpSettings, subject: String, body: String) {
        var socket: Socket = Socket().apply {
            soTimeout = TIMEOUT_MILLIS
            connect(InetSocketAddress(settings.host, settings.port), TIMEOUT_MILLIS)
        }
        try {
            if (!settings.startTls) socket = upgrade(socket, settings.host)
            var smtp = conversation(socket)
            smtp.expect(220)
            var capabilities = smtp.ehlo()
            if (settings.startTls) {
                if (capabilities.none { it.equals("STARTTLS", ignoreCase = true) }) throw IOException("server offers no STARTTLS")
                smtp.command("STARTTLS", 220)
                socket = upgrade(socket, settings.host)
                smtp = conversation(socket)
                capabilities = smtp.ehlo()
            }
            if (settings.username.isNotEmpty()) smtp.authenticate(capabilities, settings.username, settings.password)
            smtp.sendMail(settings.from, settings.to, MailComposer.compose(settings.from, settings.to, subject, body))
            smtp.quit()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun upgrade(plain: Socket, host: String): SSLSocket {
        val tls = socketFactory.createSocket(plain, host, plain.port, true) as SSLSocket
        // Hostname verification (SSLSocket doesn't do it by itself).
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.soTimeout = TIMEOUT_MILLIS
        tls.startHandshake()
        return tls
    }

    private fun conversation(socket: Socket) = SmtpConversation(
        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)),
        OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII),
    )

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}

/** The SMTP command / reply dialogue, independent of the socket (unit-testable). */
class SmtpConversation(private val reader: BufferedReader, private val writer: Writer) {

    /** Reads a (possibly multi-line) reply and checks its code. @return the text lines. */
    fun expect(code: Int): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: throw IOException("connection closed")
            if (line.length < 3) throw IOException("invalid reply: $line")
            lines += line.drop(4)
            if (line.length == 3 || line[3] == ' ') {
                val actual = line.take(3).toIntOrNull()
                if (actual != code) throw IOException("SMTP $actual ${line.drop(4)}".trim())
                return lines
            }
        }
    }

    fun command(line: String, code: Int): List<String> {
        writer.write(line + "\r\n")
        writer.flush()
        return expect(code)
    }

    /** @return the server's capabilities (e.g. "STARTTLS", "AUTH PLAIN LOGIN"). */
    fun ehlo(): List<String> = command("EHLO lares.invalid", 250).drop(1)

    /** Uses AUTH PLAIN if offered, otherwise AUTH LOGIN (e.g. Outlook offers only LOGIN). */
    fun authenticate(capabilities: List<String>, username: String, password: String) {
        val mechanisms = capabilities
            .firstOrNull { it.startsWith("AUTH ", ignoreCase = true) || it.startsWith("AUTH=", ignoreCase = true) }
            ?.drop(5)?.uppercase()?.split(' ')
            .orEmpty()
        when {
            "PLAIN" in mechanisms || mechanisms.isEmpty() -> authPlain(username, password)
            "LOGIN" in mechanisms -> authLogin(username, password)
            else -> throw IOException("no supported login method (${mechanisms.joinToString()})")
        }
    }

    fun authPlain(username: String, password: String) {
        val token = Base64.getEncoder().encodeToString("\u0000$username\u0000$password".toByteArray(Charsets.UTF_8))
        command("AUTH PLAIN $token", 235)
    }

    fun authLogin(username: String, password: String) {
        val b64 = Base64.getEncoder()
        command("AUTH LOGIN", 334)
        command(b64.encodeToString(username.toByteArray(Charsets.UTF_8)), 334)
        command(b64.encodeToString(password.toByteArray(Charsets.UTF_8)), 235)
    }

    fun sendMail(from: String, to: String, message: String) {
        command("MAIL FROM:<$from>", 250)
        command("RCPT TO:<$to>", 250)
        command("DATA", 354)
        // The composed message is base64-encoded, so no line starts with "." (no dot-stuffing needed).
        writer.write(message.replace("\r\n", "\n").replace("\n", "\r\n"))
        command("\r\n.", 250)
    }

    fun quit() {
        runCatching { command("QUIT", 221) }
    }
}

/** Builds a UTF-8 plain-text message (subject per RFC 2047, body base64). */
object MailComposer {
    fun compose(
        from: String,
        to: String,
        subject: String,
        body: String,
        date: ZonedDateTime = ZonedDateTime.now(),
        messageId: String = "<${UUID.randomUUID()}@lares.invalid>",
    ): String {
        val encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.toByteArray(Charsets.UTF_8)) + "?="
        val encodedBody = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
        return buildString {
            append("From: Lares <").append(from).append(">\r\n")
            append("To: <").append(to).append(">\r\n")
            append("Subject: ").append(encodedSubject).append("\r\n")
            append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).format(date)).append("\r\n")
            append("Message-ID: ").append(messageId).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(encodedBody)
        }
    }
}
