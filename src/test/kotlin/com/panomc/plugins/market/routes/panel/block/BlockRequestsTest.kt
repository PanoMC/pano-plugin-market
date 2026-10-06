package com.panomc.plugins.market.routes.panel.block

import com.panomc.plugins.market.db.model.BlockSource
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.error.RequestValueException
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The parsing of the block routes (MK-151, 04 section 7): the contract of the query and of the body, pure. */
class BlockRequestsTest {
    @Test
    fun `an empty query is the first page of ten with no filter`() {
        val q = parseBlockListQuery(null, null, null, null, null)

        assertNull(q.type)
        assertNull(q.source)
        assertNull(q.search)
        assertEquals(1, q.window.page)
        assertEquals(10, q.window.pageSize)
    }

    @Test
    fun `every filter is parsed and a blank one is no filter`() {
        val q = parseBlockListQuery("IP", "CHARGEBACK", " 203.0 ", "3", "50")

        assertEquals(BlockType.IP, q.type)
        assertEquals(BlockSource.CHARGEBACK, q.source)
        assertEquals("203.0", q.search)
        assertEquals(3, q.window.page)
        assertEquals(50, q.window.pageSize)

        val blank = parseBlockListQuery(" ", "", "  ", " ", "")

        assertNull(blank.type)
        assertNull(blank.source)
        assertNull(blank.search)
    }

    @Test
    fun `a value outside the contract is refused, never ignored`() {
        for (bad in listOf(
            { parseBlockListQuery("player", null, null, null, null) },
            { parseBlockListQuery("NOPE", null, null, null, null) },
            { parseBlockListQuery(null, "AUTO", null, null, null) },
            { parseBlockListQuery(null, null, "x".repeat(256), null, null) },
            { parseBlockListQuery(null, null, null, "abc", null) },
            { parseBlockListQuery(null, null, null, "0", null) },
            { parseBlockListQuery(null, null, null, null, "101") },
            { parseBlockListQuery(null, null, null, null, "0") },
            { parseBlockListQuery(null, null, null, null, "1.5") }
        )) assertThrows(RequestValueException::class.java) { bad() }
    }

    @Test
    fun `a create body keeps the four keys and refuses everything else`() {
        val ok = parseBlockCreateRequest(JsonObject().put("type", "PLAYER").put("value", "steve").put("reason", "spam").put("expiresAt", 1_800_000_000_000L))

        assertEquals("PLAYER", ok.type)
        assertEquals("steve", ok.value)
        assertEquals("spam", ok.reason)
        assertEquals(1_800_000_000_000L, ok.expiresAt)

        val small = parseBlockCreateRequest(JsonObject().put("type", "IP").put("value", "1.2.3.4").put("expiresAt", 5))

        assertEquals(5L, small.expiresAt)
        assertNull(small.reason)
        assertNull(parseBlockCreateRequest(JsonObject()).type, "a missing type is the service's INVALID_BLOCK")

        for (bad in listOf(
            JsonObject().put("type", "PLAYER").put("value", "x").put("source", "CHARGEBACK"),
            JsonObject().put("type", "PLAYER").put("value", "x").put("createdBy", 1),
            JsonObject().put("type", 1).put("value", "x"),
            JsonObject().put("type", "PLAYER").put("value", JsonObject()),
            JsonObject().put("type", "PLAYER").put("value", "x").put("reason", 5),
            JsonObject().put("type", "PLAYER").put("value", "x").put("expiresAt", "tomorrow"),
            JsonObject().put("type", "PLAYER").put("value", "x").put("expiresAt", 1.5)
        )) assertThrows(RequestValueException::class.java) { parseBlockCreateRequest(bad) }
    }
}
