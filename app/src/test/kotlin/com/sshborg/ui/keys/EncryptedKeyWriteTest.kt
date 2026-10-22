package com.sshborg.ui.keys

import com.sshborg.data.db.SshKeyEntity
import com.sshborg.data.ssh.ImportedKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EncryptedKeyWriteTest {
    private val key = ImportedKey("test private key\n", "ssh-ed25519 test-public-key", "ed25519")

    @Test fun `only ciphertext and public metadata reach the database`() = runBlocking {
        var stored: SshKeyEntity? = null
        persistEncryptedKey("test label", key, {
            assertEquals(key.pem, it)
            "encrypted blob"
        }) { stored = it }
        val record = checkNotNull(stored)
        assertEquals("", record.privateKeyPem)
        assertEquals("encrypted blob", record.encryptedBlob)
        assertEquals("test label", record.label)
        assertEquals(key.publicKey, record.publicKey)
        assertEquals(key.keyType, record.keyType)
    }

    @Test fun `an encryption failure never invokes persistence`() {
        val failure = IllegalStateException("test Keystore failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            runBlocking {
                persistEncryptedKey("test", key, { throw failure }) { fail("Must not save") }
            }
        })
    }

    @Test fun `empty ciphertext is refused without persistence`() {
        for (invalid in listOf("", " \n")) {
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    persistEncryptedKey("test", key, { invalid }) { fail("Must not save") }
                }
            }
        }
    }

    @Test fun `empty private material is refused before encryption`() {
        for (invalid in listOf("", " \n")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    persistEncryptedKey("test", key.copy(pem = invalid), { error("Must not encrypt") }) {
                        fail("Must not save")
                    }
                }
            }
        }
    }

    @Test fun `storage failure propagates without retry or plaintext fallback`() {
        val failure = IllegalStateException("test database failure")
        var saves = 0
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            runBlocking {
                persistEncryptedKey("test", key, { "encrypted blob" }) {
                    saves++
                    assertEquals("", it.privateKeyPem)
                    throw failure
                }
            }
        })
        assertEquals(1, saves)
    }

    @Test fun `cancellation in encryption never becomes a saved record`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                persistEncryptedKey("test", key, { throw CancellationException("test cancellation") }) {
                    fail("Must not save")
                }
            }
        }
    }

    @Test fun `cancellation while synchronous encryption completes is checked before persistence`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = coroutineContext[Job]!!
                persistEncryptedKey("test", key, {
                    job.cancel()
                    "encrypted blob"
                }) { fail("Must not save") }
            }
        }
    }

    @Test fun `cancellation in persistence propagates without a second write`() {
        var saves = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                persistEncryptedKey("test", key, { "encrypted blob" }) {
                    saves++
                    throw CancellationException("test cancellation")
                }
            }
        }
        assertEquals(1, saves)
    }
}
