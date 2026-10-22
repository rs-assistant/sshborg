package com.sshborg.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM operations for v2 credentials. Android prompt authorization is the caller's job;
 * these functions must only finalize the exact cipher returned by successful CryptoObject auth.
 */
internal object CredentialCipherV2 {
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** The lookup is read-only: decrypting an existing record must never create a key. */
    fun prepareDecrypt(iv: ByteArray, findExistingKey: () -> SecretKey?): Cipher {
        require(iv.size == 12) { "Invalid AES-GCM IV" }
        val key = checkNotNull(findExistingKey()) {
            "Authenticated credential key is missing; re-import required"
        }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    fun sealAuthenticated(cipher: Cipher, plaintext: ByteArray): String {
        require(cipher.algorithm == TRANSFORMATION) { "Unexpected cipher algorithm" }
        val iv = cipher.iv
        require(iv != null && iv.size == 12) { "Invalid AES-GCM IV" }
        return CredentialBlobV2.encode(iv, cipher.doFinal(plaintext))
    }

    fun openAuthenticated(cipher: Cipher, ciphertext: ByteArray): ByteArray {
        require(cipher.algorithm == TRANSFORMATION) { "Unexpected cipher algorithm" }
        require(cipher.iv?.size == 12) { "Invalid AES-GCM IV" }
        require(ciphertext.size >= 16) { "Invalid AES-GCM ciphertext" }
        return cipher.doFinal(ciphertext)
    }
}
