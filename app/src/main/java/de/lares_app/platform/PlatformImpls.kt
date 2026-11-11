/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import de.lares_app.core.data.AppState
import de.lares_app.core.data.SecretCipher
import de.lares_app.core.data.StateStore
import de.lares_app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidClock(private val context: Context) : Clock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun bootId(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output: Base64(iv ‖ ciphertext). */
class KeystoreSecretCipher : SecretCipher {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    override fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
    }

    override fun decrypt(encoded: String): ByteArray {
        val bytes = Base64.getDecoder().decode(encoded)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "lares_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

private val stateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private object AppStateSerializer : Serializer<AppState> {
    override val defaultValue = AppState()

    override suspend fun readFrom(input: InputStream): AppState = try {
        stateJson.decodeFromString(AppState.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Cannot read app state", e)
    }

    override suspend fun writeTo(t: AppState, output: OutputStream) {
        output.write(stateJson.encodeToString(AppState.serializer(), t).encodeToByteArray())
    }
}

/** Settings + unlock state as one JSON file, updated atomically by DataStore. */
class DataStoreStateStore(context: Context) : StateStore {
    private val dataStore: DataStore<AppState> = DataStoreFactory.create(
        serializer = AppStateSerializer,
        produceFile = { File(context.filesDir, "datastore/lares_state.json") },
    )

    override val state: Flow<AppState> = dataStore.data

    override suspend fun current(): AppState = dataStore.data.first()

    override suspend fun update(transform: (AppState) -> AppState): AppState = dataStore.updateData { transform(it) }
}
