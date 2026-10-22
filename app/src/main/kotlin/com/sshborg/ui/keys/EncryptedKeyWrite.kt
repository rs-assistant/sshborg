package com.sshborg.ui.keys

import com.sshborg.data.db.SshKeyEntity
import com.sshborg.data.ssh.ImportedKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Encryption must finish successfully before the database is allowed to see a new key. */
internal suspend fun persistEncryptedKey(
    label: String,
    key: ImportedKey,
    encrypt: suspend (String) -> String,
    save: suspend (SshKeyEntity) -> Unit,
) {
    require(key.pem.isNotBlank()) { "Private key is empty" }
    currentCoroutineContext().ensureActive()
    val blob = encrypt(key.pem)
    check(blob.isNotBlank()) { "Encrypted private key is empty" }
    currentCoroutineContext().ensureActive()
    save(SshKeyEntity(label = label, keyType = key.keyType, encryptedBlob = blob, publicKey = key.publicKey))
}
