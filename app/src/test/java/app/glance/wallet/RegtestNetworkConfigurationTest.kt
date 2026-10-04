package app.glance.wallet

import app.glance.wallet.core.network.ServerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RegtestNetworkConfigurationTest {
    @Test
    fun regtestPoolUsesOnlyTheEmulatorLocalElectrumEndpoint() {
        val endpoint = regtestServerPool().choose(ServerRole.ELECTRUM)

        assertEquals("10.0.2.2", endpoint.host)
        assertEquals(50_001, endpoint.port)
        assertFalse(endpoint.useTls)
    }
}
