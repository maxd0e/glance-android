package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupCodecTest {
    @Test fun `encrypted backup round trips without exposing payload`() {
        val snapshot = BackupSnapshot(
            watchedKeys = listOf(BackupWatchedKey("key-1", "Savings", "zpub-example", "NATIVE_SEGWIT", "HD_KEY", null)),
            labels = listOf(BackupLabel("ADDRESS", "bc1qexample", "Cold storage")),
            settings = BackupSettings(fiatCurrency = "EUR", torEnabled = true),
        )

        val encoded = BackupCodec.encrypt(snapshot, "backup passphrase".toCharArray(), random = DeterministicBackupRandom)

        assertEquals(snapshot, BackupCodec.decrypt(encoded, "backup passphrase".toCharArray()))
        assert(!encoded.decodeToString().contains("zpub-example"))
    }

    @Test fun `wrong passphrase and tampering expose only generic invalid backup error`() {
        val encoded = BackupCodec.encrypt(BackupSnapshot(), "correct".toCharArray(), random = DeterministicBackupRandom)

        expectInvalid { BackupCodec.decrypt(encoded, "wrong".toCharArray()) }
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        expectInvalid { BackupCodec.decrypt(encoded, "correct".toCharArray()) }
    }

    @Test fun `empty passphrase and unsupported format are rejected`() {
        try { BackupCodec.encrypt(BackupSnapshot(), charArrayOf()); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) { }
        expectInvalid { BackupCodec.decrypt("{}".encodeToByteArray(), "x".toCharArray()) }
    }

    @Test fun `backup settings have no stealth mode field`() {
        assertEquals(false, BackupSettings::class.java.declaredFields.any { it.name == "stealthMode" })
    }

    @Test fun `Tor-off restore requires the explicit direct-connection warning`() {
        assertEquals(true, requiresDirectConnectionRestoreWarning(BackupSnapshot(settings = BackupSettings(torEnabled = false))))
        assertEquals(false, requiresDirectConnectionRestoreWarning(BackupSnapshot(settings = BackupSettings(torEnabled = true))))
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); throw AssertionError("expected invalid backup") } catch (_: InvalidBackupException) { }
    }
}
