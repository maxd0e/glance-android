package app.glance.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun requiresDirectConnectionRestoreWarning(snapshot: BackupSnapshot): Boolean = !snapshot.settings.torEnabled

@Composable
internal fun BackupSettingsActions(
    repository: BackupRepository,
    onEncryptedBackupReady: (ByteArray, Int) -> Unit,
    onImportBackup: () -> Unit,
    canImportBackup: Boolean,
) {
    val scope = rememberCoroutineScope()
    var exportPassphrase by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    SettingsGroup("Backup", "settings_group_backup") {
        SettingsDisclosureRow("Export backup", modifier = Modifier.testTag("backup_export")) { exportPassphrase = "" }
        SettingsDivider()
        SettingsDisclosureRow(
            "Import backup",
            modifier = Modifier.testTag("backup_import"),
            enabled = canImportBackup,
            onClick = onImportBackup,
        )
    }
    exportPassphrase?.let { value -> PassphraseDialog("Export backup", value, confirmation = true, onDismiss = { exportPassphrase = null }) { passphrase ->
        exportPassphrase = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val passphraseChars = passphrase.toCharArray()
                    try {
                        val snapshot = repository.snapshot()
                        BackupCodec.encrypt(snapshot, passphraseChars) to snapshot.watchedKeys.size
                    } finally {
                        passphraseChars.fill('\u0000')
                    }
                }
            }.onSuccess { (encrypted, watchedTargetCount) -> onEncryptedBackupReady(encrypted, watchedTargetCount) }
                .onFailure { message = "Backup export failed. Try again." }
        }
    } }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { Button(onClick = { message = null }) { Text("OK") } }, title = { Text("Backup") }, text = { Text(text) }) }
}

@Composable
internal fun BackupImportWalletAction(
    repository: BackupRepository,
    onImported: suspend (BackupSettings) -> Unit,
    pendingImport: PendingBackupImportState,
    onRequestBackupImport: () -> Unit,
    onClearPendingImport: () -> Unit,
    trigger: @Composable (onImport: () -> Unit, canImport: Boolean, unavailableMessage: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val canRestore by androidx.compose.runtime.produceState<Boolean?>(initialValue = null, repository) {
        value = withContext(Dispatchers.IO) { repository.canRestore() }
    }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importPassphrase by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<BackupSnapshot?>(null) }
    var directConnectionConfirmation by remember { mutableStateOf<BackupSnapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingImport) {
        when (pendingImport) {
            PendingBackupImportState.Idle -> Unit
            PendingBackupImportState.Unreadable -> {
                message = "Backup could not be read."
                onClearPendingImport()
            }
            is PendingBackupImportState.Ready -> importBytes = pendingImport.bytes
        }
    }
    trigger(
        { if (canRestore == true) onRequestBackupImport() },
        canRestore == true,
        if (canRestore == false) "Import backup requires an empty local wallet." else null,
    )
    importBytes?.let { bytes -> PassphraseDialog("Import backup", importPassphrase.orEmpty(), onDismiss = { importBytes = null; importPassphrase = null; onClearPendingImport() }) { passphrase ->
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { BackupCodec.decrypt(bytes, passphrase.toCharArray()) } }
                .onSuccess { candidate = it; importBytes = null; onClearPendingImport() }
                .onFailure { message = "Backup cannot be opened."; importBytes = null; onClearPendingImport() }
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
    val passwordKeyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
    val valid = if (confirmation) passphrase.isNotEmpty() && passphrase == confirm else passphrase.isNotEmpty()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column {
        Text(if (confirmation) "Choose a backup password. This password cannot be recovered." else "Enter the backup password.")
        OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = passwordKeyboard, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        if (confirmation) OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = passwordKeyboard, modifier = Modifier.fillMaxWidth())
    } }, dismissButton = {
        TextButton(
            onClick = onDismiss,
            colors = ButtonDefaults.textButtonColors(contentColor = GlanceText),
            modifier = Modifier.testTag("backup_passphrase_cancel"),
        ) { Text("Cancel") }
    }, confirmButton = {
        Button(
            enabled = valid,
            onClick = { onConfirm(passphrase) },
            colors = ButtonDefaults.buttonColors(
                containerColor = GlanceMandarin,
                contentColor = GlanceBackground,
                disabledContainerColor = GlanceMuted.copy(alpha = 0.38f),
                disabledContentColor = GlanceBackground.copy(alpha = 0.38f),
            ),
            modifier = Modifier.testTag("backup_passphrase_confirm"),
        ) { Text(if (confirmation) "Export" else "Continue") }
    })
}
