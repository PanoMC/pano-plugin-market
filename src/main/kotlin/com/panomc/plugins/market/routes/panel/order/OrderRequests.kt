package com.panomc.plugins.market.routes.panel.order

import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.parseEnum
import com.panomc.plugins.market.routes.base.parseText
import com.panomc.plugins.market.service.OrderFilter
import com.panomc.plugins.market.util.OrderStatus

// Pure parsing of the query of `GET /orders` and `GET /orders/export` (04 section 7). One shape for both, so the export applies exactly the filters of the list the
// admin is looking at. A value outside the contract is a RequestValueException (400), never a silent default.

/** `a,b,c` as a distinct list of enum values; absent or blank is empty. An unknown name is refused. */
internal fun <E : Enum<E>> parseEnumCsv(values: Array<E>, raw: String?, name: String): Set<E> =
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { parseEnum(values, it, name) }?.toCollection(LinkedHashSet()).orEmpty()

/** `testMode`: `true`, `false` or `all` (or absent): `all` does not filter. */
internal fun parseTestMode(raw: String?): Boolean? = when (raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
    null, "all" -> null
    "true", "1" -> true
    "false", "0" -> false
    else -> throw RequestValueException("testMode", "INVALID")
}

/** An epoch-millisecond timestamp: plain digits only. */
internal fun parseTimestamp(raw: String?, name: String): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    if (!Regex("^[0-9]{1,15}$").matches(value)) throw RequestValueException(name, "MUST_BE_A_TIMESTAMP")

    return value.toLong()
}

internal const val SEARCH_MAX = 100

internal fun parseOrderFilter(
    status: String?, paymentMethodId: String?, fulfillmentStatus: String?, shippingStatus: String?, from: String?, to: String?, testMode: String?, source: String?, search: String?
): OrderFilter {
    val start = parseTimestamp(from, "from")
    val end = parseTimestamp(to, "to")

    if (start != null && end != null && end < start) throw RequestValueException("to", "BEFORE_FROM")

    return OrderFilter(
        statuses = parseEnumCsv(OrderStatus.entries.toTypedArray(), status, "status"),
        paymentMethodId = parseText(paymentMethodId, "paymentMethodId", maxLength = 64),
        fulfillmentStatuses = parseEnumCsv(FulfillmentStatus.entries.toTypedArray(), fulfillmentStatus, "fulfillmentStatus"),
        shippingStatuses = parseEnumCsv(ShippingStatus.entries.toTypedArray(), shippingStatus, "shippingStatus"),
        from = start,
        to = end,
        testMode = parseTestMode(testMode),
        sources = parseEnumCsv(OrderSource.entries.toTypedArray(), source, "source"),
        search = parseText(search, "search", maxLength = SEARCH_MAX)
    )
}

/** The query keys of the filter, so the list and the export declare the same ones. */
internal val ORDER_FILTER_QUERY = listOf("status", "paymentMethodId", "fulfillmentStatus", "shippingStatus", "from", "to", "testMode", "source", "search")
