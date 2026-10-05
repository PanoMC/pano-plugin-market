package com.panomc.plugins.market.routes.api.store

import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.service.MAX_STORE_PAGE_SIZE
import com.panomc.plugins.market.service.ProductListQuery
import com.panomc.plugins.market.service.ProductSort
import java.util.Locale

// Pure parsing of the public store queries (04 section 3). Each function throws RequestValueException for a value outside
// the contract, which the route base answers with 400 BAD_REQUEST (never a 500).

private val CURRENCY = Regex("^[A-Za-z]{3,8}$")

/** `?currency=`: absent or blank = `null`; otherwise 3 to 8 letters, upper-cased. Whether it is offered is the pricing code's call. */
fun parseCurrencyParam(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    if (!CURRENCY.matches(value)) throw RequestValueException("currency", "INVALID")

    return value.uppercase(Locale.ROOT)
}

private fun number(raw: String?, name: String): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return value.toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_AN_INTEGER")
}

/** `GET /store/products` query: `category`, `search`, `featured`, `kind`, `sort`, `currency`, `page`, `pageSize` (at most 60). */
fun parseProductListQuery(
    category: String?,
    search: String?,
    featured: String?,
    kind: String?,
    sort: String?,
    currency: String?,
    page: String?,
    pageSize: String?,
    defaultPageSize: Int
): ProductListQuery {
    val categoryId = number(category, "category")?.also { if (it < 1) throw RequestValueException("category", "MUST_BE_AN_INTEGER_ID") }
    val text = search?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 100) throw RequestValueException("search", "TOO_LONG") }
    val featuredFlag = when (featured?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }) {
        null -> null
        "true" -> true
        "false" -> false
        else -> throw RequestValueException("featured", "MUST_BE_A_BOOLEAN")
    }
    val kindValue = kind?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        ProductKind.entries.firstOrNull { it.name == raw } ?: throw RequestValueException("kind", "UNKNOWN_VALUE")
    }
    val sortValue = sort?.trim()?.takeIf { it.isNotEmpty() }?.let { ProductSort.of(it) ?: throw RequestValueException("sort", "UNKNOWN_VALUE") }
        ?: ProductSort.PRIORITY
    val window = parsePagingRequest(number(page, "page"), number(pageSize, "pageSize"), defaultPageSize.coerceIn(1, MAX_STORE_PAGE_SIZE), MAX_STORE_PAGE_SIZE)

    return ProductListQuery(
        category = categoryId, search = text, featured = featuredFlag, kind = kindValue, sort = sortValue,
        currency = parseCurrencyParam(currency), page = window.page, pageSize = window.pageSize
    )
}
