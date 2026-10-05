package com.panomc.plugins.market.provider

import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.spi.common.ProviderContext
import com.panomc.plugins.market.spi.common.ProviderLog
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.ProviderStateStore
import com.panomc.plugins.market.spi.common.SiteInfo
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Receives the outbound calls a provider reports through [ProviderLog.exchange] (market stores them as `market_payment_event` OUT rows). */
fun interface ExchangeSink {
    /** Text arrives already redacted. Must not block. */
    fun record(providerId: String, channel: String, request: String?, response: String?, status: Int?, durationMs: Long)

    companion object {
        val NONE = ExchangeSink { _, _, _, _, _, _ -> }
    }
}

/**
 * The log a provider gets (02 section 3): every message, exception text and recorded exchange passes through the
 * provider's [Redactor] first, so the values of its secret settings never reach a log file or the database.
 * Exceptions are logged by class and redacted message only: a stack trace would carry the unredacted message.
 */
class ProviderLogImpl(
    private val providerId: String,
    private val redactor: Redactor,
    private val sink: ExchangeSink = ExchangeSink.NONE,
    private val logger: Logger = LoggerFactory.getLogger("com.panomc.plugins.market.provider.$providerId")
) : ProviderLog {
    override fun info(message: String) {
        logger.info("[{}] {}", providerId, redactor.redact(message))
    }

    override fun warn(message: String, error: Throwable?) {
        logger.warn("[{}] {}{}", providerId, redactor.redact(message), describe(error))
    }

    override fun error(message: String, error: Throwable?) {
        logger.error("[{}] {}{}", providerId, redactor.redact(message), describe(error))
    }

    override fun exchange(channel: String, request: String?, response: String?, status: Int?, durationMs: Long) {
        sink.record(providerId, channel, redactor.redactOrNull(request), redactor.redactOrNull(response), status, durationMs)
    }

    private fun describe(error: Throwable?): String {
        if (error == null) return ""
        val parts = ArrayList<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 5) {
            parts.add(current.javaClass.name + (current.message?.let { ": " + redactor.redact(it) } ?: ""))
            current = current.cause
        }
        return " (" + parts.joinToString(" <- ") + ")"
    }
}

/**
 * The [ProviderContext] market hands to a provider: decrypted settings, the shared Vert.x client, the redacting log,
 * the provider's own state store and market's clock. Payment and shipping contexts extend it with their URL and
 * lookup parts.
 */
open class ProviderContextImpl(
    override val providerId: String,
    override val settings: ProviderSettings,
    override val testMode: Boolean,
    override val http: WebClient,
    override val vertx: Vertx,
    override val log: ProviderLog,
    override val state: ProviderStateStore,
    override val site: SiteInfo,
    private val clock: Clock
) : ProviderContext {
    override fun now(): Long = clock.now()
}
