package com.panomc.plugins.market.routes.base

import com.panomc.plugins.market.error.RequestValueException
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RequestParsersTest {
    private enum class Kind { ONE, TWO }

    private fun refused(field: String, reason: String? = null, block: () -> Unit) {
        val e = assertThrows(RequestValueException::class.java) { block() }
        assertEquals(field, e.field)
        if (reason != null) assertEquals(reason, e.reason)
    }

    // ---- idempotency key

    @Test
    fun `idempotency key accepts 16 to 64 url-safe characters`() {
        assertEquals("0123456789abcdef", parseIdempotencyKey("0123456789abcdef", true))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", parseIdempotencyKey("123e4567-e89b-12d3-a456-426614174000", true))
        assertEquals("A_b-" + "x".repeat(60), parseIdempotencyKey("A_b-" + "x".repeat(60), true))
        assertEquals("0123456789abcdef", parseIdempotencyKey("  0123456789abcdef  ", true), "surrounding blanks are trimmed")
    }

    @Test
    fun `idempotency key refuses a short, long or non url-safe value`() {
        refused("Idempotency-Key", "INVALID") { parseIdempotencyKey("0123456789abcde", true) }
        refused("Idempotency-Key", "INVALID") { parseIdempotencyKey("x".repeat(65), true) }
        refused("Idempotency-Key", "INVALID") { parseIdempotencyKey("0123456789abcdef!", true) }
        refused("Idempotency-Key", "INVALID") { parseIdempotencyKey("0123456789 abcdef", true) }
        refused("Idempotency-Key", "INVALID") { parseIdempotencyKey("0123456789abcdé0", true) }
    }

    @Test
    fun `a missing key is refused only where it is required, a malformed one always`() {
        refused("Idempotency-Key", "REQUIRED") { parseIdempotencyKey(null, true) }
        refused("Idempotency-Key", "REQUIRED") { parseIdempotencyKey("   ", true) }
        assertNull(parseIdempotencyKey(null, false))
        assertNull(parseIdempotencyKey("", false))
        refused("Idempotency-Key", "INVALID") { parseIdempotencyKey("short", false) }
    }

    // ---- ids

    @Test
    fun `a path id is a plain positive integer`() {
        assertEquals(1L, parseId("1"))
        assertEquals(42L, parseId(" 42 "))
        assertEquals(999999999999999999L, parseId("999999999999999999"))
        for (bad in listOf("1.5", "0", "-1", "+1", "1e3", "abc", "", " ", "1 2", "9999999999999999999", "０１")) {
            refused("id", "MUST_BE_AN_INTEGER_ID") { parseId(bad) }
        }
        refused("productId") { parseId(null, "productId") }
    }

    @Test
    fun `a body id is an integral number of at least one`() {
        assertEquals(7L, parseBodyId(7, "id"))
        assertEquals(7L, parseBodyId(7L, "id"))
        assertEquals(7L, parseBodyId(7.0, "id"))
        refused("id") { parseBodyId(1.5, "id") }
        refused("id") { parseBodyId(0, "id") }
        refused("id") { parseBodyId(-2, "id") }
        refused("id") { parseBodyId("7", "id") }
        refused("id") { parseBodyId(null, "id") }
        refused("id") { parseBodyId(Double.NaN, "id") }
        refused("id") { parseBodyId(Double.POSITIVE_INFINITY, "id") }
        refused("id") { parseBodyId(true, "id") }
    }

    @Test
    fun `an id list is non-empty, bounded, integral and free of duplicates`() {
        assertEquals(listOf(3L, 1L, 2L), parseIdList(JsonArray().add(3).add(1).add(2), "ids"))
        refused("ids", "REQUIRED") { parseIdList(null, "ids") }
        refused("ids", "REQUIRED") { parseIdList(JsonArray(), "ids") }
        refused("ids", "DUPLICATE") { parseIdList(JsonArray().add(1).add(1), "ids") }
        refused("ids", "MUST_BE_AN_INTEGER_ID") { parseIdList(JsonArray().add(1).add(1.5), "ids") }
        refused("ids", "MUST_BE_AN_INTEGER_ID") { parseIdList(JsonArray().add(1).add("2"), "ids") }
        refused("ids", "TOO_MANY") { parseIdList(JsonArray().add(1).add(2).add(3), "ids", max = 2) }
    }

    // ---- enums

    @Test
    fun `an enum is its exact name, absent takes the default, unknown is refused`() {
        assertEquals(Kind.TWO, parseEnum(Kind.values(), "TWO", "kind"))
        assertEquals(Kind.ONE, parseEnum(Kind.values(), null, "kind", default = Kind.ONE))
        refused("kind", "UNKNOWN_VALUE") { parseEnum(Kind.values(), "two", "kind") }
        refused("kind", "UNKNOWN_VALUE") { parseEnum(Kind.values(), "THREE", "kind", default = Kind.ONE) }
        refused("kind", "REQUIRED") { parseEnum(Kind.values(), null, "kind") }
    }

    @Test
    fun `an optional enum filter is null when absent and refused when unknown`() {
        assertNull(parseOptionalEnum(Kind.values(), null, "status"))
        assertEquals(Kind.ONE, parseOptionalEnum(Kind.values(), "ONE", "status"))
        refused("status", "UNKNOWN_VALUE") { parseOptionalEnum(Kind.values(), "NOPE", "status") }
    }

    // ---- text

    @Test
    fun `text is trimmed, bounded and required only when asked`() {
        assertEquals("abc", parseText("  abc ", "name"))
        assertNull(parseText(null, "name"))
        assertNull(parseText("   ", "name"))
        refused("name", "REQUIRED") { parseText("  ", "name", required = true) }
        assertEquals("x".repeat(10), parseText("x".repeat(10), "name", maxLength = 10))
        refused("name", "TOO_LONG") { parseText("x".repeat(11), "name", maxLength = 10) }
    }

    // ---- unknown keys

    @Test
    fun `a body with a key outside the allowed set is refused`() {
        rejectUnknownKeys(JsonObject().put("a", 1).put("b", 2), setOf("a", "b", "c"))
        rejectUnknownKeys(JsonObject(), setOf("a"))
        refused("zzz", "UNKNOWN_PROPERTY") { rejectUnknownKeys(JsonObject().put("a", 1).put("zzz", 2), setOf("a")) }
    }
}
