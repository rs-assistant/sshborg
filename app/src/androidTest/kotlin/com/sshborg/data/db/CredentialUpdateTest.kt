package com.sshborg.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only a new in-memory database, never the installed application's saved records. */
@RunWith(AndroidJUnit4::class)
class CredentialUpdateTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After fun close() { db.close() }

    @Test fun conditionalHostMigrationPreservesMetadataAndRefusesAStaleReplacement() = runBlocking {
        val hosts = db.hostDao()
        val id = hosts.upsert(HostEntity(label = "test host", hostname = "test.invalid", username = "test",
            encryptedPassword = "old blob", knownHostsEntry = "test pin"))
        assertEquals(1, hosts.replaceEncryptedPassword(id, "old blob", "v2:new blob"))
        assertEquals(0, hosts.replaceEncryptedPassword(id, "old blob", "v2:stale replacement"))
        val current = checkNotNull(hosts.getById(id))
        assertEquals("v2:new blob", current.encryptedPassword)
        assertEquals("test host", current.label)
        assertEquals("test pin", current.knownHostsEntry)
    }

    @Test fun keyMigrationAndRenameCannotOverwriteOneAnothersFields() = runBlocking {
        val keys = db.sshKeyDao()
        val id = keys.upsert(SshKeyEntity(label = "test", keyType = "ed25519", publicKey = "test public",
            encryptedBlob = "old blob"))
        assertEquals(1, keys.replaceEncryptedBlob(id, "old blob", "v2:new blob"))
        keys.rename(id, "renamed", "renamed public")
        assertEquals(0, keys.replaceEncryptedBlob(id, "old blob", "v2:stale blob"))
        val current = checkNotNull(keys.getById(id))
        assertEquals("v2:new blob", current.encryptedBlob)
        assertEquals("renamed", current.label)
        assertEquals("renamed public", current.publicKey)
        assertEquals("", current.privateKeyPem)
    }

    @Test fun missingCredentialsAreNeverRecreatedByMigration() = runBlocking {
        assertEquals(0, db.hostDao().replaceEncryptedPassword(19, "old", "new"))
        assertEquals(0, db.sshKeyDao().replaceEncryptedBlob(19, "old", "new"))
        assertNull(db.hostDao().getById(19))
        assertNull(db.sshKeyDao().getById(19))
    }

    @Test fun sessionMetadataCannotRollBackAMigratedPassword() = runBlocking {
        val hosts = db.hostDao()
        val id = hosts.upsert(HostEntity(label = "test", hostname = "test.invalid", username = "test",
            encryptedPassword = "old blob"))
        assertEquals(1, hosts.replaceEncryptedPassword(id, "old blob", "v2:new blob"))
        hosts.pinHostKeyIfAbsent(id, "first pin")
        hosts.pinHostKeyIfAbsent(id, "second pin")
        hosts.updateSftpShowHidden(id, true)
        hosts.updateSftpStartDir(id, "/test")
        assertEquals(1, hosts.updateJumpHostKeys(id, null, "jump pin"))
        assertEquals(0, hosts.updateJumpHostKeys(id, null, "stale pin"))
        val current = checkNotNull(hosts.getById(id))
        assertEquals("v2:new blob", current.encryptedPassword)
        assertEquals("first pin", current.knownHostsEntry)
        assertEquals("jump pin", current.jumpHostKeys)
        assertEquals("/test", current.sftpStartDir)
        assertTrue(current.sftpShowHidden)
    }

    @Test fun encryptedMigrationDoesNotSilentlyDeleteHistoricalPlaintextResidue() = runBlocking {
        val keys = db.sshKeyDao()
        val id = keys.upsert(SshKeyEntity(label = "test", keyType = "ed25519", publicKey = "test public",
            privateKeyPem = "legacy test residue", encryptedBlob = "old blob"))
        assertEquals(1, keys.replaceEncryptedBlob(id, "old blob", "v2:new blob"))
        assertEquals("legacy test residue", keys.getById(id)!!.privateKeyPem)
    }
}
