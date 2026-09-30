package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8StealthTest {
    @Test fun `calculator gesture opens only after five multiply equals`() {
        assertFalse(calculatorUnlockTriggered(listOf("5", "×")))
        assertTrue(calculatorUnlockTriggered(listOf("5", "×", "=")))
        assertFalse(calculatorUnlockTriggered(listOf("4", "×", "=")))
    }

    @Test fun `notes codeword is exact and valid`() {
        assertTrue(isValidNotesCodeword("Night Note"))
        assertFalse(isValidNotesCodeword("abc"))
        assertTrue(notesCodewordMatches("Night Note", "Night Note"))
        assertFalse(notesCodewordMatches("Night Note", "night note"))
    }

    @Test fun `shake requires two peaks inside window and observes cooldown`() {
        var state = ShakeState()
        state = state.recordPeak(1_000)
        assertFalse(state.toggled)
        state = state.recordPeak(1_300)
        assertTrue(state.toggled)
        assertEquals(2_300, state.cooldownUntilMillis)
        assertFalse(state.recordPeak(1_400).toggled)
    }

    @Test fun `street mode masks every monetary display`() {
        assertEquals("•••• sats", streetMaskedSats())
        assertEquals("••••", streetMaskedFiat())
    }
}
