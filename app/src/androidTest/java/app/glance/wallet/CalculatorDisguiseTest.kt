package app.glance.wallet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CalculatorDisguiseTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun fiveConsecutiveEqualsTapsOpenGlance() {
        var opened = false
        composeRule.setContent { GlanceTheme { CalculatorDisguise { opened = true } } }

        repeat(5) {
            composeRule.onNodeWithContentDescription("Calculator key =").performClick()
        }

        assertTrue(opened)
    }

    @Test fun ordinaryCalculationStaysInTheCalculatorAndUpdatesTheDisplay() {
        composeRule.setContent { GlanceTheme { CalculatorDisguise {} } }

        composeRule.onNodeWithContentDescription("Calculator key 5").performClick()
        composeRule.onNodeWithContentDescription("Calculator key x").performClick()
        composeRule.onNodeWithContentDescription("Calculator key 2").performClick()
        composeRule.onNodeWithContentDescription("Calculator key =").performClick()

        composeRule.onNodeWithContentDescription("Calculator display 10").assertExists()
    }
}
