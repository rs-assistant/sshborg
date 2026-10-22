package com.sshborg.data

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CredentialMigrationTest {
    private val original = Base64.getEncoder().encodeToString(ByteArray(40) { it.toByte() })
    private val replacement = CredentialBlobV2.encode(ByteArray(12), ByteArray(32))

    private inner class Fixture(var failAt: String? = null) {
        var stored = original
        var commits = 0
        var opened: ByteArray? = null
        var verified: ByteArray? = null
        val events = mutableListOf<String>()
        var onEncrypt: () -> Unit = {}
        val migration = CredentialMigration(
            authorizeLegacyRead = { events += "authorize"; failAt != "authorize" },
            decryptLegacy = {
                events += "legacy"
                stage("legacy")
                "  test credential é\n".toByteArray().also { opened = it }
            },
            encryptV2 = {
                events += "encrypt"
                stage("encrypt")
                onEncrypt()
                if (failAt == "format") "not-v2" else replacement
            },
            decryptV2 = {
                events += "verify"
                stage("verify")
                (if (failAt == "mismatch") byteArrayOf(7) else opened!!.copyOf()).also { verified = it }
            },
        )
        private fun stage(name: String) {
            if (failAt == name) throw AEADBadTagException("test failure")
            if (failAt == "cancel-$name") throw CancellationException("test cancellation")
        }
        suspend fun migrate(confirmed: Boolean = true) {
            migration.migrate(original, confirmed) { expected, new ->
                events += "commit"
                commits++
                stage("commit")
                if (stored != expected || failAt == "conflict") false
                else { stored = new; true }
            }
        }
        fun assertBuffersCleared() {
            opened?.let { assertArrayEquals(ByteArray(it.size), it) }
            verified?.let { assertArrayEquals(ByteArray(it.size), it) }
        }
    }

    @Test fun `migration commits only after consent authorization encryption and verification`() = runBlocking {
        val f = Fixture()
        f.migrate()
        assertEquals(listOf("authorize", "legacy", "encrypt", "verify", "commit"), f.events)
        assertEquals(replacement, f.stored)
        assertEquals(1, f.commits)
        f.assertBuffersCleared()
    }

    @Test fun `no confirmation permits no prompts reads or writes`() {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { runBlocking { f.migrate(confirmed = false) } }
        assertTrue(f.events.isEmpty())
        assertEquals(original, f.stored)
    }

    @Test fun `refusing old credential authorization leaves storage unchanged`() {
        val f = Fixture("authorize")
        assertThrows(IllegalStateException::class.java) { runBlocking { f.migrate() } }
        assertEquals(listOf("authorize"), f.events)
        assertEquals(original, f.stored)
        assertEquals(0, f.commits)
    }

    @Test fun `decryption encryption and verification failures never invoke storage`() {
        for (stage in listOf("legacy", "encrypt", "verify")) {
            val f = Fixture(stage)
            assertThrows(AEADBadTagException::class.java) { runBlocking { f.migrate() } }
            assertEquals(original, f.stored)
            assertEquals(0, f.commits)
            f.assertBuffersCleared()
        }
    }

    @Test fun `an invalid v2 replacement is refused before readback or storage`() {
        val f = Fixture("format")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.migrate() } }
        assertFalse(f.events.contains("verify"))
        assertEquals(original, f.stored)
        assertEquals(0, f.commits)
        f.assertBuffersCleared()
    }

    @Test fun `readback mismatch cannot replace the original`() {
        val f = Fixture("mismatch")
        assertThrows(IllegalStateException::class.java) { runBlocking { f.migrate() } }
        assertEquals(original, f.stored)
        assertEquals(0, f.commits)
        f.assertBuffersCleared()
    }

    @Test fun `cancellation at every suspending stage propagates without a write`() {
        for (stage in listOf("legacy", "encrypt", "verify")) {
            val f = Fixture("cancel-$stage")
            assertThrows(CancellationException::class.java) { runBlocking { f.migrate() } }
            assertEquals(original, f.stored)
            assertEquals(0, f.commits)
            f.assertBuffersCleared()
        }
    }

    @Test fun `cancellation as encryption finishes cannot commit a replacement`() {
        val f = Fixture()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = coroutineContext[Job]!!
                f.onEncrypt = { job.cancel() }
                f.migrate()
            }
        }
        assertEquals(original, f.stored)
        assertEquals(0, f.commits)
        f.assertBuffersCleared()
    }

    @Test fun `a concurrent edit wins instead of being overwritten by migration`() {
        val f = Fixture()
        f.onEncrypt = { f.stored = "concurrent credential replacement" }
        assertThrows(IllegalStateException::class.java) { runBlocking { f.migrate() } }
        assertEquals("concurrent credential replacement", f.stored)
        assertEquals(1, f.commits)
        f.assertBuffersCleared()
    }

    @Test fun `failed conditional update never retries or changes the original`() {
        val f = Fixture("conflict")
        assertThrows(IllegalStateException::class.java) { runBlocking { f.migrate() } }
        assertEquals(original, f.stored)
        assertEquals(1, f.commits)
        f.assertBuffersCleared()
    }

    @Test fun `storage failure propagates without retry or losing the original`() {
        val f = Fixture("commit")
        assertThrows(AEADBadTagException::class.java) { runBlocking { f.migrate() } }
        assertEquals(original, f.stored)
        assertEquals(1, f.commits)
        f.assertBuffersCleared()
    }

    @Test fun `only unversioned structurally valid v1 blobs are eligible`() {
        assertTrue(isLegacyEncryptedCredential(original))
        for (invalid in listOf("", "plaintext", "v1:$original", replacement, "v3:$original", "AA==")) {
            assertFalse(isLegacyEncryptedCredential(invalid))
            val f = Fixture()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { f.migration.migrate(invalid, true) { _, _ -> fail("Must not commit"); false } }
            }
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun `real AES GCM migration preserves exact bytes under a different authenticated key`() = runBlocking {
        val legacyKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val newKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val bytes = "  test credential é 中文\n".toByteArray() + byteArrayOf(0, -1)
        val oldCipher = Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, legacyKey)
        }
        val oldBlob = Base64.getEncoder().encodeToString(oldCipher.iv + oldCipher.doFinal(bytes))
        var oldAuthorizations = 0
        var cipherAuthorizations = 0
        val protection = CredentialProtection(
            { Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey) } },
            { iv -> Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, newKey, GCMParameterSpec(128, iv))
            } },
            { cipherAuthorizations++; it },
        )
        val migration = CredentialMigration(
            { oldAuthorizations++; true },
            { blob ->
                val raw = Base64.getDecoder().decode(blob)
                Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, legacyKey, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                }.doFinal(raw.copyOfRange(12, raw.size))
            },
            protection::encrypt,
            protection::decrypt,
        )
        var stored = oldBlob
        migration.migrate(oldBlob, true) { expected, replacement ->
            if (stored == expected) { stored = replacement; true } else false
        }
        assertTrue(stored.startsWith("v2:"))
        assertFalse(isLegacyEncryptedCredential(stored))
        assertEquals(1, oldAuthorizations)
        assertEquals(2, cipherAuthorizations)
        assertArrayEquals(bytes, protection.decrypt(stored))
        assertEquals(3, cipherAuthorizations)
    }
}
