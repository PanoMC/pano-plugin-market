package com.panomc.plugins.market.routes.base

import com.panomc.platform.model.PageRequest
import com.panomc.plugins.market.support.assertOutOfRange
import com.panomc.plugins.market.support.assertPageNotFound
import com.panomc.plugins.market.support.items
import com.panomc.plugins.market.support.pageLong
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Market lists use the core page rule (04 section 4): market only adds the text parser and the [JsonObject] wrapper. */
class PageResponseTest {
    @Test
    fun `defaults are page 1 and 10 rows, blanks count as absent`() {
        val d = parsePageRequest(null, null)

        assertEquals(1, d.number)
        assertEquals(10, d.size)
        assertEquals(0L, d.offset)

        val blank = parsePageRequest(" ", "")

        assertEquals(1, blank.number)
        assertEquals(10, blank.size)
    }

    @Test
    fun `offset is page minus one times the page size`() {
        assertEquals(20L, parsePageRequest("3", "10").offset)
        assertEquals(24L, parsePageRequest("2", "24").offset)
        assertEquals(99L * 100, parsePageRequest("100", "100").offset)
    }

    @Test
    fun `page size 100 is allowed and 101 is OUT_OF_RANGE`() {
        assertEquals(100, parsePageRequest("1", "100").size)

        assertOutOfRange("pageSize") { parsePageRequest("1", "101") }
    }

    @Test
    fun `an endpoint can lower the maximum, the storefront takes 60`() {
        assertEquals(60, parsePageRequest("1", "60", defaultSize = 24, maxSize = 60).size)
        assertEquals(24, parsePageRequest(null, null, defaultSize = 24, maxSize = 60).size)

        assertOutOfRange("pageSize") { parsePageRequest("1", "61", defaultSize = 24, maxSize = 60) }
    }

    @Test
    fun `zero, negative, absurd and unreadable values are refused instead of clamped`() {
        assertOutOfRange("page") { parsePageRequest("0", "10") }
        assertOutOfRange("page") { parsePageRequest("-3", "10") }
        assertOutOfRange("page") { parsePageRequest("abc", "10") }
        assertOutOfRange("pageSize") { parsePageRequest("1", "0") }
        assertOutOfRange("pageSize") { parsePageRequest("1", "-1") }
        assertOutOfRange("pageSize") { parsePageRequest("1", "1.5") }
        assertOutOfRange("page", "pageSize") { parsePageRequest("0", "0") }
    }

    @Test
    fun `the body is items and page plus the extra keys`() {
        val body = pageJson(listOf(JsonObject().put("id", 1), JsonObject().put("id", 2)), 5, PageRequest(2, 2), mapOf("category" to JsonObject().put("id", 9)))

        assertEquals(listOf(1, 2), body.items().map { (it as JsonObject).getInteger("id") })
        assertEquals(2L, body.pageLong("number"))
        assertEquals(2L, body.pageLong("size"))
        assertEquals(5L, body.pageLong("totalItems"))
        assertEquals(3L, body.pageLong("totalPages"))
        assertEquals(9, body.getJsonObject("category").getInteger("id"))
        assertEquals(setOf("items", "page", "category"), body.fieldNames())
    }

    @Test
    fun `an empty list is page 1 with no pages, a page beyond the last is PAGE_NOT_FOUND`() {
        val empty = pageJson(emptyList(), 0, PageRequest(1, 10))

        assertTrue(empty.items().isEmpty)
        assertEquals(0L, empty.pageLong("totalPages"))

        assertPageNotFound { pageJson(emptyList(), 0, PageRequest(2, 10)) }
        assertPageNotFound { pageJson(emptyList(), 3, PageRequest(3, 2)) }
        assertFalse(pageJson(emptyList(), 3, PageRequest(2, 2)).isEmpty)
    }
}
