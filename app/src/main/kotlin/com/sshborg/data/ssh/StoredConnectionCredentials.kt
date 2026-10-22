package com.sshborg.data.ssh

import com.sshborg.data.db.HostEntity
import com.sshborg.data.db.SshKeyEntity

/**
 * Shared stored-credential policy for terminal and SFTP connections, including jump hosts.
 * Call only after fresh connection authorization. A null result means no saved credential;
 * an unusable configured credential throws instead of permitting another authentication method.
 */
internal class StoredConnectionCredentials(
    private val findKey: suspend (Long) -> SshKeyEntity?,
    private val decrypt: suspend (String) -> String,
) {
    suspend fun resolve(host: HostEntity): SshAuth? {
        host.keyId?.let { id ->
            val key = checkNotNull(findKey(id)) {
                "Selected SSH key is unavailable; re-import it before connecting"
            }
            val blob = key.encryptedBlob
            check(!blob.isNullOrBlank()) {
                "Stored SSH key is not encrypted; re-import it before connecting"
            }
            val pem = decrypt(blob)
            check(pem.isNotBlank()) { "Stored SSH key is empty; re-import it before connecting" }
            return SshAuth.PublicKey(pem)
        }

        host.encryptedPassword?.let { blob ->
            check(blob.isNotBlank()) { "Stored encrypted password is invalid; enter it again" }
            return SshAuth.Password(decrypt(blob))
        }

        check(host.password.isNullOrEmpty()) {
            "Stored password uses legacy plaintext storage; enter it again in the host editor"
        }
        return null
    }
}
