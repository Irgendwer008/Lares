/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoPrimitivesTest {

    // RFC 6238 appendix B, SHA-1 secret "12345678901234567890".
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test fun totpMatchesRfc6238Vectors() {
        val vectors = mapOf(59L to "94287082", 1111111109L to "07081804", 1234567890L to "89005924", 20000000000L to "65353130")
        for ((seconds, expected) in vectors) {
            assertEquals(expected, Totp.code(rfcSecret, Totp.counterAt(seconds * 1000), digits = 8))
        }
        assertEquals("287082", Totp.code(rfcSecret, Totp.counterAt(59_000)))
    }

    @Test fun totpVerifyAcceptsDriftAndRejectsReplay() {
        val now = 1_234_567_890_000L
        val counter = Totp.counterAt(now)
        val previous = Totp.code(rfcSecret, counter - 1)
        assertEquals(counter - 1, Totp.verify(rfcSecret, previous, now, lastUsedCounter = Long.MIN_VALUE))
        assertNull(Totp.verify(rfcSecret, previous, now, lastUsedCounter = counter - 1)) // replay
        assertNull(Totp.verify(rfcSecret, Totp.code(rfcSecret, counter - 2), now, Long.MIN_VALUE)) // too old
        assertEquals(counter, Totp.verify(rfcSecret, Totp.code(rfcSecret, counter).chunked(3).joinToString(" "), now, Long.MIN_VALUE))
        assertNull(Totp.verify(rfcSecret, "abc123", now, Long.MIN_VALUE))
    }

    @Test fun provisioningUri() {
        val uri = Totp.provisioningUri(rfcSecret, "Anna's phone")
        assertEquals(
            "otpauth://totp/Lares:Anna%27s%20phone?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Lares&algorithm=SHA1&digits=6&period=30",
            uri,
        )
    }

    @Test fun base32RoundTripAndRfc4648Vectors() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("mzxw6ytboi======"))
        val random = ByteArray(37) { (it * 31 + 7).toByte() }
        assertArrayEquals(random, Base32.decode(Base32.encode(random)))
    }

    @Test fun passwordHashing() {
        val hasher = PasswordHasher(iterations = 1_000)
        val hash = hasher.hash("correct horse")
        assertTrue(hash.startsWith("pbkdf2-sha256$1000$"))
        assertTrue(hasher.verify("correct horse", hash))
        assertFalse(hasher.verify("correct horsE", hash))
        assertNotEquals(hash, hasher.hash("correct horse")) // salted
        assertFalse(hasher.verify("x", "garbage"))
        assertFalse(hasher.verify("x", "pbkdf2-sha256\$0\$AA\$AA"))
    }

    @Test fun recoveryCodeFormatAndNormalization() {
        val code = RecoveryCode.generate()
        assertTrue(Regex("^[0-9A-Z]{5}(-[0-9A-Z]{5}){3}$").matches(code))
        val canonical = RecoveryCode.normalize(code)!!
        assertEquals(20, canonical.length)
        assertEquals(canonical, RecoveryCode.normalize(code.lowercase().replace("-", " ")))
        assertEquals("01110ABCDE0123456789", RecoveryCode.normalize("oIlL0-abcde-01234-56789"))
        assertNull(RecoveryCode.normalize("too-short"))
        assertNull(RecoveryCode.normalize("UUUUU-UUUUU-UUUUU-UUUUU")) // U is not in the alphabet
    }

    @Test fun attemptPolicy() {
        assertEquals(0L, AttemptPolicy.lockoutMillisAfter(4))
        assertEquals(60_000L, AttemptPolicy.lockoutMillisAfter(5))
        assertEquals(120_000L, AttemptPolicy.lockoutMillisAfter(6))
        assertEquals(AttemptPolicy.MAX_LOCKOUT_MILLIS, AttemptPolicy.lockoutMillisAfter(100))
        assertEquals(listOf(5, 10, 15), (1..15).filter { AttemptPolicy.shouldReport(it) })
        assertEquals(3, AttemptPolicy.attemptsBeforeLockout(1))
        assertEquals(0, AttemptPolicy.attemptsBeforeLockout(7))
    }
}
