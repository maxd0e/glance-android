package app.glance.wallet

import app.glance.wallet.core.network.ServerDefinition
import app.glance.wallet.core.network.ServerPool

/**
 * The emulator reaches the host's disposable regtest stack through 10.0.2.2.
 * This is called only from the isolated regtest build type.
 */
internal fun regtestServerPool(): ServerPool = ServerPool(
    initial = listOf(ServerDefinition.electrum(host = "10.0.2.2", port = 50_001, useTls = false)),
)
