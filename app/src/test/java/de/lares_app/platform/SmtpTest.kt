/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import de.lares_app.core.alerts.ChannelDraft
import de.lares_app.core.alerts.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.time.ZonedDateTime
import java.util.Base64

class SmtpTest {

    private fun conversation(vararg serverLines: String): Pair<SmtpConversation, StringWriter> {
        val out = StringWriter()
        return SmtpConversation(BufferedReader(StringReader(serverLines.joinToString("\r\n", postfix = "\r\n"))), out) to out
    }

    @Test fun fullDialogue() {
        val (smtp, out) = conversation(
            "220 mail.example.org ESMTP",
            "250-mail.example.org",
            "250-AUTH PLAIN LOGIN",
            "250 8BITMIME",
            "235 2.7.0 Authentication successful",
            "250 OK", "250 OK", "354 End data with <CR><LF>.<CR><LF>", "250 queued",
            "221 Bye",
        )
        smtp.expect(220)
        assertEquals(listOf("AUTH PLAIN LOGIN", "8BITMIME"), smtp.ehlo())
        smtp.authPlain("lares@example.org", "pä ss")
        smtp.sendMail("lares@example.org", "guardian@example.org", "Subject: x\r\n\r\nYm9keQ==")
        smtp.quit()

        val sent = out.toString().split("\r\n")
        val auth = Base64.getDecoder().decode(sent[1].removePrefix("AUTH PLAIN ")).toString(Charsets.UTF_8)
        assertEquals("\u0000lares@example.org\u0000pä ss", auth)
        assertEquals("MAIL FROM:<lares@example.org>", sent[2])
        assertEquals("RCPT TO:<guardian@example.org>", sent[3])
        assertEquals("DATA", sent[4])
        assertTrue(out.toString().endsWith("Ym9keQ==\r\n.\r\nQUIT\r\n"))
    }

    @Test fun usesAuthLoginWhenPlainIsNotOffered() {
        val (smtp, out) = conversation("334 VXNlcm5hbWU6", "334 UGFzc3dvcmQ6", "235 2.7.0 Authentication successful")
        smtp.authenticate(listOf("SIZE 35882577", "AUTH LOGIN XOAUTH2"), "user@outlook.com", "pw")
        val sent = out.toString().split("\r\n")
        assertEquals("AUTH LOGIN", sent[0])
        assertEquals("user@outlook.com", String(Base64.getDecoder().decode(sent[1])))
        assertEquals("pw", String(Base64.getDecoder().decode(sent[2])))
    }

    @Test fun refusesUnsupportedLoginMethods() {
        val (smtp, _) = conversation()
        assertTrue(runCatching { smtp.authenticate(listOf("AUTH XOAUTH2"), "u", "p") }.exceptionOrNull() is IOException)
    }

    @Test fun errorReplyFails() {
        val (smtp, _) = conversation("220 hi", "250 ok", "535 5.7.8 Authentication failed")
        smtp.expect(220)
        smtp.ehlo()
        val e = runCatching { smtp.authPlain("u", "wrong") }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("535"))
    }

    @Test fun messageIsUtf8AndDotSafe() {
        val msg = MailComposer.compose(
            "lares@example.org", "guardian@example.org", "Lares: Testnachricht – äöü", ".\nZeile mit Umlauten: äöü",
            date = ZonedDateTime.parse("2026-11-11T15:30:00+01:00"), messageId = "<id@lares.invalid>",
        )
        val (headers, body) = msg.split("\r\n\r\n", limit = 2)
        assertTrue(headers.contains("Subject: =?UTF-8?B?"))
        assertTrue(headers.contains("Date: Wed, 11 Nov 2026 15:30:00 +0100"))
        assertTrue(headers.contains("Content-Transfer-Encoding: base64"))
        assertEquals(".\nZeile mit Umlauten: äöü", String(Base64.getMimeDecoder().decode(body), Charsets.UTF_8))
        assertFalse("no line may start with a dot", msg.split("\r\n").any { it.startsWith(".") })
    }

    @Test fun emailValidation() {
        val ok = ChannelDraft(ChannelType.EMAIL, server = "smtp.example.org", username = "u", secret = "p", sender = "a@example.org", address = "b@example.org")
        assertTrue(ok.isComplete)
        assertTrue(ok.copy(username = "", secret = "").isComplete) // relay without login
        assertFalse(ok.copy(secret = "").isComplete) // username without password
        assertFalse(ok.copy(address = "not-an-address").isComplete)
        assertFalse(ok.copy(server = "smtps://smtp.example.org").isComplete)
        assertFalse(ok.copy(port = 0).isComplete)
        assertEquals(" spaced ", ok.copy(secret = " spaced ").trimmed().secret) // passwords stay untouched
    }
}
