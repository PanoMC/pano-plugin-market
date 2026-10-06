package com.panomc.plugins.market.permission

import com.panomc.platform.error.NoPermission
import com.panomc.plugins.market.core.abuse.PiiMask
import com.panomc.plugins.market.db.model.MarketOrder
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext

/**
 * Field-level gating of the panel read endpoints (11 section 14.5). Two tiers:
 *  - the PII tier: `OM` or `PAY` (or the umbrella / `*`): the e-mail, the billing data, the shipping address, the client IP and user agent and the
 *    gift message of an order come back as stored; below it the e-mail is masked, the rest is `null`, the e-mail predicate of the order search is not
 *    applied and a CSV export refuses the PII columns;
 *  - the raw tier: `SET`: the body, headers and URL of a stored payment event (still redacted at write time, 11 section 8.4).
 * The functions are pure; the route asks [piiTier] / [rawTier] once and passes the answer in, so one projection serves a list row and a detail alike.
 * Tokens (`accessToken`, `token`, `webhookToken`), `providerData`, `storedMethod` and `startPayload` are never part of any projection.
 */
object FieldGating {
    /** Holders of any of these (or the umbrella) see personal data of an order. */
    val PII_NODES: Set<MarketNode> = setOf(MarketNode.ORDERS_MANAGE, MarketNode.PAYMENTS)

    /** Holders of this node (or the umbrella) see the raw bodies of payment events. */
    val RAW_NODES: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    /** The CSV columns of `GET /orders/export` that need the PII tier (04 section 7). */
    val PII_EXPORT_COLUMNS: Set<String> = setOf("email", "country")

    suspend fun piiTier(context: RoutingContext): Boolean = MarketPermissions.has(context, PII_NODES)

    suspend fun rawTier(context: RoutingContext): Boolean = MarketPermissions.has(context, RAW_NODES)

    /** `john@example.com` as is with the PII tier, `j***@e***.com` without; `null` stays `null`. */
    fun email(value: String?, pii: Boolean): String? = if (pii) value else PiiMask.email(value)

    /** [value] with the PII tier, `null` without. */
    fun <T> piiOnly(value: T?, pii: Boolean): T? = if (pii) value else null

    /** A stored JSON text (billing info, shipping address) as an object; `null` when blank or not an object, so the response never carries a raw string of unknown shape. */
    fun jsonObject(raw: String?): JsonObject? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() }

    /** The gated personal fields of an order, in the keys of 04 section 7: `email`, `billingInfo`, `shippingAddress`, `clientIp`, `userAgent`, `giftMessage`. */
    fun orderPii(order: MarketOrder, pii: Boolean): Map<String, Any?> = linkedMapOf(
        "email" to email(order.email, pii),
        "billingInfo" to piiOnly(jsonObject(order.billingInfo), pii),
        "shippingAddress" to piiOnly(jsonObject(order.shippingAddress), pii),
        "clientIp" to piiOnly(order.clientIp, pii),
        "userAgent" to piiOnly(order.userAgent, pii),
        "giftMessage" to piiOnly(order.giftMessage, pii)
    )

    /** The raw parts of a payment event: [body], [headers] and [url] with the raw tier, all three `null` without. */
    fun eventRaw(body: String?, headers: String?, url: String?, raw: Boolean): Map<String, Any?> = linkedMapOf(
        "body" to piiOnly(body, raw),
        "headers" to piiOnly(headers, raw),
        "url" to piiOnly(url, raw)
    )

    /** Throws the platform `NoPermission` (403) when [columns] names a PII column and the caller is below the PII tier. */
    fun requireExportColumns(columns: Collection<String>, pii: Boolean) {
        if (!pii && columns.any { it in PII_EXPORT_COLUMNS }) throw NoPermission()
    }
}
