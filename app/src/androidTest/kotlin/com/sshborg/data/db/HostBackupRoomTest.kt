package com.sshborg.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sshborg.data.HostBackupEntry
import com.sshborg.data.HostBackupImporter
import com.sshborg.data.HostBackupPlan
import com.sshborg.data.InvalidHostBackup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Compiled in CI; execute on a dedicated Android device to validate actual Room rollback. */
@RunWith(AndroidJUnit4::class)
class HostBackupRoomTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
    }

    @After fun close() { db.close() }

    private fun importer(writeHost: suspend (HostEntity) -> Long = db.hostDao()::upsert) = HostBackupImporter(
        transaction = { block -> db.withTransaction { block() } },
        readHosts = db.hostDao()::getAllOnce, readKeys = db.sshKeyDao()::getAllOnce,
        readGroups = db.groupDao()::getAllOnce, writeHost = writeHost, writeGroup = db.groupDao()::upsert,
    )

    @Test fun failedHopResolutionRollsBackGroupsAndHostUpdates() = runBlocking {
        val original = HostEntity(label = "test", hostname = "test.invalid", username = "test", encryptedPassword = "v2:current")
        val id = db.hostDao().upsert(original)
        val plan = HostBackupPlan(listOf(GroupEntity(name = "new group", color = 1)), listOf(
            HostBackupEntry(original.copy(username = "changed", jumpMode = "host_list"), jumpHostLabels = listOf("missing")),
        ))
        try {
            importer().restore(plan)
            fail("Missing hop must roll back the transaction")
        } catch (_: InvalidHostBackup) { /* expected */ }
        assertEquals(original.copy(id = id), db.hostDao().getById(id))
        assertTrue(db.groupDao().getAllOnce().isEmpty())
    }

    @Test fun successfulRestoreRetainsTheCurrentMigratedPassword() = runBlocking {
        val original = HostEntity(label = "test", hostname = "test.invalid", username = "test", encryptedPassword = "old blob")
        val id = db.hostDao().upsert(original)
        assertEquals(1, db.hostDao().replaceEncryptedPassword(id, "old blob", "v2:verified"))
        importer().restore(HostBackupPlan(emptyList(), listOf(HostBackupEntry(original.copy(username = "renamed")))))
        val current = db.hostDao().getById(id)!!
        assertEquals("v2:verified", current.encryptedPassword)
        assertEquals("renamed", current.username)
    }

    @Test fun aWriteFailureRollsBackEarlierWrites() = runBlocking {
        var writes = 0
        val plan = HostBackupPlan(listOf(GroupEntity(name = "new group", color = 1)), listOf("first", "second").map {
            HostBackupEntry(HostEntity(label = it, hostname = "test.invalid", username = "test"))
        })
        try {
            importer { row ->
                check(++writes < 2) { "disposable write failure" }
                db.hostDao().upsert(row)
            }.restore(plan)
            fail("The second write must fail")
        } catch (_: IllegalStateException) { /* expected */ }
        assertTrue(db.hostDao().getAllOnce().isEmpty())
        assertTrue(db.groupDao().getAllOnce().isEmpty())
    }
}
