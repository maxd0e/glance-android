package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

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

    @Test fun `backup codec avoids the Java 8 only Base64 API`() {
        val source = File("src/main/java/app/glance/wallet/BackupCodec.kt").readText()

        assertEquals(false, source.contains("java.util.Base64"))
    }

    @Test fun `backup Base64 decoder accepts standard padded values`() {
        val encoder = BackupCodec::class.java.getDeclaredMethod("base64", ByteArray::class.java).apply { isAccessible = true }
        val decoder = BackupCodec::class.java.getDeclaredMethod("base64Bytes", String::class.java).apply { isAccessible = true }

        assertEquals("AAEC", encoder.invoke(BackupCodec, byteArrayOf(0, 1, 2)))
        assertEquals("AAE=", encoder.invoke(BackupCodec, byteArrayOf(0, 1)))
        assertEquals("AA==", encoder.invoke(BackupCodec, byteArrayOf(0)))
        assertArrayEquals(byteArrayOf(0, 1, 2), decoder.invoke(BackupCodec, "AAEC") as ByteArray)
        assertArrayEquals(byteArrayOf(0, 1), decoder.invoke(BackupCodec, "AAE=") as ByteArray)
        assertArrayEquals(byteArrayOf(0), decoder.invoke(BackupCodec, "AA==") as ByteArray)
        val bytes = ByteArray(257) { it.toByte() }
        assertArrayEquals(bytes, decoder.invoke(BackupCodec, encoder.invoke(BackupCodec, bytes)) as ByteArray)
    }

    @Test fun `Tor-off restore requires the explicit direct-connection warning`() {
        assertEquals(true, requiresDirectConnectionRestoreWarning(BackupSnapshot(settings = BackupSettings(torEnabled = false))))
        assertEquals(false, requiresDirectConnectionRestoreWarning(BackupSnapshot(settings = BackupSettings(torEnabled = true))))
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); throw AssertionError("expected invalid backup") } catch (_: InvalidBackupException) { }
    }
}
