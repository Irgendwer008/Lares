/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 TOTP: HMAC-SHA1, 30 s steps, 6 digits – the defaults every authenticator app supports. */
object Totp {
    const val PERIOD_SECONDS = 30L
    const val DIGITS = 6
    const val SECRET_BYTES = 20

    fun counterAt(wallMillis: Long): Long = Math.floorDiv(wallMillis / 1000, PERIOD_SECONDS)

    fun code(secret: ByteArray, counter: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /**
     * Checks [input] against the current step ±[window] steps (clock drift). Codes at or before
     * [lastUsedCounter] are rejected, so an observed code can't be replayed.
     *
     * @return the matched counter, or null.
     */
    fun verify(
        secret: ByteArray,
        input: String,
        wallMillis: Long,
        lastUsedCounter: Long,
        window: Int = 1,
    ): Long? {
        val digits = input.filterNot { it.isWhitespace() }
        if (digits.length != DIGITS || !digits.all { it.isDigit() }) return null
        val now = counterAt(wallMillis)
        for (counter in (now - window)..(now + window)) {
            if (counter <= lastUsedCounter) continue
            if (MessageDigest.isEqual(code(secret, counter).toByteArray(), digits.toByteArray())) {
                return counter
            }
        }
        return null
    }

    /** `otpauth://` URI for the QR code shown to the guardian. */
    fun provisioningUri(secret: ByteArray, account: String, issuer: String = "Lares"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val label = enc(issuer) + ":" + enc(account)
        return "otpauth://totp/" + label +
            "?secret=" + Base32.encode(secret) +
            "&issuer=" + enc(issuer) +
            "&algorithm=SHA1&digits=" + DIGITS +
            "&period=" + PERIOD_SECONDS
    }
}
