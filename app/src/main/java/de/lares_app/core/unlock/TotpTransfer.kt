/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Moves the TOTP secret to another phone inside the rules export, so the guardian's authenticator
 * app keeps working there. The secret is encrypted with a key derived from the recovery code
 * (100 random bits – not guessable, only the guardian has it), so the exported file alone is
 * useless. Created while the recovery code is known (TOTP setup); the recovery code itself is
 * never stored.
 *
 * Format: `lares-totp-v1$<salt>$<iv>$<ciphertext>` (Base64), AES-256-GCM, key =
 * SHA-256(salt ‖ canonical recovery code).
 */
object TotpTransfer {
    private const val PREFIX = "lares-totp-v1"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** @param recoveryCanonical the recovery code in canonical form ([RecoveryCode.normalize]). */
    fun seal(secret: ByteArray, recoveryCanonical: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(salt, recoveryCanonical), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(PREFIX.encodeToByteArray())
        }
        val enc = Base64.getEncoder()
        return listOf(PREFIX, enc.encodeToString(salt), enc.encodeToString(iv), enc.encodeToString(cipher.doFinal(secret))).joinToString("$")
    }

    /** @return the secret, or null if the recovery code is wrong or the package is damaged. */
    fun open(sealed: String, recoveryCanonical: String): ByteArray? = runCatching {
        val parts = sealed.split('$')
        require(parts.size == 4 && parts[0] == PREFIX)
        val dec = Base64.getDecoder()
        val (salt, iv, data) = Triple(dec.decode(parts[1]), dec.decode(parts[2]), dec.decode(parts[3]))
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(salt, recoveryCanonical), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(PREFIX.encodeToByteArray())
        }.doFinal(data)
    }.getOrNull()

    /** Looks like a package of this format (for a quick check before asking for the code). */
    fun isPackage(text: String?): Boolean = text != null && text.startsWith("$PREFIX$") && text.count { it == '$' } == 3

    private fun key(salt: ByteArray, recoveryCanonical: String): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").run { update(salt); digest(recoveryCanonical.encodeToByteArray()) }, "AES")
}
