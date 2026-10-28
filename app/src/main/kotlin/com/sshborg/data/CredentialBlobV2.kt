package com.sshborg.data

import java.util.Base64

/**
 * Pure, versioned AES-GCM payload codec.
 * This class only encodes IV + ciphertext and NEVER performs authentication or
 * decrypts credentials. Callers must use a Cipher authorized by BiometricPrompt.
 */
internal object CredentialBlobV2 {
    private const val PREFIX = "v2:"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 16

    fun encode(iv: ByteArray, ciphertextAndTag: ByteArray): String {
        require(iv.size == IV_SIZE) { "Invalid AES-GCM IV length" }
        require(ciphertextAndTag.size >= TAG_SIZE) { "Missing AES-GCM tag" }
        return PREFIX + Base64.getEncoder().encodeToString(iv + ciphertextAndTag)
    }

    fun decode(blob: String): Pair<ByteArray, ByteArray> {
        require(blob.startsWith(PREFIX)) { "Unsupported credential format" }
        val payload = blob.removePrefix(PREFIX)
        require(payload.isNotEmpty()) { "Empty credential blob" }
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed credential encoding", e)
        }
        require(bytes.size >= IV_SIZE + TAG_SIZE) { "Truncated AES-GCM credential blob" }
        return bytes.copyOfRange(0, IV_SIZE) to bytes.copyOfRange(IV_SIZE, bytes.size)
    }
}
