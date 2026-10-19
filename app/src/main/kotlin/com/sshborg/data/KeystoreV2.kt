package com.sshborg.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Experimental per-operation Android Keystore AES key.
 *
 * This module is intentionally NOT wired into credential storage or SSH connection flows yet.
 * It requires a BiometricPrompt CryptoObject and authenticated Cipher before doFinal().
 * Do not use the legacy v1 key alias for v2 records.
 *
 * DEVICE_CREDENTIAL with CryptoObject requires separate compatibility handling on API 29.
 */
internal object KeystoreV2 {
    private const val ALIAS = "sshborg_credential_auth_v2"
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            .apply { init(builder.build()) }.generateKey()
    }

    /** Must be passed as BiometricPrompt.CryptoObject; do not call doFinal before auth. */
    fun prepareDecrypt(iv: ByteArray): Cipher {
        require(iv.size == 12) { "Invalid AES-GCM IV" }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
    }

    /** Encryption also requires a fresh authorized Cipher for this key. */
    fun prepareEncrypt(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }
}
