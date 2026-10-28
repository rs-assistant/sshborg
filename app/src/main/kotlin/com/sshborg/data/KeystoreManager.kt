package com.sshborg.data

import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Read-only legacy v1 decryptor, used only after explicit migration authorization.
 * Never creates, replaces, or deletes a key. Production connection reads use v2 only.
 *
 * Blob format: Base64(IV_12_bytes || ciphertext)
 */
internal object KeystoreManager {

    private const val KEY_ALIAS     = "sshborg_key_encryption_v1"
    private const val KEYSTORE      = "AndroidKeyStore"
    private const val ALGORITHM     = "AES/GCM/NoPadding"
    private const val GCM_IV_LEN    = 12
    private const val GCM_TAG_BITS  = 128

    /** Decrypts a pre-existing v1 blob without any key-creation fallback. */
    fun decrypt(blob: String): ByteArray {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size >= GCM_IV_LEN + GCM_TAG_BITS / 8) { "Invalid encrypted credential blob" }
        val iv         = raw.copyOfRange(0, GCM_IV_LEN)
        val ciphertext = raw.copyOfRange(GCM_IV_LEN, raw.size)
        val key        = KeyStore.getInstance(KEYSTORE).also { it.load(null) }
            .getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Keystore encryption key is unavailable; restore or re-import credentials")
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

}
