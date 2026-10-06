package com.panomc.plugins.market.routes.base

import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

@Suppress("UNCHECKED_CAST")
private fun <T> proxy(type: Class<T>, answer: (String, Array<out Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> answer(method.name, args) } as T

/** [exceptReads]: a GET goes straight to the next handler, every write reaches the validator (the panel's GET carries a JSON content type and no body). */
class WriteOnlyValidationTest {
    private class Probe(val method: HttpMethod) {
        var nexted = 0

        val context: RoutingContext = proxy(RoutingContext::class.java) { name, _ ->
            when (name) {
                "request" -> proxy(io.vertx.core.http.HttpServerRequest::class.java) { n, _ -> if (n == "method") method else null }
                "next" -> { nexted++; null }
                "fail" -> null
                else -> null
            }
        }
    }

    private class Counting : ValidationHandler {
        var calls = 0
        override fun handle(context: RoutingContext) { calls++ }
    }

    @Test
    fun `a GET skips the validator and continues`() {
        val inner = Counting()
        val probe = Probe(HttpMethod.GET)

        inner.exceptReads().handle(probe.context)

        assertEquals(0, inner.calls, "the body validator is not asked about a read")
        assertEquals(1, probe.nexted, "the request goes on to the endpoint")
    }

    @Test
    fun `every write reaches the validator and is not forwarded by the wrapper`() {
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            val inner = Counting()
            val probe = Probe(method)

            inner.exceptReads().handle(probe.context)

            assertEquals(1, inner.calls, "$method is validated")
            assertEquals(0, probe.nexted, "$method: only the validator decides whether the request continues")
        }
    }
}
