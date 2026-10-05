package com.panomc.plugins.market.util

import com.panomc.plugins.market.error.RequestValueException

/**
 * Paging of every list endpoint (04 section 1): `page` (default 1, min 1) and `pageSize` (default 10, max 100, an
 * endpoint may lower the maximum), the response triple `<plural>`, `<singular>Count`, `totalPage`, and 404
 * `PAGE_NOT_FOUND` beyond the last page. Pure: the route throws the platform error.
 */
object Paging {
    const val DEFAULT_PAGE = 1
    const val DEFAULT_PAGE_SIZE = 10
    const val MAX_PAGE_SIZE = 100

    class Window(val page: Int, val pageSize: Int) {
        /** Rows to skip: `(page - 1) * pageSize`. */
        val offset: Long get() = (page - 1).toLong() * pageSize

        override fun equals(other: Any?) = other is Window && other.page == page && other.pageSize == pageSize
        override fun hashCode() = 31 * page + pageSize
        override fun toString() = "Window(page=$page, pageSize=$pageSize)"
    }

    /**
     * Validates the two query values. A missing value takes its default; a value outside `1..` (page) or
     * `1..maxPageSize` (pageSize) is refused, not clamped, so a client never silently gets another window.
     */
    fun window(
        page: Long?,
        pageSize: Long?,
        defaultPageSize: Int = DEFAULT_PAGE_SIZE,
        maxPageSize: Int = MAX_PAGE_SIZE
    ): Window {
        require(defaultPageSize in 1..maxPageSize) { "default page size must be within 1..$maxPageSize" }

        val resolvedPage = page ?: DEFAULT_PAGE.toLong()
        val resolvedSize = pageSize ?: defaultPageSize.toLong()

        if (resolvedPage < 1 || resolvedPage > Int.MAX_VALUE) throw RequestValueException("page", "MUST_BE_POSITIVE")
        if (resolvedSize < 1 || resolvedSize > maxPageSize) {
            throw RequestValueException("pageSize", "MUST_BE_BETWEEN_1_AND_$maxPageSize")
        }

        return Window(resolvedPage.toInt(), resolvedSize.toInt())
    }

    /** `ceil(count / pageSize)`; 0 for an empty result (the `totalPage` of the response). */
    fun totalPages(count: Long, pageSize: Int): Long {
        require(pageSize >= 1) { "pageSize must be positive" }

        if (count <= 0) return 0

        return (count + pageSize - 1) / pageSize
    }

    /**
     * True when [page] is past the last page. An empty result still has page 1, so page 2 of nothing is beyond it.
     */
    fun isBeyondLast(page: Int, totalPages: Long): Boolean = page.toLong() > maxOf(1L, totalPages)
}
