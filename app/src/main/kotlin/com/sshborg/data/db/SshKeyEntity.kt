package com.sshborg.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** Key type: RSA, ECDSA, ED25519 */
    val keyType: String,
    /**
     * Legacy plaintext field, retained for schema compatibility only. New writes must leave it
     * empty, and connection code must never use it as an authentication fallback.
     */
    val privateKeyPem: String = "",
    /** OpenSSH public key string (e.g. "ssh-ed25519 AAAA...") */
    val publicKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** New writes use v2:Base64(IV||ciphertext||tag); unprefixed v1 blobs require explicit migration. */
    val encryptedBlob: String? = null,
)
