package com.panomc.plugins.market.routes.base

import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import io.vertx.core.json.JsonObject

/**
 * The core page shape (04 section 4) as a [JsonObject], for the services that build their body as one:
 * `{ "items": [...], "page": { number, size, totalItems, <page count> }, ...extra }`. A page beyond the last is the core
 * 404 `PAGE_NOT_FOUND` (thrown by [Paging.response]).
 */
fun pageJson(items: List<Any?>, totalItems: Long, page: PageRequest, extra: Map<String, Any?> = mapOf()): JsonObject =
    JsonObject(Paging.response(items, totalItems, page, extra))

/**
 * `page` / `pageSize` as the text of a query ([Paging.parse] with a blank value read as absent). A page below 1 or a
 * pageSize outside `1..maxSize` is the core 400 `INVALID_FIELDS` with `OUT_OF_RANGE` (never clamped).
 */
fun parsePageRequest(
    page: String?,
    pageSize: String?,
    defaultSize: Int = Paging.DEFAULT_SIZE,
    maxSize: Int = Paging.MAX_SIZE
): PageRequest = Paging.parse(page?.trim()?.takeIf { it.isNotEmpty() }, pageSize?.trim()?.takeIf { it.isNotEmpty() }, defaultSize, maxSize)
