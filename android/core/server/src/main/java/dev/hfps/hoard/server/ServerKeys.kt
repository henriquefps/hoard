package dev.hfps.hoard.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the device's `rvk1_` key lives (ADR 0013 "Device keys": shown once, kept in the Android Keystore). */
interface KeyVault {
    fun get(): String?
    fun set(apiKey: String)
    fun clear()
}

/** For tests and previews. */
class MemoryKeyVault(private var value: String? = null) : KeyVault {
    override fun get(): String? = value
    override fun set(apiKey: String) {
        value = apiKey
    }

    override fun clear() {
        value = null
    }
}

/**
 * The API key encrypted with a non-exportable AES-256-GCM key in the Android Keystore (as the SMB passwords,
 * `CredentialStore`); only the ciphertext is in private SharedPreferences, excluded from backups. Never in Room, never
 * in a log.
 */
class KeystoreKeyVault(context: Context) : KeyVault {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cached: String? = null

    @Synchronized
    override fun get(): String? {
        cached?.let { return it }
        val encoded = prefs.getString(ENTRY, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = blob[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, ivLength))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength), Charsets.UTF_8).also { cached = it }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Dropping an unreadable server key: ${e.javaClass.simpleName}")
            clear()
            null
        }
    }

    @Synchronized
    override fun set(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val blob = ByteBuffer.allocate(1 + cipher.iv.size + sealed.size).put(cipher.iv.size.toByte()).put(cipher.iv).put(sealed).array()
        check(prefs.edit().putString(ENTRY, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) { "Could not save the server key" }
        cached = apiKey
    }

    @Synchronized
    override fun clear() {
        cached = null
        prefs.edit().remove(ENTRY).commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "HoardServer"
        private const val PREFS = "hoard_server"
        private const val ENTRY = "apiKey"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.hfps.hoard.server.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "hoard-server-key".toByteArray(Charsets.UTF_8)
    }
}
