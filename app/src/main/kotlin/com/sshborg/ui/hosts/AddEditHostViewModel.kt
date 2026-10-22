package com.sshborg.ui.hosts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.sshborg.R
import com.sshborg.Screen
import com.sshborg.SshBorgApp
import com.sshborg.data.ProtectedCredentials
import com.sshborg.data.db.GroupEntity
import com.sshborg.data.db.HostEntity
import com.sshborg.data.db.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddEditHostViewModel(app: Application) : AndroidViewModel(app) {

    private val sshBorgApp = app as SshBorgApp
    private val hostDao  = sshBorgApp.db.hostDao()
    private val keyDao   = sshBorgApp.db.sshKeyDao()
    private val groupDao = sshBorgApp.db.groupDao()

    val keys: StateFlow<List<SshKeyEntity>> =
        keyDao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<GroupEntity>> =
        groupDao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Form state
    var label = MutableStateFlow("")
    var hostname = MutableStateFlow("")
    var port = MutableStateFlow("22")
    var username = MutableStateFlow("")
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password
    var useKey = MutableStateFlow(false)
    var selectedKeyId = MutableStateFlow<Long?>(null)
    var agentForwarding = MutableStateFlow(false)
    /** Raw jump-hosts string (simple mode): "host1:port,host2:port,...". */
    var jumpHosts = MutableStateFlow("")
    /** Newline-separated port-forwarding rules in -L syntax. */
    var portForwardings = MutableStateFlow("")
    /** Jump host mode: "simple" = text field, "host_list" = select from hosts. */
    var jumpMode = MutableStateFlow("simple")
    /** Ordered list of host IDs selected as jump hops (host-list mode). 0L = not yet selected. */
    var jumpHostIds = MutableStateFlow<List<Long>>(emptyList())
    /** SFTP starting directory mode: "last" | "fixed" | "home". */
    var sftpStartMode = MutableStateFlow("last")
    /** Path shown/edited in the starting directory field (managed or user-entered). */
    var sftpStartDir = MutableStateFlow("")
    /** Whether the SFTP browser shows dotfiles for this host. Default hides them. */
    var sftpShowHidden = MutableStateFlow(false)
    var allowLegacyCiphers = MutableStateFlow(false)
    /** Null = ungrouped; otherwise the selected group's ID. */
    var groupId = MutableStateFlow<Long?>(null)
    /** Optional per-host ARGB color; overrides the group color. */
    var hostColor = MutableStateFlow<Int?>(null)

    /** When true, the saved host key and cached jump-host keys are cleared on save. */
    var resetHostKeys = MutableStateFlow(false)

    private val _hasStoredHostKeys = MutableStateFlow(false)
    /** True when the edited host has a pinned host key or cached jump-host keys to reset. */
    val hasStoredHostKeys: StateFlow<Boolean> = _hasStoredHostKeys

    private val _needsPasswordReentry = MutableStateFlow(false)
    /** True when a legacy plaintext password was found and deliberately not loaded. */
    val needsPasswordReentry: StateFlow<Boolean> = _needsPasswordReentry

    private var passwordEdit = HostPasswordEdit()
    private val _hasSavedPassword = MutableStateFlow(false)
    val hasSavedPassword: StateFlow<Boolean> = _hasSavedPassword
    private val _removePasswordOnSave = MutableStateFlow(false)
    val removePasswordOnSave: StateFlow<Boolean> = _removePasswordOnSave
    private val _hostLoaded = MutableStateFlow(false)
    val hostLoaded: StateFlow<Boolean> = _hostLoaded
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving
    private val _saveError = MutableStateFlow<Int?>(null)
    val saveError: StateFlow<Int?> = _saveError
    private var loadJob: Job? = null

    fun setPassword(value: String) {
        _password.value = value
        _removePasswordOnSave.value = false
        _saveError.value = null
    }

    fun removeSavedPassword() {
        _password.value = ""
        _removePasswordOnSave.value = true
        _saveError.value = null
    }

    fun keepSavedPassword() {
        _removePasswordOnSave.value = false
        _saveError.value = null
    }

    private val _editingId = MutableStateFlow<Long?>(null)

    /** All hosts except the one being edited, annotated with whether they can be used as jump hosts. */
    data class JumpHostOption(val host: HostEntity, val isSelectable: Boolean)

    val availableJumpHosts: StateFlow<List<JumpHostOption>> = combine(
        hostDao.getAll(),
        _editingId,
    ) { hosts, editingId ->
        hosts
            .filter { it.id != editingId }
            .map { host ->
                JumpHostOption(
                    host        = host,
                    isSelectable = host.keyId != null ||
                                   !host.encryptedPassword.isNullOrEmpty(),
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var editingId: Long? = null

    fun loadHost(hostId: Long) {
        loadJob?.cancel()
        _hostLoaded.value = false
        _saveError.value = null
        if (hostId == Screen.AddEditHost.NEW_ID) {
            populateHost(HostEntity(label = "", hostname = "", username = ""))
            _hostLoaded.value = true
            return
        }
        loadJob = viewModelScope.launch {
            try {
                val h = hostDao.getById(hostId) ?: error("Host no longer exists")
                ensureActive()
                populateHost(h)
                _hostLoaded.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _saveError.value = R.string.host_load_failed
            }
        }
    }

    private fun populateHost(h: HostEntity) {
        editingId = h.id.takeIf { it != 0L }
        _editingId.value = editingId
        label.value = h.label
        hostname.value = h.hostname
        port.value = h.port.toString()
        username.value = h.username
        passwordEdit = HostPasswordEdit(h.encryptedPassword, h.password?.isNotEmpty() == true)
        _needsPasswordReentry.value = passwordEdit.needsReentry
        _hasSavedPassword.value = passwordEdit.hasSavedPassword
        _password.value = ""
        _removePasswordOnSave.value = false
        useKey.value = h.keyId != null
        selectedKeyId.value = h.keyId
        agentForwarding.value = h.agentForwarding
        jumpHosts.value = h.jumpHosts ?: ""
        portForwardings.value = h.portForwardings ?: ""
        jumpMode.value = h.jumpMode
        jumpHostIds.value = parseJumpHostSelection(h.jumpHostIdList)
        sftpStartMode.value = h.sftpStartMode
        sftpStartDir.value = h.sftpStartDir ?: ""
        sftpShowHidden.value = h.sftpShowHidden
        allowLegacyCiphers.value = h.allowLegacyCiphers
        groupId.value = h.groupId
        hostColor.value = h.color
        _hasStoredHostKeys.value = h.knownHostsEntry != null || h.jumpHostKeys != null
        resetHostKeys.value = false
    }

    /** Creates a new group and selects it for this host. */
    fun createGroup(name: String, color: Int) = viewModelScope.launch {
        groupId.value = groupDao.upsert(GroupEntity(name = name, color = color))
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        if (!_hostLoaded.value || _isSaving.value) return@launch
        val useKeyForSave = useKey.value
        val replacement = password.value
        val removePassword = _removePasswordOnSave.value
        val passwordForSave = passwordEdit
        val currentMode = jumpMode.value
        val jumpSelection = jumpHostIds.value.toList()
        if (currentMode == "host_list" && jumpSelection.any { it <= 0 }) {
            _saveError.value = R.string.host_jump_selection_invalid
            return@launch
        }
        val keyIdForSave = if (useKeyForSave) selectedKeyId.value else null
        if (useKeyForSave && keyIdForSave == null) {
            _saveError.value = R.string.host_key_required
            return@launch
        }
        if (passwordForSave.needsReentry && !useKeyForSave && !removePassword && replacement.isEmpty()) {
            _saveError.value = R.string.host_password_reentry_required
            return@launch
        }
        _isSaving.value = true
        _saveError.value = null
        try {
            val encryptedPwd = withContext(Dispatchers.IO) {
                passwordForSave.encryptedForSave(replacement, removePassword, useKeyForSave, ProtectedCredentials::encrypt)
            }

            val newJumpHostIdList = if (currentMode == "host_list") {
                encodeJumpHostSelection(jumpSelection)
            } else null
            val newJumpHosts = if (currentMode == "simple") {
                jumpHosts.value.trim().takeIf { it.isNotEmpty() }
            } else null

            val existing = editingId?.let { checkNotNull(hostDao.getById(it)) { "Host no longer exists" } }
            val newHostname = hostname.value.trim()
            val newPort = port.value.toIntOrNull() ?: 22
            val reset = resetHostKeys.value

            // A pinned host key is a TOFU anchor bound to a specific endpoint. Keep it only
            // when the endpoint is unchanged (and the user hasn't asked for a reset); if the
            // host was repointed, drop it so the next connection re-verifies. Cached jump-host
            // keys follow the same rule against the jump-hosts string (simple mode).
            val preservedKnownHosts = if (!reset && existing != null &&
                existing.hostname == newHostname && existing.port == newPort) existing.knownHostsEntry else null
            val preservedJumpHostKeys = if (!reset && currentMode == "simple" &&
                existing?.jumpHosts == newJumpHosts) existing?.jumpHostKeys else null

            val newSftpStartDir = when (sftpStartMode.value) {
                "home"  -> null
                "fixed" -> sftpStartDir.value.trim().takeIf { it.isNotEmpty() }
                else    -> existing?.sftpStartDir  // "last": preserve the auto-managed path
            }

            val entity = HostEntity(
                id               = editingId ?: 0,
                label            = label.value.ifBlank { newHostname },
                hostname         = newHostname,
                port             = newPort,
                username         = username.value.trim(),
                password         = null,
                encryptedPassword = encryptedPwd,
                keyId            = keyIdForSave,
                knownHostsEntry  = preservedKnownHosts,
                agentForwarding  = agentForwarding.value,
                jumpHosts        = newJumpHosts,
                jumpHostKeys     = preservedJumpHostKeys,
                portForwardings  = portForwardings.value.trim().takeIf { it.isNotEmpty() },
                jumpMode         = currentMode,
                jumpHostIdList   = newJumpHostIdList,
                sftpStartMode        = sftpStartMode.value,
                sftpStartDir         = newSftpStartDir,
                sftpShowHidden       = sftpShowHidden.value,
                allowLegacyCiphers   = allowLegacyCiphers.value,
                groupId              = groupId.value,
                color                = hostColor.value,
            )
            sshBorgApp.db.withTransaction {
                if (existing != null) {
                    val current = checkNotNull(hostDao.getById(existing.id)) { "Host no longer exists" }
                    check(current.encryptedPassword == passwordForSave.originalEncrypted) {
                        "Saved password changed while editing; reopen the editor"
                    }
                }
                hostDao.upsert(entity)
            }
            passwordEdit = HostPasswordEdit(encryptedPwd)
            _hasSavedPassword.value = passwordEdit.hasSavedPassword
            _needsPasswordReentry.value = false
            _removePasswordOnSave.value = false
            _password.value = ""
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _saveError.value = R.string.host_save_failed
        } finally {
            _isSaving.value = false
        }
    }
}
