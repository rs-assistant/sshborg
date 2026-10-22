package com.sshborg.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.crypto.Cipher

/** Each operation owns a new cipher and accepts only authorization of that exact object. */
internal class CredentialProtection(
    private val prepareEncrypt: () -> Cipher,
    private val prepareDecrypt: (ByteArray) -> Cipher,
    private val authorize: suspend (Cipher) -> Cipher?,
) {
    suspend fun encrypt(plaintext: ByteArray): String {
        currentCoroutineContext().ensureActive()
        val cipher = prepareEncrypt()
        requireAuthorized(cipher)
        return CredentialCipherV2.sealAuthenticated(cipher, plaintext)
    }

    suspend fun decrypt(blob: String): ByteArray {
        // Unknown/legacy formats never reach a key lookup or fall back to v1 decryption.
        val (iv, ciphertext) = CredentialBlobV2.decode(blob)
        currentCoroutineContext().ensureActive()
        val cipher = prepareDecrypt(iv)
        requireAuthorized(cipher)
        return CredentialCipherV2.openAuthenticated(cipher, ciphertext)
    }

    private suspend fun requireAuthorized(cipher: Cipher) {
        check(authorize(cipher) === cipher) { "Credential authorization was cancelled or unavailable" }
        currentCoroutineContext().ensureActive()
    }
}
