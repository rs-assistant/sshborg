package com.sshborg.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyInfo
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Per-operation Android Keystore AES key for versioned credentials (Android 11+).
 * It requires a BiometricPrompt CryptoObject and authenticated Cipher before doFinal().
 * Do not use the legacy v1 key alias for v2 records.
 */
internal object KeystoreV2 {
    private const val ALIAS = "sshborg_credential_auth_v2"
    private const val STORE = "AndroidKeyStore"

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing != null) {
            check(existing is SecretKey) { "Authenticated credential alias is not an AES key" }
            return requireProtectedKey(existing)
        }
        check(!store.containsAlias(ALIAS)) { "Authenticated credential key is unavailable; re-import required" }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            .apply { init(keySpecFor(ALIAS)) }.generateKey().let(::requireProtectedKey)
    }

    /** Shared with device tests, which use a separate disposable alias. */
    internal fun keySpecFor(alias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()

    internal fun requireProtectedKey(key: SecretKey): SecretKey {
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds in -1..0 &&
            info.userAuthenticationType == (KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL) &&
            info.keySize == 256 && info.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)) &&
            info.encryptionPaddings.contentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE))) {
            "Authenticated credential key has an incompatible protection policy; no key was replaced"
        }
        return key
    }

    /** Must be passed as BiometricPrompt.CryptoObject; do not call doFinal before auth. */
    fun prepareDecrypt(iv: ByteArray): Cipher =
        CredentialCipherV2.prepareDecrypt(iv) {
            (KeyStore.getInstance(STORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey)
                ?.let(::requireProtectedKey)
        }

    /** Encryption also requires a fresh authorized Cipher for this key. */
    fun prepareEncrypt(): Cipher = Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }
}
