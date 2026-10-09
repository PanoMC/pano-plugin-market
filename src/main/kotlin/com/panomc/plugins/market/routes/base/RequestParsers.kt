package com.panomc.plugins.market.routes.base

import com.panomc.plugins.market.error.RequestValueException
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

// Pure request parsing shared by the routes (17 section 2: a route's handle parses into a value, calls a service and
// maps the response; the parsing is a top-level function tested in T0). Every function throws
// RequestValueException for a value outside the contract; the route base classes answer that with 400 BAD_REQUEST.

private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{16,64}$")
private val DIGITS = Regex("^[0-9]{1,18}$")

/**
 * The `Idempotency-Key` header (04 section 1): `^[A-Za-z0-9_-]{16,64}$`. Absent: `null` unless [required]. A
 * present header that does not match is always refused, also where the key is optional.
 */
fun parseIdempotencyKey(header: String?, required: Boolean): String? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() }

    if (value == null) {
        if (required) throw RequestValueException("Idempotency-Key", "REQUIRED")

        return null
    }

    if (!IDEMPOTENCY_KEY.matches(value)) throw RequestValueException("Idempotency-Key", "INVALID")

    return value
}

/** A path / query id: a plain positive integer. `1.5`, `-1`, `0`, `1e3`, `abc` and blanks are refused (never a 500). */
fun parseId(raw: String?, name: String = "id"): Long {
    val value = raw?.trim().orEmpty()

    if (!DIGITS.matches(value)) throw RequestValueException(name, "MUST_BE_AN_INTEGER_ID")

    val id = value.toLong()

    if (id < 1) throw RequestValueException(name, "MUST_BE_AN_INTEGER_ID")

    return id
}

/** The `exchangeRate` of `PUT /orders/:id/exchange-rate` (money-critical): a finite number above zero; `null`, `0`, a negative, `NaN` and infinity are refused. */
fun parseExchangeRate(value: Double?): Double {
    if (value == null || !value.isFinite() || value <= 0.0) throw RequestValueException("exchangeRate", "MUST_BE_POSITIVE")

    return value
}

/** An id from a JSON body: an integral number >= 1 (`1.5` is refused, a string is refused). */
fun parseBodyId(raw: Any?, name: String): Long {
    val id = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Short -> raw.toLong()
        is Byte -> raw.toLong()
        is Double -> if (raw == Math.floor(raw) && !raw.isInfinite() && Math.abs(raw) < 9.0E15) raw.toLong() else null
        is Float -> if (raw == Math.floor(raw.toDouble()).toFloat() && !raw.isInfinite()) raw.toLong() else null
        else -> null
    }

    if (id == null || id < 1) throw RequestValueException(name, "MUST_BE_AN_INTEGER_ID")

    return id
}

/** `ids*[]` of a sort request: [name] must be an array of positive integer ids, 1..[max] entries, no duplicates. */
fun parseIdList(array: JsonArray?, name: String, max: Int = 1000): List<Long> {
    if (array == null || array.isEmpty) throw RequestValueException(name, "REQUIRED")
    if (array.size() > max) throw RequestValueException(name, "TOO_MANY")

    val ids = array.list.map { parseBodyId(it, name) }

    if (ids.toSet().size != ids.size) throw RequestValueException(name, "DUPLICATE")

    return ids
}

/**
 * An enum from its exact name. Absent: [default], or a refusal when there is none. An unknown name is refused (the
 * old routes silently fell back to a default, which hid typos).
 */
fun <E : Enum<E>> parseEnum(values: Array<E>, raw: String?, name: String, default: E? = null): E {
    if (raw == null) return default ?: throw RequestValueException(name, "REQUIRED")

    return values.firstOrNull { it.name == raw } ?: throw RequestValueException(name, "UNKNOWN_VALUE")
}

/** [parseEnum] for an optional filter: absent = `null`, unknown = refused. */
fun <E : Enum<E>> parseOptionalEnum(values: Array<E>, raw: String?, name: String): E? =
    if (raw == null) null else parseEnum(values, raw, name)

/** A string with a length range after trimming; `null` stays `null` unless [required]. */
fun parseText(raw: String?, name: String, required: Boolean = false, maxLength: Int = 255): String? {
    val value = raw?.trim()

    if (value.isNullOrEmpty()) {
        if (required) throw RequestValueException(name, "REQUIRED")

        return null
    }

    if (value.length > maxLength) throw RequestValueException(name, "TOO_LONG")

    return value
}

/** Rejects a JSON body carrying a key outside [allowed] (the `additionalProperties:false` rule of settings bodies). */
fun rejectUnknownKeys(body: JsonObject, allowed: Set<String>) {
    val unknown = body.fieldNames().firstOrNull { it !in allowed }

    if (unknown != null) throw RequestValueException(unknown, "UNKNOWN_PROPERTY")
}
