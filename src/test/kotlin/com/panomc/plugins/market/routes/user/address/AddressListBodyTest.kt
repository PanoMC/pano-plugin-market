package com.panomc.plugins.market.routes.user.address

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** `GET /me/addresses` answers its list under `items` and, being unpaged, carries no `page` (04 section 4). */
class AddressListBodyTest {
    @Test
    fun `the address list is under items and has no page`() {
        val body = AddressBookService.renderAll(listOf(JsonObject().put("id", 1), JsonObject().put("id", 2)))

        assertEquals(setOf("items"), body.fieldNames())
        assertEquals(listOf(1, 2), body.getJsonArray("items").map { (it as JsonObject).getInteger("id") })
        assertFalse(body.containsKey("page"))
        assertFalse(body.containsKey("addresses"))
    }

    @Test
    fun `an empty address book is an empty items array`() {
        assertEquals(0, AddressBookService.renderAll(emptyList()).getJsonArray("items").size())
    }
}
