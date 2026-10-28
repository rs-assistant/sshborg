package com.sshborg.data

/** Production v2 operations. The legacy reader is exposed only to explicit migration. */
internal object ProtectedCredentials {
    private val protection = CredentialProtection(
        KeystoreV2::prepareEncrypt,
        KeystoreV2::prepareDecrypt,
        ConnectionAuthorization::authorizeCipher,
    )

    val migration = CredentialMigration(
        authorizeLegacyRead = ConnectionAuthorization::requireFreshAuthentication,
        decryptLegacy = KeystoreManager::decrypt,
        encryptV2 = protection::encrypt,
        decryptV2 = protection::decrypt,
    )

    suspend fun encrypt(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try { protection.encrypt(bytes) } finally { bytes.fill(0) }
    }

    suspend fun decrypt(blob: String): String {
        check(blob.startsWith("v2:")) {
            "Stored credential requires explicit migration in Settings or re-import"
        }
        val bytes = protection.decrypt(blob)
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }
}
