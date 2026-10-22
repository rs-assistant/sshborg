package com.sshborg.ui.hosts

/**
 * Editing host metadata never requires decrypting its saved password. Keep its exact ciphertext
 * unless the user supplies a replacement, removes it, or switches to SSH-key authentication.
 */
internal class HostPasswordEdit(
    val originalEncrypted: String? = null,
    hasLegacyPassword: Boolean = false,
) {
    val hasSavedPassword = originalEncrypted != null || hasLegacyPassword
    val needsReentry = originalEncrypted == null && hasLegacyPassword

    suspend fun encryptedForSave(
        replacement: String,
        removeSaved: Boolean,
        useKey: Boolean,
        encrypt: suspend (String) -> String,
    ): String? {
        if (useKey || removeSaved) return null
        if (replacement.isNotEmpty()) {
            return encrypt(replacement).also {
                check(it.isNotBlank()) { "Password encryption did not produce a credential" }
            }
        }
        check(!needsReentry) { "Re-enter or explicitly remove the legacy password before saving" }
        return originalEncrypted
    }
}
