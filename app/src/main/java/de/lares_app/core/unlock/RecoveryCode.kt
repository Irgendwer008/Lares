/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.unlock

import java.security.SecureRandom

/**
 * Recovery code: 20 characters of Crockford Base32 (100 bits), shown once as
 * `XXXXX-XXXXX-XXXXX-XXXXX`. Input is normalized, so case, separators and the look-alikes
 * O/0 and I/L/1 don't matter.
 */
object RecoveryCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 20
    private const val GROUP = 5

    fun generate(random: SecureRandom = SecureRandom()): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .chunked(GROUP) { it.joinToString("") }
            .joinToString("-")

    /** Returns the canonical form (no separators), or null if it can't be a recovery code. */
    fun normalize(input: String): String? {
        val s = input.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> it
                }
            }
            .joinToString("")
        return s.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }
}
