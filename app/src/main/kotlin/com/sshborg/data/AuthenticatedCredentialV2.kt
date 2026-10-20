package com.sshborg.data

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Experimental v2 credential service. Not connected to production SSH/SFTP paths.
 *
 * Each operation uses a newly initialized Cipher and must receive that exact Cipher
 * back from the Android BiometricPrompt CryptoObject callback. No cached grants.
 * API 29 fails closed in AuthenticatedCipherPrompt until a compatible path is tested.
 */
internal object AuthenticatedCredentialV2 {
    suspend fun encrypt(activity: FragmentActivity, plaintext: ByteArray): String? {
        val cipher = withContext(Dispatchers.IO) { KeystoreV2.prepareEncrypt() }
        val authorized = AuthenticatedCipherPrompt.authorize(activity, cipher) ?: return null
        return withContext(Dispatchers.Default) {
            KeystoreV2.sealAuthenticated(authorized, plaintext)
        }
    }

    suspend fun decrypt(activity: FragmentActivity, blob: String): ByteArray? {
        val (iv, encrypted) = CredentialBlobV2.decode(blob)
        val cipher = withContext(Dispatchers.IO) { KeystoreV2.prepareDecrypt(iv) }
        val authorized = AuthenticatedCipherPrompt.authorize(activity, cipher) ?: return null
        return withContext(Dispatchers.Default) {
            KeystoreV2.openAuthenticated(authorized, encrypted)
        }
    }
}
