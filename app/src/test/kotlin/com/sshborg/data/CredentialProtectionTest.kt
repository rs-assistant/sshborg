package com.sshborg.data

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CredentialProtectionTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun encryptCipher() = Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, key)
    }
    private fun decryptCipher(iv: ByteArray, decryptKey: SecretKey = key) =
        Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, decryptKey, GCMParameterSpec(128, iv))
        }
    private fun protection(authorize: suspend (Cipher) -> Cipher?) =
        CredentialProtection(::encryptCipher, { decryptCipher(it) }, authorize)

    @Test fun `each encryption and decryption authorizes a distinct exact cipher`() = runBlocking {
        val authorized = mutableListOf<Cipher>()
        val store = protection { authorized += it; it }
        val bytes = "  é 中文 🔑\n".toByteArray() + byteArrayOf(0, -1)
        val blob = store.encrypt(bytes)
        assertTrue(blob.startsWith("v2:"))
        assertArrayEquals(bytes, store.decrypt(blob))
        assertArrayEquals(bytes, store.decrypt(blob))
        assertEquals(3, authorized.size)
        assertEquals(3, authorized.toSet().size)
    }

    @Test fun `denied encryption returns no blob`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { protection { null }.encrypt("test".toByteArray()) }
        }
    }

    @Test fun `denied decryption returns no plaintext`() {
        val blob = runBlocking { protection { it }.encrypt("test".toByteArray()) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { protection { null }.decrypt(blob) }
        }
    }

    @Test fun `a different authorized cipher cannot encrypt or decrypt this operation`() {
        val blob = runBlocking { protection { it }.encrypt("test".toByteArray()) }
        val store = protection { encryptCipher() }
        assertThrows(IllegalStateException::class.java) { runBlocking { store.encrypt(byteArrayOf(1)) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { store.decrypt(blob) } }
    }

    @Test fun `legacy unknown and damaged formats cannot reach key lookup or authorization`() {
        val store = CredentialProtection(
            { error("Must not create a key") },
            { error("Must not look up a key") },
            { error("Must not request authorization") },
        )
        for (blob in listOf("", "legacy ciphertext", "v1:AA==", "v3:AA==", "v2:", "v2:AA==", "v2:!")) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.decrypt(blob) } }
        }
    }

    @Test fun `missing decrypt key propagates without creating an encryption key`() {
        val blob = runBlocking { protection { it }.encrypt(byteArrayOf(1)) }
        val failure = IllegalStateException("test missing key")
        val store = CredentialProtection({ error("Must not create a replacement") }, { throw failure }, { error("No cipher") })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { runBlocking { store.decrypt(blob) } })
    }

    @Test fun `authorization cancellation propagates`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { protection { throw CancellationException("test") }.encrypt(byteArrayOf(1)) }
        }
    }

    @Test fun `cancellation immediately after successful authorization prevents encryption`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = coroutineContext[Job]!!
                protection { job.cancel(); it }.encrypt(byteArrayOf(1))
            }
        }
    }

    @Test fun `cancellation immediately after successful authorization prevents decryption`() {
        val blob = runBlocking { protection { it }.encrypt(byteArrayOf(1)) }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = coroutineContext[Job]!!
                protection { job.cancel(); it }.decrypt(blob)
            }
        }
    }

    @Test fun `wrong key authentication failure never falls back to a legacy decryptor`() {
        val blob = runBlocking { protection { it }.encrypt(byteArrayOf(1)) }
        val wrong = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = CredentialProtection({ error("No replacement key") }, { decryptCipher(it, wrong) }, { it })
        assertThrows(AEADBadTagException::class.java) { runBlocking { store.decrypt(blob) } }
    }
}
