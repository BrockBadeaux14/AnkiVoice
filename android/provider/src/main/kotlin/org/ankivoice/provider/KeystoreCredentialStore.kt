package org.ankivoice.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The OpenRouter key, wrapped by a non-exportable Android Keystore key and held in
 * app-private storage.
 *
 * The Keystore key never leaves the device's keystore, so a copied preferences file
 * cannot be unwrapped elsewhere. `:app` excludes this file from backup and device
 * transfer, and the app manifest sets `allowBackup=false`.
 *
 * The key is entered at runtime. It is in no `BuildConfig` field, resource or asset; it
 * is never written to a log or shown again in the UI, and [CredentialPolicy.redact]
 * guards anything the diagnostics record.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? {
        val stored = preferences.getString(VALUE, null) ?: return null
        val iv = preferences.getString(IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key() ?: return null, GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(stored)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // A rotated or invalidated Keystore key means no usable credential, never a crash.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun save(key: String): Boolean {
        val trimmed = key.trim()
        if (!CredentialPolicy.valid(trimmed)) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key() ?: createKey())
            val wrapped = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            preferences.edit()
                .putString(VALUE, encode(wrapped))
                .putString(IV, encode(cipher.iv))
                .commit()
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    override fun clear() {
        preferences.edit().remove(VALUE).remove(IV).commit()
        try {
            keystore().deleteEntry(ALIAS)
        } catch (_: GeneralSecurityException) {
            // Nothing to delete; the ciphertext is already gone.
        }
    }

    override fun present(): Boolean = preferences.contains(VALUE)

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey? = try {
        keystore().getKey(ALIAS, null) as? SecretKey
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val FILE = "provider"
        const val VALUE = "openrouter_key"
        const val IV = "openrouter_key_iv"
        const val ALIAS = "ankivoice.provider.credential"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
