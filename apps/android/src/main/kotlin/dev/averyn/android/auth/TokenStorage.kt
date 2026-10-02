package dev.averyn.android.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The OIDC session (refresh token included) as an AES-GCM encrypted file in the app's private storage; the key
 * lives in the Android Keystore and never leaves it. Layout: 12-byte IV, then the ciphertext.
 */
class TokenStorage(
    private val file: File,
) {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    fun write(text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        file.writeBytes(cipher.iv + cipher.doFinal(text.toByteArray()))
    }

    /** Null if nothing is stored or it cannot be decrypted (e.g. the Keystore key was lost): the user signs in again. */
    @Synchronized
    fun read(): String? =
        try {
            val bytes = file.readBytes()
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
                }
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: Exception) {
            null
        }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private companion object {
        const val KEY_ALIAS = "averyn_auth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
