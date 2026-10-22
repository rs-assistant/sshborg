package com.sshborg.data

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Real JCA AES-GCM operations; Android Keystore authorization still requires device tests. */
class CredentialCipherV2Test {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        return CredentialBlobV2.decode(CredentialCipherV2.sealAuthenticated(cipher, plaintext))
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        CredentialCipherV2.openAuthenticated(CredentialCipherV2.prepareDecrypt(iv) { key }, ciphertext)

    @Test fun `empty and binary credentials round trip with a real AES key`() {
        val key = key()
        for (size in listOf(0, 1, 16, 1024)) {
            val original = ByteArray(size) { it.toByte() }
            val (iv, ciphertext) = encrypt(key, original)
            assertEquals(12, iv.size)
            assertEquals(original.size + 16, ciphertext.size)
            assertArrayEquals(original, decrypt(key, iv, ciphertext))
        }
    }

    @Test fun `UTF8 password bytes including whitespace are preserved`() {
        val key = key()
        val original = "  test-é-密碼-🔑  ".toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = encrypt(key, original)
        assertArrayEquals(original, decrypt(key, iv, ciphertext))
    }

    @Test fun `a different key never yields credential bytes`() {
        val (iv, ciphertext) = encrypt(key(), "test secret".toByteArray())
        val wrongKey = key()
        assertThrows(AEADBadTagException::class.java) { decrypt(wrongKey, iv, ciphertext) }
    }

    @Test fun `changes to ciphertext or authentication tag are rejected`() {
        val key = key()
        val (iv, ciphertext) = encrypt(key, ByteArray(64) { it.toByte() })
        for (index in listOf(0, 32, ciphertext.lastIndex)) {
            val changed = ciphertext.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertThrows(AEADBadTagException::class.java) { decrypt(key, iv, changed) }
        }
    }

    @Test fun `changing the stored IV is rejected`() {
        val key = key()
        val (iv, ciphertext) = encrypt(key, "test secret".toByteArray())
        val changed = iv.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { decrypt(key, changed, ciphertext) }
    }

    @Test fun `IV and ciphertext from different records cannot be mixed`() {
        val key = key()
        val (firstIv, firstCiphertext) = encrypt(key, "first test secret".toByteArray())
        val (secondIv, secondCiphertext) = encrypt(key, "second test secret".toByteArray())
        assertThrows(AEADBadTagException::class.java) { decrypt(key, firstIv, secondCiphertext) }
        assertThrows(AEADBadTagException::class.java) { decrypt(key, secondIv, firstCiphertext) }
    }

    @Test fun `a truncated encrypted record cannot return partial plaintext`() {
        val key = key()
        val (iv, ciphertext) = encrypt(key, ByteArray(64))
        assertThrows(AEADBadTagException::class.java) {
            decrypt(key, iv, ciphertext.copyOf(ciphertext.size - 1))
        }
    }

    @Test fun `ciphertext shorter than a complete tag is refused`() {
        val key = key()
        val (iv, _) = encrypt(key, byteArrayOf())
        for (length in listOf(0, 15)) {
            assertThrows(IllegalArgumentException::class.java) { decrypt(key, iv, ByteArray(length)) }
        }
    }

    @Test fun `non GCM algorithms cannot seal or open a v2 credential`() {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialCipherV2.sealAuthenticated(cipher, byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialCipherV2.openAuthenticated(cipher, ByteArray(16))
        }
    }

    @Test fun `invalid IV lengths are refused before querying the keystore`() {
        for (length in listOf(0, 11, 13, 16)) {
            assertThrows(IllegalArgumentException::class.java) {
                CredentialCipherV2.prepareDecrypt(ByteArray(length)) { error("Must not query the keystore") }
            }
        }
    }

    @Test fun `an externally initialized cipher with the wrong IV length is refused`() {
        val cipher = Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, ByteArray(16)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialCipherV2.sealAuthenticated(cipher, byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialCipherV2.openAuthenticated(cipher, ByteArray(16))
        }
    }

    @Test fun `a missing key fails after one read without creating a replacement`() {
        var lookups = 0
        assertThrows(IllegalStateException::class.java) {
            CredentialCipherV2.prepareDecrypt(ByteArray(12)) { lookups++; null }
        }
        assertEquals(1, lookups)
    }

    @Test fun `decrypt uses the key from its single lookup without checking again`() {
        val key = key()
        val original = "test secret".toByteArray()
        val (iv, ciphertext) = encrypt(key, original)
        var lookups = 0
        val cipher = CredentialCipherV2.prepareDecrypt(iv) {
            lookups++
            if (lookups == 1) key else null
        }
        assertArrayEquals(original, CredentialCipherV2.openAuthenticated(cipher, ciphertext))
        assertEquals(1, lookups)
    }

    @Test fun `keystore lookup errors propagate without a fallback`() {
        val failure = IllegalStateException("test invalidated key")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            CredentialCipherV2.prepareDecrypt(ByteArray(12)) { throw failure }
        })
    }
}
