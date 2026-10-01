package app.glance.wallet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun requiresDirectConnectionRestoreWarning(snapshot: BackupSnapshot): Boolean = !snapshot.settings.torEnabled

@Composable internal fun BackupSettingsActions(repository: BackupRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportPassphrase by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val passphrase = exportPassphrase ?: return@rememberLauncherForActivityResult
        exportPassphrase = null
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(BackupCodec.encrypt(repository.snapshot(), passphrase.toCharArray())) } ?: error("write") } }
                .onSuccess { message = "Backup exported." }.onFailure { message = "Backup export failed. Try again." }
        }
    }
    SettingsGroup("Backup", "settings_group_backup") {
        SettingsDisclosureRow("Export encrypted backup", modifier = Modifier.testTag("backup_export")) { exportPassphrase = "" }
    }
    exportPassphrase?.let { value -> PassphraseDialog("Export backup", value, confirmation = true, onDismiss = { exportPassphrase = null }) { passphrase -> exportPassphrase = passphrase; create.launch("glance-backup-${System.currentTimeMillis()}.glancebackup") } }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { Button(onClick = { message = null }) { Text("OK") } }, title = { Text("Backup") }, text = { Text(text) }) }
}

@Composable
internal fun BackupImportWalletAction(
    repository: BackupRepository,
    onImported: suspend (BackupSettings) -> Unit,
    trigger: @Composable (onImport: () -> Unit, canImport: Boolean, unavailableMessage: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canRestore by androidx.compose.runtime.produceState<Boolean?>(initialValue = null, repository) {
        value = withContext(Dispatchers.IO) { repository.canRestore() }
    }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importPassphrase by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<BackupSnapshot?>(null) }
    var directConnectionConfirmation by remember { mutableStateOf<BackupSnapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importBytes = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.readBytes() }
            }.getOrNull() ?: run { message = "Backup could not be read."; null }
        }
    }
    trigger(
        { if (canRestore == true) open.launch(arrayOf("application/octet-stream", "application/json", "*/*")) },
        canRestore == true,
        if (canRestore == false) "Import wallet requires an empty local wallet." else null,
    )
    importBytes?.let { bytes -> PassphraseDialog("Import backup", importPassphrase.orEmpty(), onDismiss = { importBytes = null; importPassphrase = null }) { passphrase ->
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { BackupCodec.decrypt(bytes, passphrase.toCharArray()) } }
                .onSuccess { candidate = it; importBytes = null }
                .onFailure { message = "Backup cannot be opened."; importBytes = null }
        }
    } }
    fun restore(snapshot: BackupSnapshot, directConnectionConfirmed: Boolean = false) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { repository.restore(snapshot, directConnectionConfirmed) } }
            .onSuccess { onImported(snapshot.settings); message = "Backup imported. Wallet reconciliation started." }
            .onFailure { message = "Backup cannot be restored." }
    }
    candidate?.let { snapshot -> AlertDialog(onDismissRequest = { candidate = null }, title = { Text("Restore backup?") }, text = { Text("Restore ${snapshot.watchedKeys.size} watched target(s), ${snapshot.labels.size} label(s), and saved settings. Cached balances and history will reconcile automatically.") }, dismissButton = { Button(onClick = { candidate = null }) { Text("Cancel") } }, confirmButton = { Button(onClick = { candidate = null; if (requiresDirectConnectionRestoreWarning(snapshot)) directConnectionConfirmation = snapshot else restore(snapshot) }) { Text("Import") } }) }
    directConnectionConfirmation?.let { snapshot -> AlertDialog(onDismissRequest = { directConnectionConfirmation = null }, title = { Text("Turn off Tor?") }, text = { Text("The querying server will see your device's real IP address. Continue only if you accept this privacy risk.") }, dismissButton = { Button(onClick = { directConnectionConfirmation = null }) { Text("Cancel import") } }, confirmButton = { Button(onClick = { directConnectionConfirmation = null; restore(snapshot, directConnectionConfirmed = true) }) { Text("Turn off Tor") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { Button(onClick = { message = null }) { Text("OK") } }, title = { Text("Backup") }, text = { Text(text) }) }
}

@Composable private fun PassphraseDialog(title: String, initial: String, confirmation: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf(initial) }; var confirm by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column { Text("This passphrase cannot be recovered."); OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)); if (confirmation) OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm passphrase") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) } }, dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }, confirmButton = { Button(enabled = passphrase.isNotEmpty() && (!confirmation || passphrase == confirm), onClick = { onConfirm(passphrase) }) { Text(if (confirmation) "Export" else "Continue") } })
}
