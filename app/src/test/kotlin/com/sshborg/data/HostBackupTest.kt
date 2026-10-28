package com.sshborg.data

import com.sshborg.data.db.GroupEntity
import com.sshborg.data.db.HostEntity
import com.sshborg.data.db.SshKeyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostBackupTest {
    private val host = HostEntity(id = 7, label = "target", hostname = "test.invalid", username = "test")
    private val key = SshKeyEntity(id = 19, label = "test key", keyType = "ed25519", publicKey = "test public")

    /** Models rollback for policy tests; Android tests separately exercise real Room. */
    private class Store {
        var hosts = linkedMapOf<Long, HostEntity>()
        var groups = linkedMapOf<Long, GroupEntity>()
        var keys = emptyList<SshKeyEntity>()
        var beforeTransaction: () -> Unit = {}
        var failHostWriteAt: Int? = null
        var cancelHostWriteAt: Int? = null
        var hostWrites = 0
        var inTransaction = false
        val importer = HostBackupImporter(
            transaction = { block ->
                beforeTransaction()
                val oldHosts = LinkedHashMap(hosts)
                val oldGroups = LinkedHashMap(groups)
                inTransaction = true
                try { block() } catch (failure: Throwable) {
                    hosts = oldHosts
                    groups = oldGroups
                    throw failure
                } finally { inTransaction = false }
            },
            readHosts = { check(inTransaction); hosts.values.toList() },
            readKeys = { check(inTransaction); keys },
            readGroups = { check(inTransaction); groups.values.toList() },
            writeHost = { value ->
                check(inTransaction)
                hostWrites++
                if (hostWrites == failHostWriteAt) error("disposable write failure")
                if (hostWrites == cancelHostWriteAt) throw CancellationException("disposable cancellation")
                val id = value.id.takeIf { it != 0L } ?: ((hosts.keys.maxOrNull() ?: 0) + 1)
                hosts[id] = value.copy(id = id)
                id
            },
            writeGroup = { value ->
                check(inTransaction)
                val id = value.id.takeIf { it != 0L } ?: ((groups.keys.maxOrNull() ?: 0) + 1)
                groups[id] = value.copy(id = id)
                id
            },
        )
    }

    private fun restore(store: Store, vararg entries: HostBackupEntry, groups: List<GroupEntity> = emptyList()) =
        runBlocking { store.importer.restore(HostBackupPlan(groups, entries.toList())) }

    @Test fun `new imports discard all credential and trust fields from the backup`() {
        val malicious = host.copy(keyId = 999, password = "untrusted", encryptedPassword = "untrusted blob",
            knownHostsEntry = "untrusted pin", jumpHostKeys = "untrusted jump pin", groupId = 999)
        val restored = mergeHostBackup(malicious, null, null, null)
        assertEquals(0L, restored.id)
        assertNull(restored.keyId)
        assertNull(restored.password)
        assertNull(restored.encryptedPassword)
        assertNull(restored.knownHostsEntry)
        assertNull(restored.jumpHostKeys)
        assertNull(restored.groupId)
    }

    @Test fun `metadata restore retains exact current credentials even if empty or unreadable`() {
        for (blob in listOf("", "unreadable", "v2:migrated")) {
            val current = host.copy(keyId = 19, password = "historical residue", encryptedPassword = blob,
                knownHostsEntry = "local pin", jumpHosts = "jump", jumpHostKeys = "local jump pin")
            val restored = mergeHostBackup(current.copy(encryptedPassword = "stale v1"), current, 44, null)
            assertEquals(current, restored)
        }
    }

    @Test fun `pins follow their endpoint and jump mode rather than imported trust`() {
        val current = host.copy(knownHostsEntry = "local pin", jumpHosts = "jump", jumpHostKeys = "local jump pin")
        assertNull(mergeHostBackup(host.copy(hostname = "other.invalid"), current, null, null).knownHostsEntry)
        assertNull(mergeHostBackup(host.copy(port = 2222), current, null, null).knownHostsEntry)
        assertNull(mergeHostBackup(host.copy(jumpHosts = "other"), current, null, null).jumpHostKeys)
        assertNull(mergeHostBackup(current.copy(jumpMode = "host_list"), current, null, null).jumpHostKeys)
    }

    @Test fun `local usage and position are preserved`() {
        val current = host.copy(lastConnected = 500, connectCount = 9, position = 2)
        val restored = mergeHostBackup(host.copy(lastConnected = 1, connectCount = 3, position = 99), current, null, null)
        assertEquals(current, restored)
    }

    @Test fun `a migration committed before the transaction is never rolled back by restore`() {
        val store = Store()
        store.hosts[host.id] = host.copy(encryptedPassword = "v1:old")
        store.beforeTransaction = { store.hosts[host.id] = host.copy(encryptedPassword = "v2:verified") }
        assertEquals(HostBackupResult(0, 1), restore(store, HostBackupEntry(host.copy(encryptedPassword = "v1:stale"))))
        assertEquals("v2:verified", store.hosts.getValue(host.id).encryptedPassword)
    }

    @Test fun `missing or ambiguous named keys cannot downgrade to password and roll back groups`() {
        for (keys in listOf(emptyList(), listOf(key, key.copy(id = 20)))) {
            val store = Store().also { it.keys = keys }
            assertThrows(InvalidHostBackup::class.java) {
                restore(store, HostBackupEntry(host, keyLabel = key.label),
                    groups = listOf(GroupEntity(name = "new group", color = 1)))
            }
            assertTrue(store.hosts.isEmpty())
            assertTrue(store.groups.isEmpty())
        }
    }

    @Test fun `a local assigned key is never overwritten or cleared by a backup key name`() {
        val store = Store()
        store.hosts[host.id] = host.copy(keyId = 81)
        restore(store, HostBackupEntry(host, keyLabel = "missing on this install"))
        assertEquals(81L, store.hosts.getValue(host.id).keyId)
    }

    @Test fun `a unique key name resolves to its local id not its exported id`() {
        val store = Store().also { it.keys = listOf(key) }
        restore(store, HostBackupEntry(host.copy(keyId = 500), keyLabel = key.label))
        assertEquals(key.id, store.hosts.values.single().keyId)
    }

    @Test fun `named hops are remapped after all new row ids exist and keep their order`() {
        val store = Store()
        store.hosts[90] = host.copy(id = 90, label = "unrelated")
        val target = HostBackupEntry(host.copy(jumpMode = "host_list"), jumpHostLabels = listOf("hop2", "hop1"))
        restore(store, target, HostBackupEntry(host.copy(label = "hop1")), HostBackupEntry(host.copy(label = "hop2")))
        val rows = store.hosts.values.associateBy { it.label }
        assertEquals("${rows.getValue("hop2").id},${rows.getValue("hop1").id}", rows.getValue("target").jumpHostIdList)
        assertEquals("unrelated", store.hosts.getValue(90).label)
    }

    @Test fun `a missing named hop rolls back every host and group write`() {
        val store = Store()
        val entry = HostBackupEntry(host.copy(jumpMode = "host_list"), jumpHostLabels = listOf("missing"))
        assertThrows(InvalidHostBackup::class.java) {
            restore(store, entry, groups = listOf(GroupEntity(name = "new", color = 1)))
        }
        assertTrue(store.hosts.isEmpty())
        assertTrue(store.groups.isEmpty())
    }

    @Test fun `legacy numeric hop ids are refused even if they happen to exist locally`() {
        val store = Store()
        store.hosts[host.id] = host
        assertThrows(InvalidHostBackup::class.java) {
            restore(store, HostBackupEntry(host.copy(jumpMode = "host_list", jumpHostIdList = "7")))
        }
        assertEquals(mapOf(host.id to host), store.hosts)
        assertEquals(0, store.hostWrites)
    }

    @Test fun `duplicate imported or local labels cannot select an arbitrary host`() {
        val store = Store()
        assertThrows(InvalidHostBackup::class.java) { restore(store, HostBackupEntry(host), HostBackupEntry(host)) }
        store.hosts[7] = host
        store.hosts[8] = host.copy(id = 8)
        assertThrows(InvalidHostBackup::class.java) { restore(store, HostBackupEntry(host)) }
        assertEquals(2, store.hosts.size)
    }

    @Test fun `write failure or cancellation leaves all existing data unchanged`() {
        for (cancel in listOf(false, true)) {
            val store = Store()
            store.hosts[host.id] = host
            if (cancel) store.cancelHostWriteAt = 2 else store.failHostWriteAt = 2
            val type = if (cancel) CancellationException::class.java else IllegalStateException::class.java
            assertThrows(type) {
                restore(store, HostBackupEntry(host.copy(username = "changed")),
                    HostBackupEntry(host.copy(label = "new")), groups = listOf(GroupEntity(name = "new", color = 1)))
            }
            assertEquals(mapOf(host.id to host), store.hosts)
            assertTrue(store.groups.isEmpty())
        }
    }

    @Test fun `invalid ports simple chains and unknown modes never begin writing`() {
        for (bad in listOf(host.copy(port = 0), host.copy(port = 65536), host.copy(jumpHosts = ","),
            host.copy(jumpMode = "unknown"))) {
            val store = Store()
            assertThrows(InvalidHostBackup::class.java) { restore(store, HostBackupEntry(bad)) }
            assertEquals(0, store.hostWrites)
        }
    }

    @Test fun `export plan contains only portable metadata and round trips to different local ids`() = runBlocking {
        val hop = host.copy(id = 30, label = "hop", keyId = key.id, encryptedPassword = "secret blob", password = "residue")
        val target = host.copy(jumpMode = "host_list", jumpHostIdList = "30", knownHostsEntry = "pin")
        val plan = createHostBackupPlan(listOf(target, hop), listOf(key), emptyList())
        assertEquals(listOf("hop"), plan.hosts.first().jumpHostLabels)
        plan.hosts.forEach {
            assertEquals(0L, it.metadata.id)
            assertNull(it.metadata.keyId)
            assertNull(it.metadata.password)
            assertNull(it.metadata.encryptedPassword)
            assertNull(it.metadata.knownHostsEntry)
            assertNull(it.metadata.jumpHostIdList)
        }
        val store = Store().also { it.keys = listOf(key.copy(id = 88)) }
        store.importer.restore(plan)
        val rows = store.hosts.values.associateBy { it.label }
        assertEquals(rows.getValue("hop").id.toString(), rows.getValue("target").jumpHostIdList)
        assertEquals(88L, rows.getValue("hop").keyId)
    }

    @Test fun `export refuses unresolved or ambiguous associations rather than losing them`() {
        for ((hosts, keys) in listOf(
            listOf(host.copy(keyId = key.id)) to emptyList(),
            listOf(host.copy(keyId = key.id)) to listOf(key, key.copy(id = 88)),
            listOf(host.copy(jumpMode = "host_list", jumpHostIdList = "999")) to emptyList(),
            listOf(host, host.copy(id = 88)) to emptyList(),
        )) {
            assertThrows(InvalidHostBackup::class.java) {
                runBlocking { createHostBackupPlan(hosts, keys, emptyList()) }
            }
        }
    }
}
