package com.sshborg.data

import com.sshborg.data.db.GroupEntity
import com.sshborg.data.db.HostEntity
import com.sshborg.data.db.SshKeyEntity
import com.sshborg.data.ssh.parseJumpHosts
import com.sshborg.data.ssh.resolveJumpHostEntities
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Metadata only. Labels, unlike database row IDs, can be resolved on another installation. */
internal data class HostBackupEntry(
    val metadata: HostEntity,
    val keyLabel: String? = null,
    val groupName: String? = null,
    val jumpHostLabels: List<String>? = null,
)

internal data class HostBackupPlan(val groups: List<GroupEntity>, val hosts: List<HostBackupEntry>)
internal data class HostBackupResult(val inserted: Int, val updated: Int)

/** One error class lets the UI show safe, localized guidance without including imported text. */
internal class InvalidHostBackup : IllegalArgumentException("Invalid or unresolved backup metadata")

private fun backupRequire(condition: Boolean) {
    if (!condition) throw InvalidHostBackup()
}

internal fun validateHostBackup(plan: HostBackupPlan) {
    backupRequire(plan.groups.map { it.name }.distinct().size == plan.groups.size)
    backupRequire(plan.hosts.map { it.metadata.label }.distinct().size == plan.hosts.size)
    backupRequire(plan.groups.all { it.name.isNotBlank() })
    plan.hosts.forEach { entry ->
        val host = entry.metadata
        backupRequire(host.label.isNotBlank() && host.hostname.isNotBlank() && host.port in 1..65535)
        backupRequire(host.jumpMode in setOf("simple", "host_list"))
        backupRequire(entry.keyLabel == null || entry.keyLabel.isNotBlank())
        backupRequire(entry.groupName == null || entry.groupName.isNotBlank())
        if (host.jumpMode == "simple") {
            try { parseJumpHosts(host.jumpHosts, null) } catch (_: IllegalArgumentException) { throw InvalidHostBackup() }
            backupRequire(entry.jumpHostLabels.isNullOrEmpty() && host.jumpHostIdList.isNullOrBlank())
        } else {
            // Old row-ID-only backups cannot safely identify hops on another installation.
            backupRequire(host.jumpHostIdList.isNullOrBlank())
            backupRequire(entry.jumpHostLabels.orEmpty().all { it.isNotBlank() })
        }
    }
}

/** Never trust credential/pin fields from an imported entity, even if another parser sets them. */
internal fun mergeHostBackup(imported: HostEntity, current: HostEntity?, keyId: Long?, groupId: Long?): HostEntity {
    val metadata = imported.copy(
        id = current?.id ?: 0, keyId = current?.keyId ?: keyId, groupId = groupId,
        password = current?.password, encryptedPassword = current?.encryptedPassword,
        knownHostsEntry = if (current?.hostname == imported.hostname && current.port == imported.port)
            current.knownHostsEntry else null,
        jumpHostKeys = if (current != null && imported.jumpMode == "simple" && current.jumpMode == "simple" &&
            current.jumpHosts == imported.jumpHosts) current.jumpHostKeys else null,
        jumpHostIdList = null,
    )
    return if (current == null) metadata else metadata.copy(
        lastConnected = current.lastConnected ?: imported.lastConnected,
        connectCount = maxOf(current.connectCount, imported.connectCount),
        position = current.position ?: imported.position,
    )
}

internal suspend fun createHostBackupPlan(
    hosts: List<HostEntity>, keys: List<SshKeyEntity>, groups: List<GroupEntity>,
): HostBackupPlan {
    val hostsById = hosts.associateBy { it.id }
    val keysById = keys.associateBy { it.id }
    val groupsById = groups.associateBy { it.id }
    val entries = hosts.map { host ->
        val keyLabel = host.keyId?.let { id ->
            val key = keysById[id] ?: throw InvalidHostBackup()
            backupRequire(keys.count { it.label == key.label } == 1)
            key.label
        }
        val groupName = host.groupId?.let { groupsById[it]?.name ?: throw InvalidHostBackup() }
        val hops = if (host.jumpMode == "host_list") {
            try { resolveJumpHostEntities(host.jumpHostIdList) { hostsById[it] }.map { it.label } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: IllegalStateException) { throw InvalidHostBackup() }
        } else null
        HostBackupEntry(mergeHostBackup(host, null, null, null), keyLabel, groupName, hops)
    }
    return HostBackupPlan(groups, entries).also(::validateHostBackup)
}

