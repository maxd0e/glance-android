package app.glance.wallet

import org.junit.Assert.assertNotEquals
import org.junit.Test

class ManifestTrustTest {
    @Test fun `production configuration never trusts the published RFC test key`() {
        assertNotEquals(
            "MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=",
            ManifestTrust.PUBLIC_KEY_BASE64,
        )
    }
}
