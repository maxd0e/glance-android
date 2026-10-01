package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PendingBackupExportTest {
    @Test
    fun `encrypted export remains available after its initiating UI is gone`() {
        val pending = PendingBackupExport()
        val encryptedBackup = byteArrayOf(1, 2, 3)

        pending.stage(encryptedBackup, watchedTargetCount = 2)

        assertSame(encryptedBackup, pending.takeForWrite())
        pending.completeWrite(encryptedBackup, succeeded = true)
        assertEquals(PendingBackupExportState.Idle, pending.state.value)
        assertEquals(BackupExportFeedback.Exported(2), pending.consumeFeedback())
        assertEquals(listOf(0, 0, 0), encryptedBackup.map(Byte::toInt))
    }

    @Test
    fun `cancelled export clears its encrypted bytes without feedback`() {
        val pending = PendingBackupExport()
        val encryptedBackup = byteArrayOf(1, 2, 3)

        pending.stage(encryptedBackup, watchedTargetCount = 2)
        pending.clear()

        assertEquals(PendingBackupExportState.Idle, pending.state.value)
        assertEquals(null, pending.consumeFeedback())
        assertEquals(listOf(0, 0, 0), encryptedBackup.map(Byte::toInt))
    }

    @Test
    fun `failed export is reported after encrypted bytes are cleared`() {
        val pending = PendingBackupExport()
        val encryptedBackup = byteArrayOf(1, 2, 3)

        pending.stage(encryptedBackup, watchedTargetCount = 2)
        pending.completeWrite(requireNotNull(pending.takeForWrite()), succeeded = false)

        assertEquals(PendingBackupExportState.Idle, pending.state.value)
        assertEquals(BackupExportFeedback.Failed, pending.consumeFeedback())
        assertEquals(listOf(0, 0, 0), encryptedBackup.map(Byte::toInt))
    }

    @Test
    fun `new export replaces and clears the previous encrypted bytes`() {
        val pending = PendingBackupExport()
        val first = byteArrayOf(1, 2, 3)
        val replacement = byteArrayOf(4, 5, 6)

        pending.stage(first, watchedTargetCount = 1)
        pending.stage(replacement, watchedTargetCount = 2)

        assertEquals(listOf(0, 0, 0), first.map(Byte::toInt))
        assertSame(replacement, pending.takeForWrite())
    }
}
