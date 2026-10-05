package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.ProductFieldType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Who typed a username: a buyer (strict Java-edition alphabet) or an admin (Bedrock prefixes `.` and `*` allowed), 00 section 8.4. */
enum class UsernameOrigin { BUYER, ADMIN }

/** How a value must look before it may be substituted into a console command (08 section 3.3). */
enum class ValueKind { USERNAME, NUMBER, DECIMAL, IDENTIFIER, EMAIL, DISCORD_ID, FREE_TEXT, FIELD_TEXT, FORBIDDEN, LATE_BOUND }

/**
 * A resolved variable. [origin] only matters for [ValueKind.USERNAME]. [pattern] is the own pattern of a `TEXT` field
 * ([ValueKind.FIELD_TEXT]). [usableInCommands] is `false` for a field with `usableInCommands=0`.
 */
class VariableValue(
    val kind: ValueKind,
    val raw: String,
    val origin: UsernameOrigin = UsernameOrigin.BUYER,
    val pattern: String? = null,
    val usableInCommands: Boolean = true
)

/** A buyer-supplied custom field value as the order item snapshot keeps it (`market_order_item.fieldValues`). */
class FieldValue(
    val type: ProductFieldType,
    val value: String,
    val pattern: String? = null,
    val usableInCommands: Boolean = true
)

/**
 * Everything a template can reference (08 section 3.2), resolved once. Pure: time is a parameter. [resolve] answers
 * `null` for a name that is not in the catalogue, so the renderers leave such a token verbatim (NBT / JSON such as
 * `{display}` must survive).
 *
 * Names under `variant.` and `field.` are always known; a missing attribute or field resolves to the empty value, which a
 * command refuses (`EMPTY_VARIABLE`) instead of sending a literal `{field.x}` to the console.
 */
