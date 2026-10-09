package com.panomc.plugins.market.support

import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Error
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

/** The `error.fields` of an [Error] as its body carries them. */
fun fieldsOf(e: Error): Map<String, Any?> = JsonObject(e.encode()).getJsonObject("error").getJsonObject("fields")?.map.orEmpty()

/** [block] is refused by the core page rule (04 section 4): 400 `INVALID_FIELDS` with exactly [fields], each `OUT_OF_RANGE`. */
fun assertOutOfRange(vararg fields: String, block: () -> Unit) {
    val e = assertThrows(InvalidFields::class.java) { block() }

    assertEquals(400, e.getStatusCode())
    assertEquals(fields.associateWith { "OUT_OF_RANGE" }, fieldsOf(e))
}

/** [block] asks for a page beyond the last one: 404 `PAGE_NOT_FOUND`. */
fun assertPageNotFound(block: () -> Unit) {
    val e = assertThrows(PageNotFound::class.java) { block() }

    assertEquals("PAGE_NOT_FOUND", e.getErrorCode())
    assertEquals(404, e.getStatusCode())
}

/** `items` of a core page body. */
fun JsonObject.items() = getJsonArray("items")

/** `page.<key>` of a core page body as a long. */
fun JsonObject.pageLong(key: String): Long = getJsonObject("page").getLong(key)
