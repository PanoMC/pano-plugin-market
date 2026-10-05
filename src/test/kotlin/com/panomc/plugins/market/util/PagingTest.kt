package com.panomc.plugins.market.util

import com.panomc.plugins.market.error.RequestValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagingTest {
    @Test
    fun `defaults are page 1 and 10 rows`() {
        val w = Paging.window(null, null)

        assertEquals(1, w.page)
        assertEquals(10, w.pageSize)
        assertEquals(0L, w.offset)
    }

    @Test
    fun `offset is page minus one times the page size`() {
        assertEquals(20L, Paging.window(3, 10).offset)
        assertEquals(24L, Paging.window(2, 24).offset)
        assertEquals(0L, Paging.window(1, 100).offset)
        assertEquals(99L * 100, Paging.window(100, 100).offset)
    }

    @Test
    fun `page size 100 is allowed and 101 is refused`() {
        assertEquals(100, Paging.window(1, 100).pageSize)

        val e = assertThrows(RequestValueException::class.java) { Paging.window(1, 101) }
        assertEquals("pageSize", e.field)
    }

    @Test
    fun `an endpoint can lower the maximum, the storefront takes 60`() {
        assertEquals(60, Paging.window(1, 60, defaultPageSize = 24, maxPageSize = 60).pageSize)
        assertEquals(24, Paging.window(null, null, defaultPageSize = 24, maxPageSize = 60).pageSize)

        assertThrows(RequestValueException::class.java) { Paging.window(1, 61, defaultPageSize = 24, maxPageSize = 60) }
    }

    @Test
    fun `zero, negative and absurd values are refused instead of clamped`() {
        assertEquals("page", assertThrows(RequestValueException::class.java) { Paging.window(0, 10) }.field)
        assertEquals("page", assertThrows(RequestValueException::class.java) { Paging.window(-3, 10) }.field)
        assertEquals("page", assertThrows(RequestValueException::class.java) { Paging.window(Long.MAX_VALUE, 10) }.field)
        assertEquals("pageSize", assertThrows(RequestValueException::class.java) { Paging.window(1, 0) }.field)
        assertEquals("pageSize", assertThrows(RequestValueException::class.java) { Paging.window(1, -1) }.field)
    }

    @Test
    fun `a default page size outside the maximum is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { Paging.window(null, null, defaultPageSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { Paging.window(null, null, defaultPageSize = 101) }
    }

    @Test
    fun `total pages is the ceiling and zero for nothing`() {
        assertEquals(0L, Paging.totalPages(0, 10))
        assertEquals(1L, Paging.totalPages(1, 10))
        assertEquals(1L, Paging.totalPages(10, 10))
        assertEquals(2L, Paging.totalPages(11, 10))
        assertEquals(3L, Paging.totalPages(21, 10))
        assertEquals(1L, Paging.totalPages(100, 100))
        assertEquals(2L, Paging.totalPages(101, 100))
        assertEquals(0L, Paging.totalPages(-5, 10))
        assertThrows(IllegalArgumentException::class.java) { Paging.totalPages(5, 0) }
    }

    @Test
    fun `beyond the last page means PAGE_NOT_FOUND, an empty result still has page 1`() {
        assertFalse(Paging.isBeyondLast(1, 0))
        assertTrue(Paging.isBeyondLast(2, 0))
        assertFalse(Paging.isBeyondLast(1, 1))
        assertTrue(Paging.isBeyondLast(2, 1))
        assertFalse(Paging.isBeyondLast(3, 3))
        assertTrue(Paging.isBeyondLast(4, 3))
    }

    @Test
    fun `a boundary walk over 25 rows with page size 10 visits 3 pages`() {
        val count = 25L
        val pages = Paging.totalPages(count, 10)
        val seen = (1..pages.toInt()).map { page ->
            val w = Paging.window(page.toLong(), 10)
            minOf(10L, count - w.offset)
        }

        assertEquals(listOf(10L, 10L, 5L), seen)
        assertTrue(Paging.isBeyondLast(4, pages))
    }
}
