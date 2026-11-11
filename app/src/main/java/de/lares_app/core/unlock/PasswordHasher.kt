/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 hashes in the self-describing format
 * `pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>`, so the cost can be raised later
 * without breaking existing hashes.
 */
class PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(secret: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(secret, salt, iterations)
        return listOf(PREFIX, iterations.toString(), b64(salt), b64(hash)).joinToString("$")
    }

    fun verify(secret: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iter = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(secret, salt, iter), expected)
    }

    private fun derive(secret: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iter, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        /** Balance between phone CPU time (~0.3 s) and offline attack cost; stored per hash. */
        const val DEFAULT_ITERATIONS = 310_000
        private const val PREFIX = "pbkdf2-sha256"
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
    }
}
