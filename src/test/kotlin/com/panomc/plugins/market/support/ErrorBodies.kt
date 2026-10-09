package com.panomc.plugins.market.support

import com.panomc.platform.model.Error
import io.vertx.core.json.JsonObject

/**
 * Reads the wire body of a platform [Error] (04 section 3): `{ "error": { code, message?, details?, fields? } }`. The extras an
 * error class carries (`lines`, `retryAfter`, `fieldErrors`, ...) are the keys of `error.details`.
 */
object ErrorBodies {
    fun envelope(failure: Throwable): JsonObject = JsonObject((failure as Error).encode(emptyMap())).getJsonObject("error")

    fun code(failure: Throwable): String = envelope(failure).getString("code")

    /** The extras of the error: `error.details`, empty when there are none. */
    fun details(failure: Throwable): JsonObject = envelope(failure).getJsonObject("details") ?: JsonObject()
}
