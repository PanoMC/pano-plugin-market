package com.panomc.plugins.market.core.provider

import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.provider.ExchangeSink
import com.panomc.plugins.market.provider.ProviderLogImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.helpers.MessageFormatter

/** The log a provider gets redacts messages, exception texts and recorded exchanges (02 section 3, 11 section 8.4). */
class ProviderLogTest {
    private class Recorded(val providerId: String, val channel: String, val request: String?, val response: String?, val status: Int?, val ms: Long)

    private val lines = ArrayList<String>()

    private val capturing = object : org.slf4j.helpers.AbstractLogger() {
        override fun getFullyQualifiedCallerName(): String? = null
        override fun handleNormalizedLoggingCall(level: org.slf4j.event.Level, marker: org.slf4j.Marker?, messagePattern: String?, arguments: Array<Any?>?, throwable: Throwable?) {
            lines.add(MessageFormatter.arrayFormat(messagePattern, arguments).message ?: "")
        }
        override fun isTraceEnabled() = true
        override fun isTraceEnabled(marker: org.slf4j.Marker?) = true
        override fun isDebugEnabled() = true
        override fun isDebugEnabled(marker: org.slf4j.Marker?) = true
        override fun isInfoEnabled() = true
        override fun isInfoEnabled(marker: org.slf4j.Marker?) = true
        override fun isWarnEnabled() = true
        override fun isWarnEnabled(marker: org.slf4j.Marker?) = true
        override fun isErrorEnabled() = true
        override fun isErrorEnabled(marker: org.slf4j.Marker?) = true
    }

    private val secret = "sk_live_4eC39HqLyjWDarjtT1zdp7dc"
    private val recorded = ArrayList<Recorded>()
    private val log = ProviderLogImpl("stripe", Redactor(setOf(secret)), { p, c, rq, rs, st, ms -> recorded.add(Recorded(p, c, rq, rs, st, ms)) }, capturing)

    @Test
    fun `messages are redacted and carry the provider id`() {
        log.info("using key $secret for card 4111111111111111")
        log.warn("retry $secret")
        log.error("failed $secret")
        assertEquals(3, lines.size)
        for (l in lines) {
            assertFalse(l.contains(secret), l)
            assertTrue(l.startsWith("[stripe] "), l)
        }
        assertEquals("[stripe] using key [REDACTED] for card ************1111", lines[0])
    }

    @Test
    fun `an exception is logged by class and redacted message with its causes, never as a stack trace`() {
        val cause = IllegalStateException("inner $secret")
        log.warn("call failed", RuntimeException("outer $secret", cause))
        assertEquals(1, lines.size)
        val line = lines[0]
        assertFalse(line.contains(secret), line)
        assertTrue(line.contains("java.lang.RuntimeException: outer [REDACTED]"), line)
        assertTrue(line.contains("java.lang.IllegalStateException: inner [REDACTED]"), line)
        assertFalse(line.contains("\tat "), "no stack trace")
    }

    @Test
    fun `a recorded exchange reaches the sink redacted`() {
        log.exchange("charge", """{"key":"$secret","cvv":"123"}""", "Bearer $secret ok", 200, 42)
        assertEquals(1, recorded.size)
        val r = recorded[0]
        assertEquals("stripe", r.providerId)
        assertEquals("charge", r.channel)
        assertEquals("""{"key":"[REDACTED]","cvv":"[REDACTED]"}""", r.request)
        assertEquals("Bearer [REDACTED] ok", r.response)
        assertEquals(200, r.status)
        assertEquals(42L, r.ms)
        log.exchange("ping", null, null, null, 1)
        assertEquals(null, recorded[1].request)
        assertEquals(null, recorded[1].status)
    }

    @Test
    fun `the default sink and logger accept calls without failing`() {
        val quiet = ProviderLogImpl("p1", Redactor.NONE)
        quiet.exchange("x", "a", "b", 1, 1)
        quiet.info("hello")
        ExchangeSink.NONE.record("p1", "x", null, null, null, 0)
    }
}
