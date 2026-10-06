package com.panomc.plugins.market.routes.base

import com.panomc.plugins.market.error.RequestValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the panel retrofit of MK-160 added to the existing routes (04 section 1, 11 section 14.5): integer ids, a bounded `pageSize`, the positive exchange rate
 * of `PUT /orders/:id/exchange-rate`, integer body ids of the category sort. The parsing is pure and driven directly; that every route under `routes.panel` takes
 * its ids and paging through those parsers is asserted on the source (the host is outside this tier, E2E-10 proves the status codes over HTTP).
 */
class RetrofitRequestParsingTest {
    private val panelRoot = File("src/main/kotlin/com/panomc/plugins/market/routes/panel")

    private fun panelSources(): List<Pair<String, String>> =
        panelRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path to it.readText() }.toList()

    private fun refused(field: String, reason: String, block: () -> Unit) {
        val e = assertThrows(RequestValueException::class.java) { block() }

        assertEquals(field, e.field)
        assertEquals(reason, e.reason)
    }

    // ---- exchange rate

    @Test
    fun `an exchange rate is a finite number above zero`() {
        assertEquals(34.5, parseExchangeRate(34.5))
        assertEquals(0.0001, parseExchangeRate(0.0001))

        for (bad in listOf(0.0, -0.0, -1.0, -34.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null)) {
            refused("exchangeRate", "MUST_BE_POSITIVE") { parseExchangeRate(bad) }
        }
    }

    @Test
    fun `the exchange rate route parses through parseExchangeRate and requires the property`() {
        val source = File(panelRoot, "order/PanelUpdateOrderExchangeRateAPI.kt").readText()

        assertTrue(source.contains("parseExchangeRate(data.getDouble(\"exchangeRate\"))"), "the body value goes through the parser")
        assertTrue(source.contains(".requiredProperty(\"exchangeRate\", numberSchema())"), "the property is required in the schema")
        assertTrue(source.contains("parseId(context.pathParam(\"id\"))"), "the path id is an integer id")
    }

    // ---- ids and paging

    @Test
    fun `an id is a plain positive integer`() {
        assertEquals(7L, parseId("7"))
        assertEquals(7L, parseId(" 7 "))

        for (bad in listOf("1.5", "0", "-1", "1e3", "abc", "", "  ", null, "9".repeat(19))) {
            refused("id", "MUST_BE_AN_INTEGER_ID") { parseId(bad) }
        }
    }

    @Test
    fun `a page size above 100 is refused, not clamped, and 100 passes`() {
        assertEquals(100, parsePagingRequest(1, 100).pageSize)
        assertEquals(10, parsePagingRequest(null, null).pageSize)

        refused("pageSize", "MUST_BE_BETWEEN_1_AND_100") { parsePagingRequest(1, 500) }
        refused("pageSize", "MUST_BE_BETWEEN_1_AND_100") { parsePagingRequest(1, 0) }
        refused("page", "MUST_BE_POSITIVE") { parsePagingRequest(0, 10) }
    }

    @Test
    fun `no panel route reads an id or a page as a plain number or converts it unchecked`() {
        val banned = listOf(
            Regex("""param\(\s*"id"\s*,\s*numberSchema\(\)"""),
            Regex("""optionalParam\(\s*"page(Size)?"\s*,\s*numberSchema\(\)"""),
            Regex("""param\(\s*"page(Size)?"\s*,\s*numberSchema\(\)"""),
            Regex("""pathParam\([^)]*\)\s*(!!)?\.toLong\("""),
            Regex("""pathParam\([^)]*\)\s*(!!)?\.toInt\("""),
            Regex("""pathParameter\([^)]*\)\s*(!!)?\.long\b"""),
            Regex("""getParam\(\s*"page(Size)?"\s*\)\s*(!!)?\??\.to(Long|Int)\(""")
        )
        val sources = panelSources()

        assertTrue(sources.size > 20, "the scan reaches the panel routes")

        for ((path, text) in sources) for (pattern in banned) assertTrue(!pattern.containsMatchIn(text), "$path matches ${pattern.pattern}")
    }

    @Test
    fun `the category sort body takes integer ids`() {
        val source = File(panelRoot, "category/PanelSortCategoriesAPI.kt").readText()

        assertTrue(source.contains(".requiredProperty(\"id\", intSchema())"), "id is an integer")
        assertTrue(source.contains(".optionalProperty(\"targetId\", intSchema())"), "targetId is an integer")
        assertTrue(!source.contains("numberSchema"), "no plain number schema is left")
    }
}
