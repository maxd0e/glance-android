package app.glance.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeOnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboardingUsesTheOfficialLogoAndRoutesBothWalletEntryActions() {
        var addRequested by mutableStateOf(false)
        var restoreRequested by mutableStateOf(false)

        composeRule.setContent {
            GlanceTheme {
                HomeOnboarding(
                    onAdd = { addRequested = true },
                    onImportWallet = { restoreRequested = true },
                    canImportWallet = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Glance logo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tor routing").assertIsDisplayed()
        composeRule.onNodeWithText("+  Add your first key").performClick()
        composeRule.onNodeWithText("Import backup").performClick()

        assertTrue(addRequested)
        assertTrue(restoreRequested)
    }

    @Test
    fun onboardingExplainsWhenTheLocalStoreCannotBeRestoredInto() {
        composeRule.setContent {
            GlanceTheme {
                HomeOnboarding(
                    onAdd = {},
                    onImportWallet = {},
                    canImportWallet = false,
                    importUnavailableMessage = "Import backup requires an empty local wallet.",
                )
            }
        }

        composeRule.onNodeWithText("Import backup").assertIsNotEnabled()
        composeRule.onNodeWithText("Import backup requires an empty local wallet.").assertIsDisplayed()
    }
}
