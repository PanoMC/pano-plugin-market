package com.panomc.plugins.market.service

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.util.TrustedProxyIpResolver
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.SystemClock
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext

/**
 * [ip] is the address market counts, limits and blocks on; `null` when there is no socket peer. [trusted] `false`
 * means the IP dimension is skipped (no IP-keyed limiter, no IP block match, `clientIp` columns stored `NULL`),
 * 11 section 2.
 */
data class ClientIp(val ip: String?, val trusted: Boolean)

/**
 * The only way market learns a client IP (11 section 2). Never `AuthProvider.getRemoteIP` or
 * `RateLimitManager.getClientIp` in market code.
 *
 * - the socket peer wins; a forwarded header is honoured only from a peer listed in `server.trusted-proxies`
 *   (the platform's [TrustedProxyIpResolver]);
 * - a forwarding header that was not honoured means a reverse proxy nobody configured: every buyer would share one
 *   address, so the health flag `UNCONFIGURED_PROXY` is raised for an hour and, when the socket peer is a loopback /
 *   private / link-local address (a proxy on the same host or LAN), `trusted = false`. From a public socket peer the
 *   header is just ignored (`trusted = true`, the socket address counts): otherwise any client could skip the IP
 *   limiter and its IP block by sending one header;
 * - a loopback socket peer without a forwarding header is the theme / panel SSR upstream, not a buyer:
 *   `trusted = false` but no health flag (rule 2a).
 */
object ClientIpResolver {
    const val UNCONFIGURED_PROXY_WINDOW_MS = 60L * 60L * 1000L

    /** Test seam; production reads the system clock through [SystemClock]. */
    @Volatile
    internal var clock: Clock = SystemClock

    @Volatile
    private var lastUnconfiguredProxyAt: Long? = null

    fun resolve(context: RoutingContext): ClientIp {
        val trustedProxies = applicationContext.getBean(ConfigManager::class.java).config.server.trustedProxies

        return resolve(context.request(), trustedProxies)
    }

    fun resolve(request: HttpServerRequest, trustedProxies: List<String>): ClientIp {
        val resolved = TrustedProxyIpResolver.resolve(request, trustedProxies) ?: return ClientIp(null, false)

        val forwardingHeader = TrustedProxyIpResolver.hasForwardingHeader(request)
        val unconfiguredProxy = !resolved.fromForwardedHeader && forwardingHeader

        if (unconfiguredProxy) {
            lastUnconfiguredProxyAt = clock.now()

            // Only a same-host or LAN peer can plausibly be a reverse proxy nobody configured. A public socket peer
            // is a direct client: its forwarding header is ignored and it stays keyed (and blockable) on its socket
            // address, so a header can never switch the IP dimension off (review fix, deviates from 11 section 2).
            return ClientIp(resolved.ip, trusted = !isPrivateOrLoopback(resolved.ip))
        }

        if (!resolved.fromForwardedHeader && isLoopback(resolved.ip)) {
            return ClientIp(resolved.ip, trusted = false)
        }

        return ClientIp(resolved.ip, trusted = true)
    }

    /** `health.ipTrust`: `UNCONFIGURED_PROXY` when a request in the last hour came through an unconfigured proxy. */
    fun ipTrust(): String {
        val last = lastUnconfiguredProxyAt ?: return "OK"

        return if (clock.now() - last in 0 until UNCONFIGURED_PROXY_WINDOW_MS) "UNCONFIGURED_PROXY" else "OK"
    }

    /** `127.0.0.0/8`, `::1` and their IPv4-mapped forms. Literal addresses only: nothing is resolved. */
    fun isLoopback(ip: String): Boolean {
        val value = ip.trim().lowercase()

        if (IPV4.matches(value)) return value.startsWith("127.")

        if (value == "::1" || value == "0:0:0:0:0:0:0:1") return true

        val mapped = value.removePrefix("::ffff:").takeIf { it != value }

        return mapped != null && IPV4.matches(mapped) && mapped.startsWith("127.")
    }

    /**
     * Loopback, RFC1918, `169.254/16`, `fc00::/7`, `fe80::/10` (and the IPv4-mapped forms). Literal addresses only:
     * anything that is not a literal is treated as public and nothing is ever resolved.
     */
    fun isPrivateOrLoopback(ip: String): Boolean {
        val value = ip.trim().substringBefore('%').lowercase()

        val literal = IPV4.matches(value) || (value.contains(':') && value.all { it in "0123456789abcdef:." })

        if (!literal) return false

        val address = try {
            java.net.InetAddress.getByName(value)
        } catch (_: Exception) {
            return false
        }

        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true

        return address is java.net.Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
    }

    internal fun reset() {
        lastUnconfiguredProxyAt = null
        clock = SystemClock
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
