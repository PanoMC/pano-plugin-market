package com.panomc.plugins.market.routes.panel.credit

import com.panomc.plugins.market.config.ConfigScope
import com.panomc.plugins.market.config.MarketConfigKeys
import com.panomc.plugins.market.config.SettingsRequest
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.error.InvalidCreditAmount
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.parseEnum
import com.panomc.plugins.market.routes.base.parseIdempotencyKey
import com.panomc.plugins.market.routes.base.parseText
import io.vertx.core.json.JsonObject
import java.math.BigDecimal

// Pure request parsing of the panel credit routes (07 sections 11.1 and 14.2), so every rule is tested without the host.

/** Largest amount of one manual movement and largest credit value / top-up bound of the settings (07 sections 11.1 and 14.2). */
const val MAX_CREDITS = 1_000_000L

/** A parsed grant or revoke: [credits] is the amount x 100, [idempotencyKey] the header (the ledger key is `panel:<key>`). */
class CreditMoveRequest(val type: CreditTxType, val credits: Long, val note: String, val idempotencyKey: String)

/**
 * The body of `POST /credits/accounts/:userId/grant|revoke` with its `Idempotency-Key` header, in the order of 07 section 11.1: header (400 `BAD_REQUEST`),
 * `amount` (400 `INVALID_CREDIT_AMOUNT`: a number, `0.01` to 1 000 000, at most two decimals), `note` (trimmed 3 to 255 characters, 400 `BAD_REQUEST`).
 */
fun parseCreditMove(type: CreditTxType, header: String?, body: JsonObject): CreditMoveRequest {
    require(type == CreditTxType.GRANT || type == CreditTxType.REVOKE)

    val key = parseIdempotencyKey(header, required = true)!!
    val credits = parseCreditAmount(body.getValue("amount"))
    val note = parseText(body.getValue("note") as? String, "note", required = true, maxLength = 255)!!

    if (note.length < 3) throw RequestValueException("note", "TOO_SHORT")

    return CreditMoveRequest(type, credits, note, key)
}

/** [raw] as credits x 100; anything that is not a finite number in range with at most two decimals is `INVALID_CREDIT_AMOUNT`. */
fun parseCreditAmount(raw: Any?): Long {
    val number = when (raw) {
        is Int, is Long, is Short, is Byte -> BigDecimal(raw.toString())
        is Double -> if (raw.isFinite()) BigDecimal(raw.toString()) else null
        is Float -> if (raw.isFinite()) BigDecimal(raw.toString()) else null
        is BigDecimal -> raw
        else -> null
    } ?: throw InvalidCreditAmount("NOT_A_NUMBER", 0.01, MAX_CREDITS)

    if (number.signum() <= 0) throw InvalidCreditAmount("MIN", 0.01, MAX_CREDITS)
    if (number > BigDecimal(MAX_CREDITS)) throw InvalidCreditAmount("MAX", 0.01, MAX_CREDITS)
    if (number.stripTrailingZeros().scale() > 2) throw InvalidCreditAmount("PRECISION", 0.01, MAX_CREDITS)

    return number.movePointRight(2).longValueExact()
}

/** The filters of `GET /credits/transactions`; `type` is a comma separated list of ledger types. */
class CreditTxFilter(val types: List<CreditTxType>, val userId: Long?, val orderId: Long?, val from: Long?, val to: Long?)

fun parseCreditTxFilter(type: String?, userId: String?, orderId: String?, from: String?, to: String?): CreditTxFilter {
    val types = type?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { parseEnum(CreditTxType.entries.toTypedArray(), it, "type") }?.distinct().orEmpty()

    return CreditTxFilter(
        types, userId?.let { com.panomc.plugins.market.routes.base.parseId(it, "userId") }, orderId?.let { com.panomc.plugins.market.routes.base.parseId(it, "orderId") },
        from?.let { parseEpoch(it, "from") }, to?.let { parseEpoch(it, "to") }
    )
}

private fun parseEpoch(raw: String, name: String): Long =
    raw.trim().takeIf { it.matches(Regex("^[0-9]{1,15}$")) }?.toLong() ?: throw RequestValueException(name, "MUST_BE_A_TIMESTAMP")

/**
 * `POST /settings/credits` (07 section 14.2): the key table of [MarketConfigKeys] plus the bounds the table does not carry: `creditValue`, `creditTopUpMin` and
 * `creditTopUpMax` at most 1 000 000, and at most two decimals on those and on `cashbackPercent`. Throws 400 `INVALID_SETTINGS` `{fieldErrors}` (nothing is
 * applied when any field is invalid) and returns the merged config to save.
 */
fun applyCreditSettings(body: JsonObject, current: JsonObject): JsonObject {
    val errors = LinkedHashMap(MarketConfigKeys.validate(body, current, ConfigScope.CREDIT))

    for (name in listOf("creditValue", "creditTopUpMin", "creditTopUpMax")) {
        val value = (body.getValue(name) as? Number)?.toDouble() ?: continue

        if (name !in errors && value > MAX_CREDITS) errors[name] = MarketConfigKeys.OUT_OF_RANGE
    }

    for (name in listOf("creditValue", "creditTopUpMin", "creditTopUpMax", "cashbackPercent")) {
        val value = body.getValue(name) as? Number ?: continue

        if (name !in errors && value.toDouble().isFinite() && BigDecimal(value.toString()).stripTrailingZeros().scale() > 2) errors[name] = MarketConfigKeys.INVALID_VALUE
    }

    if (errors.isNotEmpty()) throw InvalidSettings(errors)

    return SettingsRequest.apply(body, current, ConfigScope.CREDIT)
}
