package com.sshborg.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sshborg.R
import com.sshborg.SshBorgApp
import com.sshborg.data.ProtectedCredentials
import com.sshborg.data.isLegacyEncryptedCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class CredentialKind { SSH_KEY, HOST_PASSWORD }

internal data class MigrationCandidate(
    val kind: CredentialKind,
    val id: Long,
    val label: String,
    val encrypted: String,
)

internal class CredentialMigrationViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as SshBorgApp).db
    private val keys = db.sshKeyDao()
    private val hosts = db.hostDao()

    val candidates = combine(keys.getAll(), hosts.getAll()) { keyRows, hostRows ->
        keyRows.mapNotNull { key ->
            key.encryptedBlob?.takeIf(::isLegacyEncryptedCredential)?.let {
                MigrationCandidate(CredentialKind.SSH_KEY, key.id, key.label, it)
            }
        } + hostRows.mapNotNull { host ->
            host.encryptedPassword?.takeIf(::isLegacyEncryptedCredential)?.let {
                MigrationCandidate(CredentialKind.HOST_PASSWORD, host.id, host.label, it)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<Int?>(null)
        private set

    fun migrate(candidate: MigrationCandidate, confirmed: Boolean) {
        if (!confirmed || busy) return
        busy = true
        status = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ProtectedCredentials.migration.migrate(candidate.encrypted, confirmed) { expected, replacement ->
                        val changed = when (candidate.kind) {
                            CredentialKind.SSH_KEY -> keys.replaceEncryptedBlob(candidate.id, expected, replacement)
                            CredentialKind.HOST_PASSWORD -> hosts.replaceEncryptedPassword(candidate.id, expected, replacement)
                        }
                        changed == 1
                    }
                }
                status = R.string.credential_migration_success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = R.string.credential_migration_failed
            } finally {
                busy = false
            }
        }
    }
}
