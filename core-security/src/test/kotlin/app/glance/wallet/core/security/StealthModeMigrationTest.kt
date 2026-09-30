package app.glance.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class StealthModeMigrationTest {
    @Test
    fun legacyNotesModeResetsToOff() {
        assertEquals(StealthMode.OFF, stealthModeFromStorage("NOTES"))
    }
}
