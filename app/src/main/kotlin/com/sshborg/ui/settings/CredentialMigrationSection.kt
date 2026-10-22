package com.sshborg.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sshborg.R

@Composable
internal fun CredentialMigrationSection(vm: CredentialMigrationViewModel = viewModel()) {
    val candidates by vm.candidates.collectAsState()
    var selected by remember { mutableStateOf<MigrationCandidate?>(null) }
    Column {
        Text(stringResource(R.string.credential_migration_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text(stringResource(R.string.credential_migration_explanation),
            modifier = Modifier.padding(horizontal = 16.dp))
        candidates.forEach { candidate ->
            ListItem(
                headlineContent = { Text(candidate.label) },
                supportingContent = {
                    Text(stringResource(if (candidate.kind == CredentialKind.SSH_KEY)
                        R.string.credential_migration_key else R.string.credential_migration_password))
                },
                trailingContent = {
                    TextButton(onClick = { selected = candidate }, enabled = !vm.busy) {
                        Text(stringResource(R.string.credential_migration_action))
                    }
                },
            )
        }
        if (vm.busy) CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        vm.status?.let { Text(stringResource(it), modifier = Modifier.padding(16.dp)) }
    }
    selected?.let { candidate ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.credential_migration_confirm_title, candidate.label)) },
            text = { Text(stringResource(R.string.credential_migration_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    vm.migrate(candidate, confirmed = true)
                }, enabled = !vm.busy) { Text(stringResource(R.string.credential_migration_action)) }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
