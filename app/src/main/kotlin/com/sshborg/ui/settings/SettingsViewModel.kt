package com.sshborg.ui.settings

import android.app.Application
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.room.withTransaction
import com.sshborg.R
import androidx.lifecycle.viewModelScope
import com.sshborg.SshBorgApp
import com.sshborg.data.AppPreferences
import com.sshborg.data.ExtraBar
import com.sshborg.data.ExtraBarPresets
import com.sshborg.data.HostBackupEntry
import com.sshborg.data.HostBackupImporter
import com.sshborg.data.HostBackupPlan
import com.sshborg.data.InvalidHostBackup
import com.sshborg.data.createHostBackupPlan
import com.sshborg.data.db.GroupEntity
import com.sshborg.data.db.HostEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val sshBorgApp   = app as SshBorgApp
    private val prefs        = sshBorgApp.appPreferences
    private val appLock      = sshBorgApp.appLockManager
    private val keyDao       = sshBorgApp.db.sshKeyDao()
    private val hostDao      = sshBorgApp.db.hostDao()
    private val groupDao     = sshBorgApp.db.groupDao()

    val lockMode: StateFlow<Int> =
        prefs.lockMode.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.sshborg.data.AppPreferences.LOCK_NONE,
        )

    val confirmExit: StateFlow<Boolean> =
        prefs.confirmExit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val invertTerminalScroll: StateFlow<Boolean> =
        prefs.invertTerminalScroll.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val sftpSortDirsFirst: StateFlow<Boolean> =
        prefs.sftpSortDirsFirst.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val nightMode: StateFlow<Int> =
        prefs.nightMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    val allowScreenshots: StateFlow<Boolean> =
        prefs.allowScreenshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val scrollbackLines: StateFlow<Int> =
        prefs.scrollbackLines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2000)

    val terminalFontSize: StateFlow<Int> =
        prefs.terminalFontSize.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.sshborg.data.AppPreferences.DEFAULT_TERMINAL_FONT_SIZE,
        )

    val keepScreenOn: StateFlow<Boolean> =
        prefs.keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val terminalBarAutoHide: StateFlow<Boolean> =
        prefs.terminalBarAutoHide.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val terminalHideStatusBar: StateFlow<Boolean> =
        prefs.terminalHideStatusBar.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val terminalColorScheme: StateFlow<Int> =
        prefs.terminalColorScheme.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.sshborg.data.AppPreferences.TERMINAL_SCHEME_DARK,
        )

    val doubleTapAction: StateFlow<Int> =
        prefs.doubleTapAction.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.sshborg.data.AppPreferences.DOUBLE_TAP_NONE,
        )

    val historySuggestions: StateFlow<Boolean> =
        prefs.historySuggestions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val suggestionsBarSticky: StateFlow<Boolean> =
        prefs.suggestionsBarSticky.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val extraKeysBarPinned: StateFlow<Boolean> =
        prefs.extraKeysBarPinned.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hostSortMode: StateFlow<Int> =
        prefs.hostSortMode.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.HOST_SORT_ALPHA,
        )

    fun setHostSortMode(mode: Int) {
        viewModelScope.launch { prefs.setHostSortMode(mode) }
    }

    /** The extra-key bar in use, for the Settings row label. */
    val extraBar: StateFlow<ExtraBar> =
        prefs.extraBar.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExtraBarPresets.all.first())

    val lockTimeoutSeconds: StateFlow<Int> =
        prefs.lockTimeoutSeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message: SharedFlow<String> = _message

    /** The BCP-47 tag of the currently forced locale, or "" for system default. */
    val currentLocaleTag: String
        get() {
            val locales = AppCompatDelegate.getApplicationLocales()
            return if (locales.isEmpty) "" else locales[0]?.toLanguageTag() ?: ""
        }

    fun setLocale(tag: String) {
        val localeList = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                         else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun setLockMode(mode: Int) {
        viewModelScope.launch {
            // Leaving the in-app lock discards its stored secret.
            if (mode != com.sshborg.data.AppPreferences.LOCK_SECRET) appLock.clear()
            prefs.setLockMode(mode)
        }
    }

    /** Confirms the current PIN/passphrase (no throttling) before allowing a change. */
    suspend fun checkAppLockSecret(input: CharArray): Boolean = appLock.checkSecret(input)

    /** Stores a new PIN/passphrase and switches to the in-app lock mode. */
    fun setAppLockSecret(kind: com.sshborg.data.AppLockManager.Kind, secret: CharArray) {
        viewModelScope.launch {
            appLock.setSecret(kind, secret)
            secret.fill(' ')
            prefs.setLockMode(com.sshborg.data.AppPreferences.LOCK_SECRET)
        }
    }

    fun setConfirmExit(enabled: Boolean) {
        viewModelScope.launch { prefs.setConfirmExit(enabled) }
    }

    fun setInvertTerminalScroll(enabled: Boolean) {
        viewModelScope.launch { prefs.setInvertTerminalScroll(enabled) }
    }

    fun setSftpSortDirsFirst(enabled: Boolean) {
        viewModelScope.launch { prefs.setSftpSortDirsFirst(enabled) }
    }

    fun setLockTimeoutSeconds(seconds: Int) {
        viewModelScope.launch { prefs.setLockTimeoutSeconds(seconds) }
    }

    fun setNightMode(mode: Int) {
        viewModelScope.launch { prefs.setNightMode(mode) }
    }

    fun setAllowScreenshots(enabled: Boolean) {
        viewModelScope.launch { prefs.setAllowScreenshots(enabled) }
    }

    fun setScrollbackLines(lines: Int) {
        viewModelScope.launch { prefs.setScrollbackLines(lines) }
    }

    fun setTerminalFontSize(sp: Int) {
        viewModelScope.launch { prefs.setTerminalFontSize(sp) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { prefs.setKeepScreenOn(enabled) }
    }

    fun setTerminalBarAutoHide(enabled: Boolean) {
        viewModelScope.launch { prefs.setTerminalBarAutoHide(enabled) }
    }

    fun setTerminalHideStatusBar(enabled: Boolean) {
        viewModelScope.launch { prefs.setTerminalHideStatusBar(enabled) }
    }

    fun setDoubleTapAction(action: Int) {
        viewModelScope.launch { prefs.setDoubleTapAction(action) }
    }

    fun setTerminalColorScheme(scheme: Int) {
        viewModelScope.launch { prefs.setTerminalColorScheme(scheme) }
    }

    fun setHistorySuggestions(enabled: Boolean) {
        viewModelScope.launch { prefs.setHistorySuggestions(enabled) }
    }

    fun setSuggestionsBarSticky(enabled: Boolean) {
        viewModelScope.launch { prefs.setSuggestionsBarSticky(enabled) }
    }

    fun setExtraKeysBarPinned(enabled: Boolean) {
        viewModelScope.launch { prefs.setExtraKeysBarPinned(enabled) }
    }

    fun exportHosts(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val (hosts, groups, keys) = sshBorgApp.db.withTransaction {
                    Triple(hostDao.getAllOnce(), groupDao.getAllOnce(), keyDao.getAllOnce())
                }
                val plan = createHostBackupPlan(hosts, keys, groups)
                val groupsArr = JSONArray()
                groups.forEach { g ->
                    groupsArr.put(JSONObject().apply {
                        put("name", g.name)
                        put("color", g.color)
                        g.position?.let { put("position", it) }
                    })
                }
                val arr = JSONArray()
                plan.hosts.forEach { entry ->
                    val h = entry.metadata
                    arr.put(JSONObject().apply {
                        put("label", h.label)
                        put("hostname", h.hostname)
                        put("port", h.port)
                        put("username", h.username)
                        put("agentForwarding", h.agentForwarding)
                        put("jumpMode", h.jumpMode)
                        put("sftpStartMode", h.sftpStartMode)
                        put("sftpShowHidden", h.sftpShowHidden)
                        put("allowLegacyCiphers", h.allowLegacyCiphers)
                        h.jumpHosts?.let { put("jumpHosts", it) }
                        if (h.jumpMode == "host_list") put("jumpHostLabels", JSONArray(entry.jumpHostLabels.orEmpty()))
                        h.portForwardings?.let { put("portForwardings", it) }
                        h.sftpStartDir?.let { put("sftpStartDir", it) }
                        entry.keyLabel?.let { put("keyLabel", it) }
                        entry.groupName?.let { put("group", it) }
                        h.color?.let { put("color", it) }
                        // Host list order (#16): without these a restore loses the manual
                        // arrangement and both usage-based orders start from scratch.
                        h.position?.let { put("position", it) }
                        h.lastConnected?.let { put("lastConnected", it) }
                        if (h.connectCount > 0) put("connectCount", h.connectCount)
                    })
                }
                val json = JSONObject().apply {
                    put("version", 8)
                    put("exported_at", java.time.Instant.now().toString())
                    put("groups", groupsArr)
                    put("hosts", arr)
                    put("settings", prefs.exportSettingsJson())
                }.toString(2)
                checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)).use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
                _message.tryEmit(getApplication<Application>().getString(R.string.backup_export_success, hosts.size))
            }.onFailure {
                if (it is CancellationException) throw it
                _error.tryEmit(getApplication<Application>().getString(R.string.backup_export_failed))
            }
        }
    }

    fun importHosts(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val jsonText = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException(getApplication<Application>().getString(R.string.error_unknown))
                val root = JSONObject(jsonText)

                val version = if (root.has("version")) root.get("version").toString().toIntOrNull() else 1
                if (version == null || version !in 1..8) throw InvalidHostBackup()
                val groups = root.optJSONArray("groups")?.let { groupsArr ->
                    (0 until groupsArr.length()).map { i ->
                        val g = groupsArr.getJSONObject(i)
                        GroupEntity(name = g.getString("name"), color = g.optInt("color", GroupEntity.SWATCHES[0]),
                            position = if (g.has("position")) g.getInt("position") else null)
                    }
                }.orEmpty()
                val arr = root.getJSONArray("hosts")
                val toImport = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    HostBackupEntry(metadata = HostEntity(
                        id = 0,
                        label = o.getString("label"),
                        hostname = o.getString("hostname"),
                        port = if (o.has("port")) o.get("port").toString().toIntOrNull() ?: throw InvalidHostBackup() else 22,
                        username = o.getString("username"),
                        password = null,
                        encryptedPassword = null,
                        knownHostsEntry = null,
                        agentForwarding = o.optBoolean("agentForwarding", false),
                        lastConnected = if (o.has("lastConnected")) o.getLong("lastConnected") else null,
                        jumpHosts = o.optString("jumpHosts").takeIf { it.isNotEmpty() },
                        jumpHostKeys = null,
                        portForwardings = o.optString("portForwardings").takeIf { it.isNotEmpty() },
                        jumpMode = o.optString("jumpMode", "simple"),
                        jumpHostIdList = o.optString("jumpHostIdList").takeIf { it.isNotEmpty() },
                        sftpStartMode = o.optString("sftpStartMode", "last"),
                        sftpStartDir = o.optString("sftpStartDir").takeIf { it.isNotEmpty() },
                        sftpShowHidden = o.optBoolean("sftpShowHidden", false),
                        allowLegacyCiphers = o.optBoolean("allowLegacyCiphers", false),
                        color = if (o.has("color")) o.getInt("color") else null,
                        position = if (o.has("position")) o.getInt("position") else null,
                        connectCount = o.optInt("connectCount", 0),
                    ),
                        keyLabel = o.optString("keyLabel").takeIf { it.isNotEmpty() },
                        groupName = o.optString("group").takeIf { it.isNotEmpty() },
                        jumpHostLabels = if (o.has("jumpHostLabels")) o.getJSONArray("jumpHostLabels").let { labels ->
                            (0 until labels.length()).map { labels.getString(it) }
                        } else null,
                    )
                }
                val importer = HostBackupImporter(
                    transaction = { block -> sshBorgApp.db.withTransaction { block() } },
                    readHosts = hostDao::getAllOnce, readKeys = keyDao::getAllOnce, readGroups = groupDao::getAllOnce,
                    writeHost = hostDao::upsert, writeGroup = groupDao::upsert,
                )
                val result = importer.restore(HostBackupPlan(groups, toImport))
                // DataStore cannot share a Room transaction. Report partial settings failure
                // accurately instead of claiming that the committed host import failed.
                try {
                    root.optJSONObject("settings")?.let { prefs.importSettingsJson(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _error.tryEmit(getApplication<Application>().getString(
                        R.string.backup_settings_import_failed, result.inserted, result.updated))
                    return@launch
                }
                _message.tryEmit(
                    getApplication<Application>().getString(R.string.backup_import_success, result.inserted, result.updated)
                )
            }.onFailure {
                if (it is CancellationException) throw it
                _error.tryEmit(getApplication<Application>().getString(R.string.backup_import_failed))
            }
        }
    }


}
