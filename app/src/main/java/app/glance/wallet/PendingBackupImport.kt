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

/** Keeps an already encrypted export alive while Android's document picker locks the app. */
internal sealed interface PendingBackupExportState {
    data object Idle : PendingBackupExportState
    data object Ready : PendingBackupExportState
    data object Writing : PendingBackupExportState
}

internal sealed interface BackupExportFeedback {
    data class Exported(val watchedTargetCount: Int) : BackupExportFeedback
    data object Failed : BackupExportFeedback
}

internal class PendingBackupExport : ViewModel() {
    private val lock = Any()
    private val mutableState = MutableStateFlow<PendingBackupExportState>(PendingBackupExportState.Idle)
    private val mutableFeedback = MutableStateFlow<BackupExportFeedback?>(null)
    val state: StateFlow<PendingBackupExportState> = mutableState.asStateFlow()
    val feedback: StateFlow<BackupExportFeedback?> = mutableFeedback.asStateFlow()
    private var encryptedBytes: ByteArray? = null
    private var watchedTargetCount: Int = 0

    fun stage(bytes: ByteArray, watchedTargetCount: Int) = synchronized(lock) {
        clearLocked()
        encryptedBytes = bytes
        this.watchedTargetCount = watchedTargetCount
        mutableState.value = PendingBackupExportState.Ready
    }

    fun takeForWrite(): ByteArray? = synchronized(lock) {
        if (mutableState.value != PendingBackupExportState.Ready) return null
        encryptedBytes?.also {
            encryptedBytes = null
            mutableState.value = PendingBackupExportState.Writing
        }
    }

    fun completeWrite(bytes: ByteArray, succeeded: Boolean, reportFeedback: Boolean = true) = synchronized(lock) {
        bytes.fill(0)
        val count = watchedTargetCount
        watchedTargetCount = 0
        mutableState.value = PendingBackupExportState.Idle
        if (reportFeedback) mutableFeedback.value = if (succeeded) BackupExportFeedback.Exported(count) else BackupExportFeedback.Failed
    }

    fun clear() = synchronized(lock) { clearLocked() }

    fun consumeFeedback(): BackupExportFeedback? = synchronized(lock) {
        mutableFeedback.value.also { mutableFeedback.value = null }
    }

    override fun onCleared() {
        clear()
    }

    private fun clearLocked() {
        encryptedBytes?.fill(0)
        encryptedBytes = null
        watchedTargetCount = 0
        mutableState.value = PendingBackupExportState.Idle
    }
}
