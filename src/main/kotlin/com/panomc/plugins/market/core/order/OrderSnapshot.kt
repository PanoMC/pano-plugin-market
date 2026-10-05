package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.config.BillingInfoMode
import com.panomc.plugins.market.core.shipping.Countries
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale

/*
 * The immutable records an order keeps of what the buyer saw and accepted (MK-075; 01 sections 5.1 and 5.2, 06 sections 5.1
 * and 8): the product as sold, the validated billing info, and the canonical request fingerprint behind the idempotency
 * key. Pure: no database, no clock.
 */

/**
 * The product facts that go into `market_order_item.snapshot` (01 section 5.2). [weightGrams] is the effective unit weight
 * (the variant's override already applied), [providerMeta] is `{providerId: {...}}` (product level, then the variant's
 * keys on top), [variantAttributes] the variant's `{key: value}` object.
 */
class SnapshotProduct(
    val slug: String,
    val imageFileName: String?,
    val kind: String,
    val billingMode: String,
    val periodUnit: String?,
    val periodCount: Int?,
    val physical: Boolean,
    val weightGrams: Int?,
    val lengthMm: Int?,
    val widthMm: Int?,
    val heightMm: Int?,
    val hsCode: String?,
    val originCountry: String?,
    val tierCategoryId: Long?,
    val tierRank: Int?,
    /** The product's `actions` JSON text (an array), copied as it was sold. */
    val actions: String?,
    val variantAttributes: String?,
    val providerMeta: JsonObject = JsonObject()
)

/**
 * "The product as sold": deliveries, fulfilment and customs data are built from this, never from the live product
 * (01 section 5.2). Every key is always present (`null` when the product has no value), `actions`, `variantAttributes` and
 * `providerMeta` are JSON values, not JSON text inside JSON.
 */
object ItemSnapshot {
    fun of(p: SnapshotProduct): JsonObject = JsonObject()
        .put("slug", p.slug)
        .put("imageFileName", p.imageFileName)
        .put("kind", p.kind)
        .put("billingMode", p.billingMode)
        .put("periodUnit", p.periodUnit)
        .put("periodCount", p.periodCount)
        .put("physical", p.physical)
        .put("weightGrams", p.weightGrams)
        .put("lengthMm", p.lengthMm)
        .put("widthMm", p.widthMm)
        .put("heightMm", p.heightMm)
        .put("hsCode", p.hsCode)
        .put("originCountry", p.originCountry)
        .put("tierCategoryId", p.tierCategoryId)
        .put("tierRank", p.tierRank)
        .put("actions", jsonArrayOrEmpty(p.actions))
        .put("variantAttributes", jsonObjectOrNull(p.variantAttributes))
        .put("providerMeta", p.providerMeta)

    /** The synthetic line of a free-amount credit top-up (07 section 8.2): `{kind: "CREDIT_TOPUP", actions: []}`. */
    fun topUp(): JsonObject = JsonObject().put("kind", "CREDIT_TOPUP").put("actions", JsonArray())

    private fun jsonArrayOrEmpty(raw: String?): JsonArray =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { JsonArray(it) }.getOrNull() } ?: JsonArray()

    private fun jsonObjectOrNull(raw: String?): JsonObject? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() }
}

/**
 * Billing info of a checkout (06 section 8.2): trims and cleans every value, validates it, keeps only the validated keys and
 * reports every offending path. Which paths are mandatory comes from the caller ([RequiredBuyerFields.of]).
 *
 * Mode `OFF` stores nothing except the paths a provider requires (a `billingInfo` object in the body is discarded
 * otherwise); `OPTIONAL` validates and stores what was sent; `REQUIRED` additionally needs the paths of the table.
 */
object BillingSnapshot {
    const val PREFIX = "billingInfo."

    private val PHONE = Regex("^\\+[1-9][0-9]{7,14}$")
    private val POSTAL = Regex("^[A-Za-z0-9 -]+$")
    private val TAX_NUMBER = Regex("^[A-Za-z0-9-]+$")
    private val NAME_KEYS = listOf("firstName", "lastName", "company", "phone", "email", "country", "state", "city", "district", "neighborhood", "line1", "line2", "postalCode", "taxOffice", "taxNumber", "identityNumber")

    sealed class Result {
        /** [json] is `null` when nothing is stored (mode `OFF` without a provider requirement, or nothing sent). */
        class Valid(val json: JsonObject?) : Result()

        /** Every offending path (`billingInfo.line1`, ...), missing and invalid, in a stable order. */
        class Invalid(val fields: List<String>) : Result()
    }

