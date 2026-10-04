package app.glance.wallet.core.network

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkSessionGateTest {
    @Test fun `revoked Electrum session cannot reuse its connected socket`() {
        ServerSocket(0).use { server ->
            val received = AtomicInteger()
            val worker = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    reader.readLine()?.let {
                        received.incrementAndGet()
                        writer.write("""{"id":1,"result":1}""")
                        writer.newLine()
                        writer.flush()
                    }
                    runCatching { reader.readLine() }.getOrNull()?.let { received.incrementAndGet() }
                }
            }.apply { isDaemon = true; start() }
            val gate = RevocableNetworkClientFactorySource(DirectNetworkClientFactorySource)
            gate.activate()
            val transport = SocketElectrumTransport(
                NetworkEndpoint(BlockchainProtocol.ELECTRUM, "127.0.0.1", server.localPort, false),
                clientFactorySource = gate,
            )
            try {
                transport.request("blockchain.headers.subscribe")
                gate.revoke()
                assertThrows(NetworkException::class.java) { transport.request("blockchain.headers.subscribe") }
                worker.join(1_000)
                assertEquals(1, received.get())
            } finally { transport.close() }
        }
    }
}
