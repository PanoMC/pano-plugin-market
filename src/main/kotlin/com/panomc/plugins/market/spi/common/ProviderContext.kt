package com.panomc.plugins.market.spi.common

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient

/** Decrypted settings of one provider as market stores them. */
interface ProviderSettings {
    /** Trimmed; null when blank or absent. */
    fun string(key: String): String?

    /** Throws [ProviderException] ([ProviderErrorCode.CONFIGURATION]) when blank or absent. */
    fun require(key: String): String

    fun boolean(key: String, default: Boolean = false): Boolean

    fun long(key: String): Long?

    /** Decrypted copy. */
    fun asJson(): JsonObject
}

/**
 * Durable, encrypted key/value store scoped to one provider (`market_provider_state`).
 * Convention: a key holding per-buyer data starts with `user:<userId>:` so market can delete it with the user.
 */
interface ProviderStateStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String, ttlSeconds: Long? = null)

    suspend fun remove(key: String)

    suspend fun compareAndSet(key: String, expected: String?, value: String, ttlSeconds: Long? = null): Boolean
}

class SiteInfo(
    val name: String,
    val baseUrl: String,
    val https: Boolean,
    /** False for localhost / private hosts: omit webhook URLs the gateway would reject. */
    val publiclyReachable: Boolean,
    val defaultLocale: String
)

/** Values of secret settings are redacted automatically. */
interface ProviderLog {
    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    fun error(message: String, error: Throwable? = null)

    /** Records an outbound call in `market_payment_event` (direction OUT). Never pass card data. */
    fun exchange(channel: String, request: String?, response: String?, status: Int?, durationMs: Long)
}

interface ProviderContext {
    val providerId: String

    val settings: ProviderSettings

    /** Method test mode or the store-wide test mode. */
    val testMode: Boolean

    /** Host client; use per-request timeouts (default 15 s). */
    val http: WebClient
    val vertx: Vertx
    val log: ProviderLog
    val state: ProviderStateStore
    val site: SiteInfo

    /** Epoch ms from market's clock. */
    fun now(): Long
}

class ProviderException(
    val code: ProviderErrorCode,
    /** Safe to log. */
    message: String,
    /** The gateway's own text, shown only in the panel. */
    val adminMessage: String? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** New values only at the end; both sides treat an unknown value as `INTERNAL`. */
enum class ProviderErrorCode {
    CONFIGURATION, AUTHENTICATION, IP_NOT_ALLOWED, GATEWAY_UNREACHABLE, GATEWAY_REJECTED, RATE_LIMITED,
    INVALID_REQUEST, UNSUPPORTED, NOT_FOUND, INTERNAL
}
