package com.sshborg.data.ssh

import com.sshborg.data.db.HostEntity
import com.sshborg.data.db.SshKeyEntity
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class StoredConnectionCredentialsTest {
    private val host = HostEntity(label = "test", hostname = "test.invalid", username = "test")
    private val key = SshKeyEntity(
        id = 7, label = "test key", keyType = "ED25519", publicKey = "test public key",
        encryptedBlob = "encrypted key",
    )

    @Test fun `only an unconfigured credential permits an interactive password prompt`() {
        val resolver = StoredConnectionCredentials(
            findKey = { error("No key should be read") },
            decrypt = { error("No credential should be decrypted") },
        )
        assertNull(runBlocking { resolver.resolve(host) })
    }

    @Test fun `encrypted password is used without trimming its contents`() {
        val resolver = StoredConnectionCredentials(
            findKey = { error("Password auth must not read a key") },
            decrypt = { blob ->
                assertEquals("encrypted password", blob)
                "  test password  "
            },
        )
        assertEquals(SshAuth.Password("  test password  "), runBlocking {
            resolver.resolve(host.copy(encryptedPassword = "encrypted password"))
        })
    }

    @Test fun `legacy plaintext password is refused instead of requesting another method`() {
        val resolver = StoredConnectionCredentials({ null }) { error("Must not decrypt plaintext") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { resolver.resolve(host.copy(password = "legacy test password")) }
        }
    }

    @Test fun `empty encrypted password does not fall back to the legacy password`() {
        val resolver = StoredConnectionCredentials({ null }) { error("Invalid blob must be refused") }
        for (blob in listOf("", " ")) {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { resolver.resolve(host.copy(encryptedPassword = blob, password = "legacy")) }
            }
        }
    }

    @Test fun `password decryption failure remains an error even with a legacy password`() {
        val failure = AEADBadTagException("test tampered ciphertext")
        val resolver = StoredConnectionCredentials({ null }) { throw failure }
        val thrown = assertThrows(AEADBadTagException::class.java) {
            runBlocking {
                resolver.resolve(host.copy(encryptedPassword = "tampered", password = "legacy"))
            }
        }
        assertSame(failure, thrown)
    }

    @Test fun `missing assigned key cannot downgrade to a saved password`() {
        val resolver = StoredConnectionCredentials({ null }) { error("Must not decrypt a password") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { resolver.resolve(host.copy(keyId = key.id, encryptedPassword = "password")) }
        }
    }

    @Test fun `legacy assigned key cannot downgrade to a saved password`() {
        val legacyKey = key.copy(encryptedBlob = null, privateKeyPem = "legacy test private key")
        val resolver = StoredConnectionCredentials({ legacyKey }) { error("Must not decrypt a password") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { resolver.resolve(host.copy(keyId = key.id, encryptedPassword = "password")) }
        }
    }

    @Test fun `encrypted assigned key takes precedence over saved password and plaintext residue`() {
        val resolver = StoredConnectionCredentials(
            findKey = { id ->
                assertEquals(key.id, id)
                key.copy(privateKeyPem = "ignored plaintext residue")
            },
            decrypt = { blob ->
                assertEquals(key.encryptedBlob, blob)
                "test decrypted private key"
            },
        )
        assertEquals(SshAuth.PublicKey("test decrypted private key"), runBlocking {
            resolver.resolve(host.copy(keyId = key.id, encryptedPassword = "unused password"))
        })
    }

    @Test fun `key decryption failure cannot fall back to legacy key or saved password`() {
        val failure = AEADBadTagException("test wrong encryption key")
        val resolver = StoredConnectionCredentials(
            { key.copy(privateKeyPem = "legacy test private key") },
            { throw failure },
        )
        val thrown = assertThrows(AEADBadTagException::class.java) {
            runBlocking { resolver.resolve(host.copy(keyId = key.id, encryptedPassword = "password")) }
        }
        assertSame(failure, thrown)
    }

    @Test fun `blank encrypted key and blank decrypted key are refused`() {
        for (blob in listOf("", " ")) {
            val resolver = StoredConnectionCredentials({ key.copy(encryptedBlob = blob) }) {
                error("Blank ciphertext must not be decrypted")
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { resolver.resolve(host.copy(keyId = key.id)) }
            }
        }
        val resolver = StoredConnectionCredentials({ key }) { " " }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { resolver.resolve(host.copy(keyId = key.id)) }
        }
    }

    @Test fun `key lookup error is propagated instead of becoming absent credentials`() {
        val failure = IllegalStateException("test database failure")
        val resolver = StoredConnectionCredentials({ throw failure }) { error("Must not decrypt") }
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { resolver.resolve(host.copy(keyId = key.id)) }
        }
        assertSame(failure, thrown)
    }

    @Test fun `cancellation during key lookup is propagated`() {
        val failure = CancellationException("test cancelled lookup")
        val resolver = StoredConnectionCredentials({ throw failure }) { error("Must not decrypt") }
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { resolver.resolve(host.copy(keyId = key.id)) }
        }
        assertSame(failure, thrown)
    }
}
