package com.panomc.plugins.market.service

import com.panomc.plugins.market.support.FakeClock
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.SocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** 11 section 2: trusted-proxy IP resolution, the loopback exemption and the `UNCONFIGURED_PROXY` health flag. */
class ClientIpResolverTest {
    private val clock = FakeClock(1_000_000L)

    @BeforeEach
    fun setUp() {
        ClientIpResolver.reset()
        ClientIpResolver.clock = clock
    }

    @AfterEach
    fun tearDown() {
        ClientIpResolver.reset()
    }

    /** A request that only knows its socket peer and headers: all the resolver reads. */
    private fun request(peer: String?, vararg headers: Pair<String, String>): HttpServerRequest {
        val map = headers.associate { it.first.lowercase() to it.second }

        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(HttpServerRequest::class.java)
        ) { _, method, args ->
            when (method.name) {
                "remoteAddress" -> peer?.let { SocketAddress.inetSocketAddress(4711, it) }
                "getHeader" -> map[(args[0] as CharSequence).toString().lowercase()]
                else -> error("unexpected call ${method.name}")
            }
        } as HttpServerRequest
    }

    @Test
    fun `a direct client is trusted and keeps its socket address`() {
        val ip = ClientIpResolver.resolve(request("203.0.113.9"), emptyList())

        assertEquals("203.0.113.9", ip.ip)
        assertTrue(ip.trusted)
        assertEquals("OK", ClientIpResolver.ipTrust())
    }

    @Test
    fun `a configured proxy lets the forwarded address through`() {
        val ip = ClientIpResolver.resolve(
            request("10.0.0.5", "X-Forwarded-For" to "198.51.100.7"),
            listOf("10.0.0.5")
        )

        assertEquals("198.51.100.7", ip.ip)
        assertTrue(ip.trusted)
        assertEquals("OK", ClientIpResolver.ipTrust())
    }

    @Test
    fun `the rightmost untrusted hop wins, a spoofed leftmost value is ignored`() {
        val ip = ClientIpResolver.resolve(
            request("10.0.0.5", "X-Forwarded-For" to "1.1.1.1, 198.51.100.7, 10.0.0.6"),
            listOf("10.0.0.5", "10.0.0.6")
        )

        assertEquals("198.51.100.7", ip.ip)
        assertTrue(ip.trusted)
    }

    @Test
    fun `a forwarding header from an unlisted proxy is ignored and the address is untrusted`() {
        val ip = ClientIpResolver.resolve(
            request("10.0.0.5", "X-Forwarded-For" to "198.51.100.7"),
            emptyList()
        )

        assertEquals("10.0.0.5", ip.ip, "the socket peer, never the header")
        assertFalse(ip.trusted, "every buyer would share this address")
        assertEquals("UNCONFIGURED_PROXY", ClientIpResolver.ipTrust())
    }

    @Test
    fun `the other forwarding headers count too`() {
        for (header in listOf("X-Real-IP", "Forwarded")) {
            ClientIpResolver.reset()
            ClientIpResolver.clock = clock

            val ip = ClientIpResolver.resolve(request("10.0.0.5", header to "198.51.100.7"), emptyList())

            assertFalse(ip.trusted, header)
            assertEquals("UNCONFIGURED_PROXY", ClientIpResolver.ipTrust(), header)
        }
    }

    @Test
    fun `a trusted proxy without a forwarded address resolves to the proxy itself and is trusted`() {
        val ip = ClientIpResolver.resolve(request("10.0.0.5"), listOf("10.0.0.5"))

        assertEquals("10.0.0.5", ip.ip)
        assertTrue(ip.trusted)
    }

    @Test
    fun `a loopback peer without forwarding headers is the SSR upstream, untrusted and without a health flag`() {
        for (peer in listOf("127.0.0.1", "127.8.9.10", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
            ClientIpResolver.reset()
            ClientIpResolver.clock = clock

            val ip = ClientIpResolver.resolve(request(peer), emptyList())

            assertFalse(ip.trusted, peer)
            assertEquals(peer.lowercase(), ip.ip, peer)
            assertEquals("OK", ClientIpResolver.ipTrust(), "$peer must not raise the flag")
        }
    }

    @Test
    fun `a loopback peer that forwards for someone is an unconfigured same-host proxy`() {
        val ip = ClientIpResolver.resolve(request("127.0.0.1", "X-Forwarded-For" to "198.51.100.7"), emptyList())

        assertFalse(ip.trusted)
        assertEquals("UNCONFIGURED_PROXY", ClientIpResolver.ipTrust())
    }

    @Test
    fun `a loopback proxy listed as trusted forwards the real address`() {
        val ip = ClientIpResolver.resolve(request("127.0.0.1", "X-Forwarded-For" to "198.51.100.7"), listOf("127.0.0.1"))

        assertEquals("198.51.100.7", ip.ip)
        assertTrue(ip.trusted)
        assertEquals("OK", ClientIpResolver.ipTrust())
    }

    @Test
    fun `no socket peer gives no address and is untrusted without a flag`() {
        val ip = ClientIpResolver.resolve(request(null), emptyList())

        assertNull(ip.ip)
        assertFalse(ip.trusted)
        assertEquals("OK", ClientIpResolver.ipTrust())
    }

    @Test
    fun `the unconfigured proxy flag lasts exactly one hour`() {
        ClientIpResolver.resolve(request("10.0.0.5", "X-Forwarded-For" to "198.51.100.7"), emptyList())
        assertEquals("UNCONFIGURED_PROXY", ClientIpResolver.ipTrust())

        clock.advance(ClientIpResolver.UNCONFIGURED_PROXY_WINDOW_MS - 1)
        assertEquals("UNCONFIGURED_PROXY", ClientIpResolver.ipTrust())

        clock.advance(1)
        assertEquals("OK", ClientIpResolver.ipTrust())
    }

    @Test
    fun `another unconfigured request renews the flag`() {
        ClientIpResolver.resolve(request("10.0.0.5", "X-Forwarded-For" to "1.2.3.4"), emptyList())
        clock.advance(ClientIpResolver.UNCONFIGURED_PROXY_WINDOW_MS - 10)
        ClientIpResolver.resolve(request("10.0.0.5", "X-Forwarded-For" to "1.2.3.4"), emptyList())
        clock.advance(ClientIpResolver.UNCONFIGURED_PROXY_WINDOW_MS - 10)

        assertEquals("UNCONFIGURED_PROXY", ClientIpResolver.ipTrust())
    }

    @Test
    fun `loopback detection is literal and never resolves a name`() {
        assertTrue(ClientIpResolver.isLoopback("127.0.0.1"))
        assertTrue(ClientIpResolver.isLoopback("127.255.255.255"))
        assertTrue(ClientIpResolver.isLoopback("::1"))
        assertTrue(ClientIpResolver.isLoopback("::FFFF:127.0.0.2"))
        assertFalse(ClientIpResolver.isLoopback("128.0.0.1"))
        assertFalse(ClientIpResolver.isLoopback("10.0.0.1"))
        assertFalse(ClientIpResolver.isLoopback("localhost"))
        assertFalse(ClientIpResolver.isLoopback("::2"))
        assertFalse(ClientIpResolver.isLoopback("::ffff:10.0.0.1"))
        assertFalse(ClientIpResolver.isLoopback("1270.0.0.1"))
        assertFalse(ClientIpResolver.isLoopback(""))
    }
}
