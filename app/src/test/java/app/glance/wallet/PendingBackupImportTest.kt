package app.glance.wallet

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class PendingBackupImportTest {
    @Test
    fun `selected encrypted backup remains available until it is cleared`() {
        val pending = PendingBackupImport()
        val encryptedBackup = byteArrayOf(1, 2, 3)

        pending.stage(encryptedBackup)

        assertSame(encryptedBackup, (pending.state.value as PendingBackupImportState.Ready).bytes)
        pending.clear()
        assertEquals(PendingBackupImportState.Idle, pending.state.value)
        assertEquals(listOf(0, 0, 0), encryptedBackup.map(Byte::toInt))
    }

    @Test
    fun `unreadable selection is surfaced without retaining backup bytes`() {
        val pending = PendingBackupImport()

        pending.markUnreadable()

        assertEquals(PendingBackupImportState.Unreadable, pending.state.value)
        pending.clear()
        assertEquals(PendingBackupImportState.Idle, pending.state.value)
    }

    @Test
    fun `backup document reads are bounded`() {
        assertEquals(
            listOf(1, 2, 3),
            readBackupDocument(ByteArrayInputStream(byteArrayOf(1, 2, 3)), maximumBytes = 3).map(Byte::toInt),
        )
        assertThrows(IllegalArgumentException::class.java) {
            readBackupDocument(ByteArrayInputStream(byteArrayOf(1, 2, 3)), maximumBytes = 2)
        }
    }
}
