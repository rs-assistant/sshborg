package com.sshborg.ui.keys

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sshborg.SshBorgApp
import com.sshborg.data.ProtectedCredentials
import com.sshborg.data.db.SshKeyEntity
import com.sshborg.data.ssh.SshManager
import com.sshborg.data.ssh.ImportedKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeysViewModel(app: Application) : AndroidViewModel(app) {

    private val sshBorgApp = app as SshBorgApp
    private val dao        = sshBorgApp.db.sshKeyDao()
    var isSaving by mutableStateOf(false)
        private set

    val keys: StateFlow<List<SshKeyEntity>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun generateKey(label: String, type: String, size: String = "", onError: (String) -> Unit, onDone: () -> Unit) {
        writeKey(label, onError, onDone) {
            val (priv, pub) = SshManager.generateKeyPair(type, comment = commentFor(label), bits = size.toIntOrNull())
            ImportedKey(priv, pub, type)
        }
    }

    fun importKey(
        label: String,
        pem: String,
        passphrase: String?,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        writeKey(label, onError, onDone) {
            val imported = SshManager.importPrivateKey(pem, passphrase.takeIf { !it.isNullOrEmpty() })
            imported.copy(publicKey = withComment(imported.publicKey, commentFor(label)))
        }
    }

    private fun writeKey(
        label: String,
        onError: (String) -> Unit,
        onDone: () -> Unit,
        prepare: suspend () -> ImportedKey,
    ) {
        if (isSaving) return
        isSaving = true
        viewModelScope.launch {
            var prepared = false
            try {
                val key = withContext(Dispatchers.IO) { prepare() }
                prepared = true
                withContext(Dispatchers.IO) {
                    persistEncryptedKey(label, key, ProtectedCredentials::encrypt) { dao.upsert(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Do not put raw storage/Keystore errors or key material in UI messages.
                val code = if (prepared) "save_failed" else failure.message.takeIf {
                    it in setOf("encrypted", "wrong_passphrase", "unsupported_encryption")
                } ?: "invalid"
                onError(code)
                return@launch
            } finally {
                isSaving = false
            }
            onDone()
        }
    }

    fun renameKey(key: SshKeyEntity, newLabel: String) = viewModelScope.launch {
        // Also refresh the comment embedded in the public key so it matches the new name.
        dao.rename(key.id, newLabel, withComment(key.publicKey, commentFor(newLabel)))
    }

    fun deleteKey(key: SshKeyEntity) = viewModelScope.launch { dao.delete(key) }

    /**
     * A public-key comment is the free-text tail of the line; keep it a single token by collapsing
     * whitespace to '_'. The label shown in the app keeps its spaces — only the embedded comment
     * is normalized.
     */
    private fun commentFor(label: String): String =
        label.trim().replace(Regex("\\s+"), "_")

    /** Replaces the comment (3rd field) of an OpenSSH public key line with [comment]. */
    private fun withComment(publicKey: String, comment: String): String {
        val parts = publicKey.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return publicKey            // malformed — leave as-is
        val base = "${parts[0]} ${parts[1]}"
        return if (comment.isBlank()) base else "$base $comment"
    }
}
