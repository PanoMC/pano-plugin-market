package com.panomc.plugins.market.routes.base

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.error.StoreBusy
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.runtime.MarketRuntime
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** The decisions of the route base classes (00 sections 8.8 and 8.9, 04 section 1) without a host. */
class MarketGateTest {
    @AfterEach
    fun restore() {
        MarketGate.storeEnabled = { true }
    }

    @Test
    fun `only READY lets a request through`() {
        MarketGate.requireReady(MarketRuntime.State.READY)

        for (state in listOf(MarketRuntime.State.STOPPED, MarketRuntime.State.STARTING, MarketRuntime.State.DEGRADED)) {
            val e = assertThrows(StoreUnavailable::class.java) { MarketGate.requireReady(state) }
            assertEquals(503, e.getStatusCode())
            assertEquals("STORE_UNAVAILABLE", e.getErrorCode())
        }
    }

    @Test
    fun `the gate reads the runtime state by default`() {
        MarketRuntime.reset()
        assertThrows(StoreUnavailable::class.java) { MarketGate.requireReady() }

        MarketRuntime.starting()
        MarketRuntime.finish(null, emptyList(), degraded = false)
        MarketGate.requireReady()

        MarketRuntime.stopped()
        assertThrows(StoreUnavailable::class.java) { MarketGate.requireReady() }
        MarketRuntime.reset()
    }

    @Test
    fun `the store switch answers STORE_DISABLED and defaults to on`() {
        MarketGate.requireStoreEnabled()

        MarketGate.storeEnabled = { false }
        val e = assertThrows(StoreDisabled::class.java) { MarketGate.requireStoreEnabled() }
        assertEquals(503, e.getStatusCode())
        assertEquals("STORE_DISABLED", e.getErrorCode())

        MarketGate.storeEnabled = { true }
        MarketGate.requireStoreEnabled()
        assertThrows(StoreDisabled::class.java) { MarketGate.requireStoreEnabled(false) }
    }

    @Test
    fun `csrf is required for an authenticated mutating call without proof and nowhere else`() {
        val mutating = listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)
        val safe = listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)

        for (m in mutating) {
            assertTrue(MarketGate.csrfViolation(m, isLoggedIn = true, csrfSafe = false), "$m logged in without proof")
            assertFalse(MarketGate.csrfViolation(m, isLoggedIn = true, csrfSafe = true), "$m with proof")
            assertFalse(MarketGate.csrfViolation(m, isLoggedIn = false, csrfSafe = false), "$m as a guest has no ambient credential")
        }

        for (m in safe) {
            assertFalse(MarketGate.csrfViolation(m, isLoggedIn = true, csrfSafe = false), "$m never changes state")
            assertTrue(MarketGate.isSafeMethod(m))
        }

        mutating.forEach { assertFalse(MarketGate.isSafeMethod(it)) }
    }

    // ---- translating failures

    private class Recorder {
        val headers = mutableMapOf<String, String>()
    }

    /** A RoutingContext whose response only records putHeader; nothing else of it is touched. */
    private fun fakeContext(recorder: Recorder): RoutingContext {
        val response = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(io.vertx.core.http.HttpServerResponse::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putHeader" -> {
                    recorder.headers[args[0].toString()] = args[1].toString()
                    proxy
                }

                else -> error("unexpected call ${method.name}")
            }
        } as io.vertx.core.http.HttpServerResponse

        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RoutingContext::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "response" -> response
                else -> error("unexpected call ${method.name}")
            }
        } as RoutingContext
    }

    @Test
    fun `a database that stayed busy becomes STORE_BUSY with Retry-After 2`() {
        val recorder = Recorder()
        val translated = MarketGate.translate(MarketBusyException(3, null), fakeContext(recorder))

        assertTrue(translated is StoreBusy)
        assertEquals(503, (translated as StoreBusy).getStatusCode())
        assertEquals("2", recorder.headers["Retry-After"])
    }

    @Test
    fun `a refused request value becomes BAD_REQUEST with the field in bodyValidationError`() {
        val recorder = Recorder()
        val translated = MarketGate.translate(RequestValueException("pageSize", "TOO_BIG"), fakeContext(recorder))

        assertTrue(translated is BadRequest)
        val body = JsonObject((translated as BadRequest).encode())
        assertEquals("BAD_REQUEST", body.getString("error"))
        assertEquals("pageSize: TOO_BIG", body.getString("bodyValidationError"))
        assertTrue(recorder.headers.isEmpty())
    }

    @Test
    fun `any other failure passes through unchanged`() {
        val failure = IllegalStateException("boom")

        assertSame(failure, MarketGate.translate(failure, fakeContext(Recorder())))
    }

    @Test
    fun `guarded translates what the block throws and returns what it returns`() = runBlocking {
        val recorder = Recorder()
        val context = fakeContext(recorder)

        assertEquals("ok", MarketGate.guarded(context) { "ok" })

        val busy = runCatching { MarketGate.guarded<String>(context) { throw MarketBusyException(2, null) } }.exceptionOrNull()
        assertTrue(busy is StoreBusy)
        assertEquals("2", recorder.headers["Retry-After"])

        val bad = runCatching { MarketGate.guarded<String>(context) { throw RequestValueException("page", "X") } }.exceptionOrNull()
        assertTrue(bad is BadRequest)

        val other = runCatching { MarketGate.guarded<String>(context) { throw IllegalStateException("x") } }.exceptionOrNull()
        assertTrue(other is IllegalStateException)
    }

    // ---- route classification

    private class PanelRoute(override val nodes: Set<MarketNode>) : MarketPanelApi() {
        override val paths = emptyList<com.panomc.platform.model.Path>()
        override suspend fun handleAuthorized(context: RoutingContext): com.panomc.platform.model.Result? = null
        override fun getValidationHandler(schemaRepository: io.vertx.json.schema.SchemaRepository) = null
    }

    private class PublicRoute : MarketApi() {
        override val paths = emptyList<com.panomc.platform.model.Path>()
        override suspend fun handleMarket(context: RoutingContext): com.panomc.platform.model.Result? = null
        override fun getValidationHandler(schemaRepository: io.vertx.json.schema.SchemaRepository) = null
    }

    private class MutationRoute : MarketPublicMutationApi() {
        override val paths = emptyList<com.panomc.platform.model.Path>()
        override suspend fun handleMarket(context: RoutingContext): com.panomc.platform.model.Result? = null
        override fun getValidationHandler(schemaRepository: io.vertx.json.schema.SchemaRepository) = null
    }

    private class UserRoute : MarketUserApi() {
        override val paths = emptyList<com.panomc.platform.model.Path>()
        override suspend fun handleMarket(context: RoutingContext): com.panomc.platform.model.Result? = null
        override fun getValidationHandler(schemaRepository: io.vertx.json.schema.SchemaRepository) = null
    }

    @Test
    fun `route auth names follow 04 section 1`() {
        assertEquals("PUB", RouteAuth.describe(PublicRoute()))
        assertEquals("PUB-M", RouteAuth.describe(MutationRoute()))
        assertEquals("USER", RouteAuth.describe(UserRoute()))
        assertEquals("P:ANY", RouteAuth.describe(PanelRoute(emptySet())))
        assertEquals("P:SET", RouteAuth.describe(PanelRoute(setOf(MarketNode.SETTINGS))))
        assertEquals("P:CAT,PAY", RouteAuth.describe(PanelRoute(setOf(MarketNode.PAYMENTS, MarketNode.CATALOG))))
        assertEquals("UNKNOWN", RouteAuth.describe("not a route"))
    }
}
