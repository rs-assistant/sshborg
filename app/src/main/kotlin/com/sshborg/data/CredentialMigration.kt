package com.sshborg.data

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun isLegacyEncryptedCredential(blob: String): Boolean = try {
    // A v1 value is unprefixed Base64(12-byte IV || ciphertext || 16-byte GCM tag).
    Base64.getDecoder().decode(blob).size >= 28
} catch (_: IllegalArgumentException) {
    false
}

/** No storage change until explicit consent, fresh authorization and a verified round trip. */
internal class CredentialMigration(
    private val authorizeLegacyRead: suspend () -> Boolean,
    private val decryptLegacy: suspend (String) -> ByteArray,
    private val encryptV2: suspend (ByteArray) -> String,
    private val decryptV2: suspend (String) -> ByteArray,
) {
    suspend fun migrate(
        original: String,
        confirmed: Boolean,
        replaceIfUnchanged: suspend (expected: String, replacement: String) -> Boolean,
    ) {
        check(confirmed) { "Explicit migration confirmation is required" }
        require(isLegacyEncryptedCredential(original)) { "Only existing v1 encrypted credentials can be migrated" }
        currentCoroutineContext().ensureActive()
        check(authorizeLegacyRead()) { "Migration authorization was cancelled or unavailable" }
        currentCoroutineContext().ensureActive()
        val plaintext = decryptLegacy(original)
        try {
            currentCoroutineContext().ensureActive()
            val replacement = encryptV2(plaintext)
            currentCoroutineContext().ensureActive()
            CredentialBlobV2.decode(replacement)
            val verified = decryptV2(replacement)
            try {
                check(MessageDigest.isEqual(plaintext, verified)) { "Credential migration verification failed" }
            } finally {
                verified.fill(0)
            }
            currentCoroutineContext().ensureActive()
            check(replaceIfUnchanged(original, replacement)) {
                "Credential changed during migration; reload and try again"
            }
        } finally {
            plaintext.fill(0)
        }
    }
}