class VariableContext private constructor(
    private val values: Map<String, VariableValue>,
    private val variantAttributes: Map<String, String>,
    private val fields: Map<String, FieldValue>,
    private val extras: Map<String, String>
) {
    fun resolve(name: String, includeExtras: Boolean = false): VariableValue? {
        values[name]?.let { return it }

        if (name.startsWith("variant.")) return VariableValue(ValueKind.FREE_TEXT, variantAttributes[name.removePrefix("variant.")].orEmpty())

        if (name.startsWith("field.")) {
            val field = fields[name.removePrefix("field.")] ?: return VariableValue(ValueKind.FREE_TEXT, "")

            return field.toValue()
        }

        if (includeExtras) extras[name]?.let { return VariableValue(ValueKind.FREE_TEXT, it) }

        return null
    }

    private fun FieldValue.toValue(): VariableValue {
        val kind = when (type) {
            ProductFieldType.TEXT -> ValueKind.FIELD_TEXT
            ProductFieldType.NUMBER -> ValueKind.NUMBER
            ProductFieldType.USERNAME -> ValueKind.USERNAME
            ProductFieldType.EMAIL -> ValueKind.EMAIL
            ProductFieldType.DISCORD_ID -> ValueKind.DISCORD_ID
            ProductFieldType.SELECT, ProductFieldType.CHECKBOX -> ValueKind.IDENTIFIER
        }

        return VariableValue(kind, value, UsernameOrigin.BUYER, pattern, usableInCommands)
    }

    /** The same context with webhook-only names ([extras], 08 section 16.2) added; commands never see them. */
    fun withExtras(more: Map<String, String>) = VariableContext(values, variantAttributes, fields, extras + more)

    class Input(
        val recipientUsername: String,
        val buyerUsername: String = recipientUsername,
        val recipientOrigin: UsernameOrigin = UsernameOrigin.BUYER,
        val buyerOrigin: UsernameOrigin = UsernameOrigin.BUYER,
        /** Platform `user.mcUuid` of the recipient, a hint for webhooks; commands keep `{uuid}` late-bound. */
        val playerUuid: String? = null,
        val orderId: Long = 0,
        val orderPublicId: String = "",
        /** x100 amounts (`market_order.totalPrice`, `market_order_item.lineTotal`). */
        val orderTotal: Long = 0,
        val orderCurrency: String = "",
        val giftMessage: String? = null,
        val productId: Long = 0,
        val productName: String = "",
        val productSlug: String = "",
        val productSku: String? = null,
        val bundleName: String? = null,
        val variantName: String? = null,
        val variantSku: String? = null,
        val variantAttributes: Map<String, String> = emptyMap(),
        val fields: Map<String, FieldValue> = emptyMap(),
        /** Units covered by this delivery (`1` for `perUnit`). */
        val quantity: Int = 1,
        /** 1-based unit number for `perUnit`, else 1. */
        val unit: Int = 1,
        val serverId: Long? = null,
        val serverName: String? = null,
        val lineTotal: Long = 0,
        /** Purchased quantity of the line, the divisor of `{price}`. */
        val lineQuantity: Int = 1,
        /** Entitlement end (epoch millis); `null` = permanent ownership. */
        val expiresAtMillis: Long? = null,
        val phase: DeliveryPhase = DeliveryPhase.GRANT,
        val now: Long = 0,
        val zone: ZoneId = ZoneId.of("UTC")
    )

    companion object {
        /** Token form (08 section 3.1): group 1 = name, group 2 = default text. */
        val TOKEN = Regex("""\{([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)?)(?:\|([A-Za-z0-9_.:\- ]{0,64}))?\}""")

        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        /** x100 amount as a plain decimal with a dot and two decimals, e.g. `1250` -> `12.50`. */
        fun plainDecimal(x100: Long): String = BigDecimal.valueOf(x100, 2).setScale(2).toPlainString()

        fun build(input: Input): VariableContext {
            val values = HashMap<String, VariableValue>()

            fun put(name: String, kind: ValueKind, raw: String?, origin: UsernameOrigin = UsernameOrigin.BUYER) {
                values[name] = VariableValue(kind, raw.orEmpty(), origin)
            }

            put("username", ValueKind.USERNAME, input.recipientUsername, input.recipientOrigin)
            put("player", ValueKind.USERNAME, input.recipientUsername, input.recipientOrigin)
            put("recipient.username", ValueKind.USERNAME, input.recipientUsername, input.recipientOrigin)
            put("buyer.username", ValueKind.USERNAME, input.buyerUsername, input.buyerOrigin)
            values["uuid"] = VariableValue(ValueKind.LATE_BOUND, input.playerUuid.orEmpty())

            put("order.id", ValueKind.NUMBER, input.orderId.toString())
            put("order.publicId", ValueKind.IDENTIFIER, input.orderPublicId)
            put("order.total", ValueKind.DECIMAL, plainDecimal(input.orderTotal))
            put("order.currency", ValueKind.IDENTIFIER, input.orderCurrency)

            put("product.id", ValueKind.NUMBER, input.productId.toString())
            put("product.name", ValueKind.FREE_TEXT, input.productName)
            put("product", ValueKind.FREE_TEXT, input.productName)
            put("product.slug", ValueKind.IDENTIFIER, input.productSlug)
            put("product.sku", ValueKind.FREE_TEXT, input.productSku)
            put("bundle.name", ValueKind.FREE_TEXT, input.bundleName)
            put("variant.name", ValueKind.FREE_TEXT, input.variantName)
            put("variant.sku", ValueKind.FREE_TEXT, input.variantSku)

            put("quantity", ValueKind.NUMBER, input.quantity.toString())
            put("unit", ValueKind.NUMBER, input.unit.toString())
            put("server.id", ValueKind.NUMBER, input.serverId?.takeIf { it != 0L }?.toString())
            put("server.name", ValueKind.FREE_TEXT, input.serverName)

            val divisor = BigDecimal(input.lineQuantity.coerceAtLeast(1))
            val price = BigDecimal(input.lineTotal).divide(divisor, 0, RoundingMode.HALF_UP).longValueExact()
            put("price", ValueKind.DECIMAL, plainDecimal(price))

            val end = input.expiresAtMillis
            if (end == null) {
                for (name in listOf("expiresAt", "expiresAt.iso", "period.days", "period.seconds")) put(name, ValueKind.NUMBER, "")
                values["expiresAt.iso"] = VariableValue(ValueKind.IDENTIFIER, "")
            } else {
                val epochSeconds = Math.floorDiv(end, 1000L)
                val remainingMillis = (end - input.now).coerceAtLeast(0)
                val seconds = (remainingMillis + 999) / 1000
                val days = (seconds + 86_399) / 86_400

                put("expiresAt", ValueKind.NUMBER, epochSeconds.toString())
                put("expiresAt.iso", ValueKind.IDENTIFIER, Instant.ofEpochSecond(epochSeconds).toString())
                put("period.days", ValueKind.NUMBER, days.toString())
                put("period.seconds", ValueKind.NUMBER, seconds.toString())
            }

            val local = Instant.ofEpochMilli(input.now).atZone(input.zone)
            put("date", ValueKind.IDENTIFIER, DATE.format(local))
            put("time", ValueKind.IDENTIFIER, TIME.format(local))

            values["gift.message"] = VariableValue(ValueKind.FORBIDDEN, input.giftMessage.orEmpty())
            put("phase", ValueKind.IDENTIFIER, input.phase.name.lowercase())

            return VariableContext(values, input.variantAttributes, input.fields, emptyMap())
        }
    }
}
