package app.glance.wallet

import app.glance.wallet.core.security.StealthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8StealthTest {
    @Test fun `calculator computes each supported operation`() {
        assertEquals("20", calculated(CalculatorOperation.ADD, "5", "15").result)
        assertEquals("5", calculated(CalculatorOperation.SUBTRACT, "15", "10").result)
        assertEquals("25", calculated(CalculatorOperation.MULTIPLY, "5", "5").result)
        assertEquals("3", calculated(CalculatorOperation.DIVIDE, "15", "5").result)
    }

    @Test fun `calculator prevents duplicate decimals and replaces a pending operation`() {
        val decimalState = CalculatorState().reduce(CalculatorAction.Digit(5)).reduce(CalculatorAction.Decimal)
        assertEquals("5.", decimalState.display)
        assertEquals("5.", decimalState.reduce(CalculatorAction.Decimal).display)

        val operationState = CalculatorState()
            .reduce(CalculatorAction.Digit(5))
            .reduce(CalculatorAction.Operation(CalculatorOperation.ADD))
            .reduce(CalculatorAction.Operation(CalculatorOperation.MULTIPLY))
        assertEquals("5x", operationState.display)
    }

    @Test fun `calculator deletes the active input and all clear resets it`() {
        val withSecondNumber = CalculatorState()
            .reduce(CalculatorAction.Digit(5))
            .reduce(CalculatorAction.Operation(CalculatorOperation.ADD))
            .reduce(CalculatorAction.Digit(3))
        assertEquals("5+", withSecondNumber.reduce(CalculatorAction.Delete).display)
        assertEquals("5", withSecondNumber.reduce(CalculatorAction.Delete).reduce(CalculatorAction.Delete).display)
        assertEquals("0", withSecondNumber.reduce(CalculatorAction.Clear).display)
    }

    @Test fun `calculator reports invalid division rather than a nonfinite result`() {
        assertEquals("Error", calculated(CalculatorOperation.DIVIDE, "5", "0").result)
    }

    @Test fun `calculator limits each operand to eight digits`() {
        val state = (1..9).fold(CalculatorState()) { current, _ -> current.reduce(CalculatorAction.Digit(1)) }
        assertEquals("11111111", state.display)
    }

    @Test fun `calculator gesture opens only after five consecutive equals taps`() {
        val fourEqualsTaps = (1..4).fold(CalculatorState()) { state, _ ->
            assertFalse(calculatorUnlockTriggered(state, CalculatorAction.Calculate))
            state.reduce(CalculatorAction.Calculate)
        }
        assertTrue(calculatorUnlockTriggered(fourEqualsTaps, CalculatorAction.Calculate))

        val interrupted = CalculatorState()
            .reduce(CalculatorAction.Calculate)
            .reduce(CalculatorAction.Calculate)
            .reduce(CalculatorAction.Digit(5))
            .reduce(CalculatorAction.Calculate)
            .reduce(CalculatorAction.Calculate)
            .reduce(CalculatorAction.Calculate)
        assertFalse(calculatorUnlockTriggered(interrupted, CalculatorAction.Calculate))
    }

    @Test fun `stealth setup offers only off and calculator`() {
        assertEquals(
            listOf(StealthMode.OFF, StealthMode.CALCULATOR),
            stealthModeOptions(),
        )
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

    @Test fun `disguised launcher requires its unlock flow again after backgrounding`() {
        val gate = StealthDisguiseGate(StealthMode.CALCULATOR)
        assertFalse(gate.isGlanceOpen)
        gate.openGlance()
        assertTrue(gate.isGlanceOpen)
        gate.reenterDisguise()
        assertFalse(gate.isGlanceOpen)
    }

    @Test fun `normal launcher does not show a disguise after backgrounding`() {
        val gate = StealthDisguiseGate(StealthMode.OFF)
        gate.reenterDisguise()
        assertTrue(gate.isGlanceOpen)
    }

    @Test fun `calculator launcher re-gates a task originally opened through Glance`() {
        val gate = StealthDisguiseGate(StealthMode.OFF)
        assertTrue(gate.isGlanceOpen)

        gate.enterLauncher(StealthMode.CALCULATOR)

        assertEquals(StealthMode.CALCULATOR, gate.launchMode)
        assertFalse(gate.isGlanceOpen)
    }

    private fun calculated(operation: CalculatorOperation, number1: String, number2: String): CalculatorState =
        number1.fold(CalculatorState()) { state, digit -> state.reduce(CalculatorAction.Digit(digit.digitToInt())) }
            .reduce(CalculatorAction.Operation(operation))
            .let { state -> number2.fold(state) { current, digit -> current.reduce(CalculatorAction.Digit(digit.digitToInt())) } }
            .reduce(CalculatorAction.Calculate)
}
