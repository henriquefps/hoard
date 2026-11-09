package dev.hfps.hoard.smb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Machine credentials encrypted with a non-exportable AES-256-GCM key in the Android Keystore.
 * Only the ciphertext lives in app-private SharedPreferences (excluded from backups, see
 * `res/xml/data_extraction_rules.xml`). The machine id is the GCM associated data, so a blob cannot
 * be swapped onto another machine. Nothing here ever returns a password to JS.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun set(machineId: String, username: String, password: String, domain: String?) {
        val json = JSONObject()
            .put("u", username)
            .put("p", password)
            .put("d", domain ?: JSONObject.NULL)
            .toString()
        val plain = json.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(machineId.toByteArray(Charsets.UTF_8))
            val sealed = cipher.doFinal(plain)
            val blob = ByteBuffer.allocate(1 + cipher.iv.size + sealed.size)
                .put(cipher.iv.size.toByte()).put(cipher.iv).put(sealed).array()
            if (!prefs.edit().putString(keyFor(machineId), Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
                smbFail(ErrorCode.UNKNOWN, "Could not save the credentials")
            }
        } finally {
            plain.fill(0)
        }
    }

    fun has(machineId: String): Boolean = prefs.contains(keyFor(machineId))

    @Synchronized
    fun delete(machineId: String) {
        prefs.edit().remove(keyFor(machineId)).commit()
    }

    /**
     * Decrypt the machine's credentials, or null when none are stored. A blob that no longer
     * decrypts (key invalidated, data restored from elsewhere) is dropped: the user re-enters it.
     */
    @Synchronized
    fun get(machineId: String): SmbCredentials? {
        val encoded = prefs.getString(keyFor(machineId), null) ?: return null
        var plain: ByteArray? = null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = blob[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, ivLength))
            cipher.updateAAD(machineId.toByteArray(Charsets.UTF_8))
            plain = cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
            val json = JSONObject(String(plain, Charsets.UTF_8))
            SmbCredentials(
                username = json.getString("u"),
                password = json.getString("p").toCharArray(),
                domain = json.optString("d").takeIf { !json.isNull("d") && it.isNotEmpty() },
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Dropping unreadable credentials for $machineId: ${e.javaClass.simpleName}")
            delete(machineId)
            null
        } finally {
            plain?.fill(0)
        }
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

    private fun keyFor(machineId: String) = "machine:$machineId"

    companion object {
        private const val TAG = "HoardSmb"
        private const val PREFS = "hoard_credentials"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.hfps.hoard.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