    /**
     * @param raw the `billingInfo` object of the request, `null` when absent
     * @param required the paths of [RequiredBuyerFields.of] (`email`, `shippingAddress` and `billingInfo.*`)
     * @param have paths the caller already knows are satisfied (`email` when the order has an e-mail, `shippingAddress` when it has one)
     */
    fun check(raw: JsonObject?, mode: BillingInfoMode, required: List<String>, have: Set<String> = emptySet()): Result {
        val cleaned = LinkedHashMap<String, String>()
        val invalid = LinkedHashSet<String>()
        var type = "INDIVIDUAL"

        if (raw != null) {
            for (key in NAME_KEYS) {
                val value = clean(raw.getValue(key)) ?: continue

                val normalised = if (key == "country") value.uppercase(Locale.ROOT) else value

                if (!valid(key, normalised)) invalid += PREFIX + key else cleaned[key] = normalised
            }

            val sentType = raw.getValue("type")

            if (sentType != null) {
                val text = clean(sentType)?.uppercase(Locale.ROOT)

                if (text == "INDIVIDUAL" || text == "COMPANY") type = text else invalid += PREFIX + "type"
            }
        }

        // identity numbers are checked against the (cleaned) country, whatever order the keys came in
        cleaned["identityNumber"]?.let { id ->
            if (!identityValid(id, cleaned["country"])) {
                invalid += PREFIX + "identityNumber"
                cleaned.remove("identityNumber")
            }
        }

        val missing = LinkedHashSet<String>()

        for (path in required) {
            if (path in have) continue

            if (!path.startsWith(PREFIX)) {
                // `shippingAddress` / `email` that the caller did not vouch for
                missing += path

                continue
            }

            val key = path.removePrefix(PREFIX)

            if (key == "type") continue // defaults to INDIVIDUAL

            if (cleaned[key] == null && path !in invalid) missing += path
        }

        val fields = (missing + invalid).toList()

        if (fields.isNotEmpty()) return Result.Invalid(fields)

        // OFF stores only what a provider required; OPTIONAL and REQUIRED store everything validated
        val kept = if (mode == BillingInfoMode.OFF) {
            cleaned.filterKeys { (PREFIX + it) in required }
        } else {
            cleaned
        }

        if (kept.isEmpty()) return Result.Valid(null)

        val json = JsonObject()

        for (key in NAME_KEYS) kept[key]?.let { json.put(key, it) }

        json.put("type", type)

        return Result.Valid(json)
    }

    private fun clean(value: Any?): String? {
        val text = when (value) {
            null -> return null
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> return ""
        }

        return text.filterNot { Character.isISOControl(it) }.trim().ifEmpty { null }
    }

    private fun valid(key: String, value: String): Boolean = when (key) {
        "firstName", "lastName" -> value.length in 1..100
        "company" -> value.length <= 255
        "phone" -> PHONE.matches(value)
        "email" -> BuyerValidator.isValidEmail(value.lowercase(Locale.ROOT))
        "country" -> Countries.isValid(value)
        "state", "city", "district", "neighborhood" -> value.length <= 100
        "line1" -> value.length in 3..255
        "line2" -> value.length <= 255
        "postalCode" -> value.length <= 16 && POSTAL.matches(value)
        "taxOffice" -> value.length <= 100
        "taxNumber" -> value.length <= 32 && TAX_NUMBER.matches(value)
        "identityNumber" -> value.length <= 32
        else -> true
    }

    /** `country = TR` needs exactly 11 digits passing the TCKN checksum; any other country accepts up to 32 characters (checked by [valid]). */
    private fun identityValid(id: String, country: String?): Boolean = if (country == "TR") isTckn(id) else id.length <= 32

    /** The Turkish national id checksum: 11 digits, the first not 0, `d10 = ((d1+d3+d5+d7+d9) * 7 - (d2+d4+d6+d8)) mod 10`, `d11 = (d1..d10) mod 10`. */
    fun isTckn(id: String): Boolean {
        if (id.length != 11 || id.any { it !in '0'..'9' } || id[0] == '0') return false

        val d = id.map { it - '0' }
        val tenth = (((d[0] + d[2] + d[4] + d[6] + d[8]) * 7) - (d[1] + d[3] + d[5] + d[7])).mod(10)

        if (d[9] != tenth) return false

        return d[10] == d.take(10).sum() % 10
    }
}

/**
 * The fingerprint behind `Idempotency-Key` (06 section 5.1): SHA-256 (hex) of the canonical JSON of the whole request
 * body: keys sorted by code point at every level, no insignificant whitespace, numbers in their shortest form (`1.0` and
 * `1` are one number). A different body for the same key is `IDEMPOTENCY_CONFLICT`.
 */
object RequestFingerprint {
    fun canonical(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    fun hash(body: JsonObject): String = sha256(canonical(body))

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is JsonObject -> write(value.map, out)
            is Map<*, *> -> {
                out.append('{')

                value.entries.map { it.key.toString() to it.value }.sortedWith { a, b -> compareCodePoints(a.first, b.first) }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')

                    out.append(Json.encode(k)).append(':')
                    write(v, out)
                }

                out.append('}')
            }

            is JsonArray -> write(value.list, out)
            is Iterable<*> -> {
                out.append('[')

                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')

                    write(v, out)
                }

                out.append(']')
            }

            is Boolean -> out.append(value.toString())
            is Number -> out.append(number(value))
            is CharSequence -> out.append(Json.encode(value.toString()))
            else -> out.append(Json.encode(value.toString()))
        }
    }

    private fun number(value: Number): String {
        val decimal = when (value) {
            is BigDecimal -> value
            is Double, is Float -> BigDecimal(value.toString())
            else -> BigDecimal(value.toString())
        }

        return if (decimal.signum() == 0) "0" else decimal.stripTrailingZeros().toPlainString()
    }

    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0

        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)

            if (ca != cb) return ca.compareTo(cb)

            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }

        return (a.length - i).compareTo(b.length - j)
    }
}
