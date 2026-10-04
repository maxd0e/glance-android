package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class DuressNetworkPolicyTest {
    @Test fun `offline duress session cannot start Tor or sync`() {
        assertEquals(false, duressNetworkAllowed(active = true, offlineMode = true))
        assertEquals(true, duressNetworkAllowed(active = true, offlineMode = false))
        assertEquals(false, duressNetworkAllowed(active = false, offlineMode = false))
    }
}
