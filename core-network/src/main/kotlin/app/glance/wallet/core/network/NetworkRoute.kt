package app.glance.wallet.core.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The only supported routing modes for wallet network traffic. */
sealed interface NetworkRoute {
    data object Direct : NetworkRoute
    data class Socks(val address: InetSocketAddress) : NetworkRoute {
        init {
            require(address.address?.isLoopbackAddress == true || address.hostString == "127.0.0.1") {
                "SOCKS routing must use a loopback proxy."
            }
        }
    }
}

/** Supplies the route at request/connection time so a Tor state change cannot be bypassed. */
fun interface NetworkClientFactorySource {
    fun current(): NetworkClientFactory
    fun acquire(): NetworkLease = NetworkLease(current())
}

class NetworkLease internal constructor(
    val factory: NetworkClientFactory,
    private val allowed: () -> Boolean = { true },
    private val registerClose: (() -> Unit) -> AutoCloseable = { AutoCloseable {} },
) {
    fun checkActive() {
        if (!allowed()) throw NetworkException("Network session is no longer active")
    }
    fun register(close: () -> Unit): AutoCloseable = registerClose(close)
}

/** Revokes blocking sockets and HTTP calls synchronously before a session policy changes. */
class RevocableNetworkClientFactorySource(private val delegate: NetworkClientFactorySource) : NetworkClientFactorySource {
    private var active = false
    private var generation = 0L
    private val closers = mutableSetOf<() -> Unit>()

    @Synchronized fun activate() { active = true }

    fun revoke() {
        val toClose = synchronized(this) {
            active = false
            generation++
            closers.toList().also { closers.clear() }
        }
        toClose.forEach { runCatching { it() } }
    }

    override fun current(): NetworkClientFactory = acquire().factory

    override fun acquire(): NetworkLease = synchronized(this) {
        if (!active) throw NetworkException("Network session is inactive")
        val factory = delegate.current()
        val issued = generation
        NetworkLease(factory, allowed = { synchronized(this) { active && generation == issued } }) { close ->
            val registered = synchronized(this) {
                if (!active || generation != issued) false else { closers += close; true }
            }
            if (!registered) {
                runCatching { close() }
                throw NetworkException("Network session is no longer active")
            }
            AutoCloseable { synchronized(this) { closers.remove(close) } }
        }
    }
}

internal fun <T> NetworkClientFactorySource.executeHttp(
    request: Request,
    client: (NetworkClientFactory) -> OkHttpClient = { it.okHttpClient() },
    read: (Response) -> T,
): T {
    val lease = acquire()
    val call = client(lease.factory).newCall(request)
    val registration = lease.register(call::cancel)
    try {
        lease.checkActive()
        return call.execute().use { response ->
            lease.checkActive()
            read(response).also { lease.checkActive() }
        }
    } finally {
        registration.close()
    }
}

internal fun Response.readBodyLimited(maxBytes: Long): String {
    val body = body ?: throw NetworkException("Network response body is empty")
    val source = body.source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) throw NetworkException("Network response is too large")
    return source.readUtf8()
}

object DirectNetworkClientFactorySource : NetworkClientFactorySource {
    override fun current(): NetworkClientFactory = NetworkClientFactory(NetworkRoute.Direct)
}

/**
 * Creates protocol clients from one route so Electrum, Esplora, and both fiat providers cannot
 * accidentally diverge between Tor and direct networking.
 */
class NetworkClientFactory(val route: NetworkRoute = NetworkRoute.Direct) {
    internal val proxy: Proxy? = (route as? NetworkRoute.Socks)?.let { Proxy(Proxy.Type.SOCKS, it.address) }

    /**
     * OkHttp owns its connection pool, so retain one client for each immutable route. Sources may
     * create factories at request time as Tor state changes without forfeiting connection reuse.
     */
    fun okHttpClient(): OkHttpClient = clients.computeIfAbsent(route) { configuredRoute ->
        val configuredProxy = (configuredRoute as? NetworkRoute.Socks)
            ?.let { Proxy(Proxy.Type.SOCKS, it.address) }
        OkHttpClient.Builder()
            .proxy(configuredProxy)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    internal fun socket(): java.net.Socket = proxy?.let { java.net.Socket(it) } ?: java.net.Socket()
    internal fun electrumAddress(host: String, port: Int): InetSocketAddress = when (route) {
        is NetworkRoute.Socks -> InetSocketAddress.createUnresolved(host, port)
        NetworkRoute.Direct -> InetSocketAddress(host, port)
    }

    private companion object {
        val clients = ConcurrentHashMap<NetworkRoute, OkHttpClient>()
    }
}
