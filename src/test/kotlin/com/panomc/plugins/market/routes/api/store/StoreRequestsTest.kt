package com.panomc.plugins.market.routes.api.store

import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.service.ProductSort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The query parsing of the public store routes (04 section 3): defaults, bounds, and refusal instead of a silent fallback. */
class StoreRequestsTest {
    private fun parse(
        category: String? = null, search: String? = null, featured: String? = null, kind: String? = null, sort: String? = null,
        currency: String? = null, page: String? = null, pageSize: String? = null, default: Int = 24
    ) = parseProductListQuery(category, search, featured, kind, sort, currency, page, pageSize, default)

    private fun refused(field: String, block: () -> Unit) {
        val e = assertThrows(RequestValueException::class.java, block)
        assertEquals(field, e.field)
    }

    @Test
    fun `defaults`() {
        val q = parse()

        assertNull(q.category)
        assertNull(q.search)
        assertNull(q.featured)
        assertNull(q.kind)
        assertNull(q.currency)
        assertEquals(ProductSort.PRIORITY, q.sort)
        assertEquals(1, q.page)
        assertEquals(24, q.pageSize, "the default page size is storePageSize")
    }

    @Test
    fun `every value is read`() {
        val q = parse("5", " sword ", "TRUE", "BUNDLE", "price-desc", "usd", "3", "12")

        assertEquals(5L, q.category)
        assertEquals("sword", q.search)
        assertEquals(true, q.featured)
        assertEquals(ProductKind.BUNDLE, q.kind)
        assertEquals(ProductSort.PRICE_DESC, q.sort)
        assertEquals("USD", q.currency)
        assertEquals(3, q.page)
        assertEquals(12, q.pageSize)
        assertEquals(false, parse(featured = "false").featured)
    }

    @Test
    fun `every sort value`() {
        for (s in listOf("priority", "newest", "price-asc", "price-desc", "bestselling")) assertEquals(s, parse(sort = s).sort.wire)
    }

    @Test
    fun `blank values count as absent`() {
        val q = parse("", " ", "", "", "", "", "", "")

        assertNull(q.category)
        assertNull(q.search)
        assertEquals(ProductSort.PRIORITY, q.sort)
        assertEquals(1, q.page)
    }

    @Test
    fun `page size is at most 60 and a default above that is capped`() {
        assertEquals(60, parse(pageSize = "60").pageSize)
        refused("pageSize") { parse(pageSize = "61") }
        refused("pageSize") { parse(pageSize = "0") }
        refused("pageSize") { parse(pageSize = "-3") }
        assertEquals(60, parse(default = 500).pageSize)
        assertEquals(1, parse(default = 0).pageSize)
    }

    @Test
    fun `bad values are refused with the field name`() {
        refused("page") { parse(page = "0") }
        refused("page") { parse(page = "abc") }
        refused("page") { parse(page = "1.5") }
        refused("category") { parse(category = "x") }
        refused("category") { parse(category = "0") }
        refused("category") { parse(category = "1.5") }
        refused("featured") { parse(featured = "yes") }
        refused("kind") { parse(kind = "standard") }
        refused("kind") { parse(kind = "OTHER") }
        refused("sort") { parse(sort = "cheapest") }
        refused("search") { parse(search = "x".repeat(101)) }
        refused("currency") { parse(currency = "US") }
        refused("currency") { parse(currency = "US1") }
        refused("currency") { parse(currency = "'; DROP") }
    }

    @Test
    fun `currency param`() {
        assertNull(parseCurrencyParam(null))
        assertNull(parseCurrencyParam("  "))
        assertEquals("TRY", parseCurrencyParam(" try "))
        refused("currency") { parseCurrencyParam("A") }
        refused("currency") { parseCurrencyParam("ABCDEFGHI") }
    }
}
