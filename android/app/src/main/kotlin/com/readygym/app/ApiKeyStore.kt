package com.readygym.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Stores one bearer credential (now the ReadyGym account token; [slot] names it) in the Android
 * Keystore, encrypted at rest.
 *
 * Why not plain SharedPreferences: an API key is a bearer credential that bills its owner. Storing
 * it in the clear would put it in every backup the OS or a third party app takes of this app's data
 * directory. The key material never leaves the Keystore; only a ciphertext does.
 *
 * Why not EncryptedSharedPreferences: it would pull in androidx.security-crypto, which is in
 * maintenance mode and brings a Tink dependency for what is a single AES-GCM string. This uses the
 * platform Keystore directly and adds no dependency.
 *
 * The key is bound to this app and this device. It does not survive an app-data wipe or move to
 * another device, which is the correct behaviour for a credential: the user re-pastes it.
 */
internal class ApiKeyStore(context: Context, slot: String = "deepseek_key") {
    private val keyCiphertext = "${slot}_ciphertext"
    private val keyIv = "${slot}_iv"
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Encrypt and persist. The plaintext is not logged and not written anywhere else. */
    fun save(key: String) {
        if (key.isBlank()) { clear(); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(keyCiphertext, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(keyIv, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Returns the stored key, or null when there is none or it can no longer be decrypted (an
     * app-data restore onto a new device, or a Keystore reset). A null is not an error: the caller
     * asks the user to paste the key again.
     */
    fun load(): String? {
        val encrypted = prefs.getString(keyCiphertext, null) ?: return null
        val iv = prefs.getString(keyIv, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Ciphertext we cannot decrypt is worse than none: drop it so the UI stops pretending.
            clear()
            null
        }
    }

    fun hasKey(): Boolean = prefs.contains(keyCiphertext)

    fun clear() {
        prefs.edit().remove(keyCiphertext).remove(keyIv).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication requirement: the app reads the key to make a request the
                // user just triggered, not to unlock a vault, and requiring biometrics per message
                // would make the chat unusable.
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "readygym.deepseek.api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFS = "readygym.credentials"
    }
}
