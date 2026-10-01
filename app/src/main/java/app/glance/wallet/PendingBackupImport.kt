package app.glance.wallet

import androidx.lifecycle.ViewModel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Encrypted backup bytes selected before the document picker locks the app. Never persisted. */
internal sealed interface PendingBackupImportState {
    data object Idle : PendingBackupImportState
    data class Ready(val bytes: ByteArray) : PendingBackupImportState
    data object Unreadable : PendingBackupImportState
}

internal class PendingBackupImport : ViewModel() {
    private val mutableState = MutableStateFlow<PendingBackupImportState>(PendingBackupImportState.Idle)
    val state: StateFlow<PendingBackupImportState> = mutableState.asStateFlow()

    fun stage(bytes: ByteArray) {
        clear()
        mutableState.value = PendingBackupImportState.Ready(bytes)
    }

    fun markUnreadable() {
        clear()
        mutableState.value = PendingBackupImportState.Unreadable
    }

    fun clear() {
        (mutableState.value as? PendingBackupImportState.Ready)?.bytes?.fill(0)
        mutableState.value = PendingBackupImportState.Idle
    }

    override fun onCleared() {
        clear()
    }
}

internal fun readBackupDocument(input: InputStream, maximumBytes: Int = MAXIMUM_BACKUP_DOCUMENT_BYTES): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        totalBytes += read
        require(totalBytes <= maximumBytes) { "Backup file is too large" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private const val MAXIMUM_BACKUP_DOCUMENT_BYTES = 1_500_000