/**
 * Transaction ownership is explicit and injected for deterministic JVM policy tests.
 * Production must supply Room.withTransaction, covering every group/host read and write.
 * Preferences live in a different store and are deliberately applied/reported separately.
 */
internal class HostBackupImporter(
    private val transaction: suspend (suspend () -> HostBackupResult) -> HostBackupResult,
    private val readHosts: suspend () -> List<HostEntity>,
    private val readKeys: suspend () -> List<SshKeyEntity>,
    private val readGroups: suspend () -> List<GroupEntity>,
    private val writeHost: suspend (HostEntity) -> Long,
    private val writeGroup: suspend (GroupEntity) -> Long,
) {
    suspend fun restore(plan: HostBackupPlan): HostBackupResult {
        validateHostBackup(plan)
        currentCoroutineContext().ensureActive()
        return transaction {
            // Read after acquiring the transaction, never preserve a pre-migration snapshot.
            val hostsByLabel = readHosts().groupBy { it.label }
            val keysByLabel = readKeys().groupBy { it.label }
            val groupsByName = readGroups().groupBy { it.name }.toMutableMap()
            val restored = linkedMapOf<String, HostEntity>()
            var inserted = 0
            var updated = 0

            plan.groups.forEach { imported ->
                val matches = groupsByName[imported.name].orEmpty()
                backupRequire(matches.size <= 1)
                val current = matches.singleOrNull()
                val group = imported.copy(id = current?.id ?: 0, collapsed = current?.collapsed ?: false,
                    position = current?.position ?: imported.position)
                val id = writeGroup(group)
                groupsByName[group.name] = listOf(group.copy(id = id))
            }

            suspend fun resolveGroup(name: String?): Long? {
                if (name == null) return null
                val matches = groupsByName[name].orEmpty()
                backupRequire(matches.size <= 1)
                matches.singleOrNull()?.let { return it.id }
                val group = GroupEntity(name = name, color = GroupEntity.SWATCHES[0])
                val id = writeGroup(group)
                groupsByName[name] = listOf(group.copy(id = id))
                return id
            }

            plan.hosts.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val matches = hostsByLabel[entry.metadata.label].orEmpty()
                backupRequire(matches.size <= 1)
                val current = matches.singleOrNull()
                val keyId = current?.keyId ?: entry.keyLabel?.let { label ->
                    val keys = keysByLabel[label].orEmpty()
                    // A missing/ambiguous assigned key cannot silently become password auth.
                    backupRequire(keys.size == 1)
                    keys.single().id
                }
                val host = mergeHostBackup(entry.metadata, current, keyId, resolveGroup(entry.groupName))
                restored[host.label] = host.copy(id = writeHost(host))
                if (current == null) inserted++ else updated++
            }

            // All imported row IDs now exist; resolve the complete chain before committing.
            plan.hosts.filter { it.metadata.jumpMode == "host_list" }.forEach { entry ->
                val ids = entry.jumpHostLabels.orEmpty().map { label ->
                    restored[label]?.id ?: run {
                        val matches = hostsByLabel[label].orEmpty()
                        backupRequire(matches.size == 1)
                        matches.single().id
                    }
                }
                val host = restored.getValue(entry.metadata.label)
                    .copy(jumpHostIdList = ids.takeIf { it.isNotEmpty() }?.joinToString(","))
                writeHost(host)
            }
            currentCoroutineContext().ensureActive()
            HostBackupResult(inserted, updated)
        }
    }
}
