package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.shipping.Parcel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `ParcelBuilder`, `ShippableLines` and `QuoteCacheKey` (10 sections 2.2, 5.4, 5.5; tests 24 to 26 of section 16). */
class ParcelBuilderTest {
    private fun line(weight: Int, qty: Int = 1, l: Int? = null, w: Int? = null, h: Int? = null, value: Long = 1000, id: Long = 1) =
        ShippableLine(id, id, 0, "p$id", "sku$id", qty, weight, l, w, h, value)

    @Test
    fun `weight is the sum of unit weight times quantity`() {
        val parcel = ParcelBuilder.forCheckout(listOf(line(300, 2), line(150, 3, id = 2)))

        assertEquals(300 * 2 + 150 * 3, parcel.weightGrams)
    }

    @Test
    fun `an empty cart still weighs one gram`() {
        assertEquals(1, ParcelBuilder.forCheckout(emptyList()).weightGrams)
    }

    @Test
    fun `dimensions come from the line with the largest volume among lines that have all three`() {
        val small = line(100, l = 100, w = 100, h = 100, id = 1)                // 1 000 000
        val big = line(100, l = 300, w = 200, h = 50, id = 2)                   // 3 000 000
        val partial = line(100, l = 5000, w = 5000, h = null, id = 3)           // ignored: no height

        val parcel = ParcelBuilder.forCheckout(listOf(small, partial, big))

        assertEquals(300, parcel.lengthMm)
        assertEquals(200, parcel.widthMm)
        assertEquals(50, parcel.heightMm)
    }

    @Test
    fun `equal volumes take the first line and the quantity does not enlarge the parcel`() {
        val a = line(100, 5, 10, 20, 30, id = 1)
        val b = line(100, 1, 30, 20, 10, id = 2)

        val parcel = ParcelBuilder.forCheckout(listOf(a, b))

        assertEquals(10, parcel.lengthMm)
        assertEquals(30, parcel.heightMm)
    }

    @Test
    fun `no dimensions fall back to the provider default then the manual default then none`() {
        val lines = listOf(line(500))
        val provider = ParcelSize(300, 200, 100)
        val manual = ParcelSize(400, 300, 200)

        assertEquals(300, ParcelBuilder.forCheckout(lines, provider, manual).lengthMm)
        assertEquals(400, ParcelBuilder.forCheckout(lines, null, manual).lengthMm)

        val none = ParcelBuilder.forCheckout(lines, null, null)
        assertNull(none.lengthMm)
        assertNull(none.widthMm)
        assertNull(none.heightMm)
        assertFalse(ParcelBuilder.hasDimensions(none))
        assertTrue(ParcelBuilder.hasDimensions(ParcelBuilder.forCheckout(lines, provider)))
    }

    @Test
    fun `line dimensions win over a default`() {
        val parcel = ParcelBuilder.forCheckout(listOf(line(10, l = 1, w = 2, h = 3)), ParcelSize(300, 200, 100), null)

        assertEquals(1, parcel.lengthMm)
    }

    @Test
    fun `a cart above two million kilograms is too heavy and has no parcel`() {
        val heavy = listOf(line(1_000_000, 2001))      // 2 001 000 000 g

        assertTrue(ShippableLines.isTooHeavy(heavy))
        assertFalse(ShippableLines.isTooHeavy(listOf(line(1_000_000, 2000))))
        assertThrows(IllegalArgumentException::class.java) { ParcelBuilder.forCheckout(heavy) }
    }

    @Test
    fun `weight arithmetic is done in long and does not wrap at the int limit`() {
        val lines = List(10) { line(1_000_000, Int.MAX_VALUE, id = it + 1L) }

        assertEquals(10L * 1_000_000 * Int.MAX_VALUE, ShippableLines.weightGrams(lines))
        assertTrue(ShippableLines.isTooHeavy(lines))
    }

    @Test
    fun `totals of a cart`() {
        val t = ShippableLines.totals(listOf(line(200, 2, value = 3000, id = 1), line(100, 1, value = 500, id = 2)))

        assertTrue(t.requiresShipping)
        assertEquals(3500, t.shippableValue)
        assertEquals(3, t.shippableUnits)
        assertEquals(500, t.weightGrams)
        assertFalse(ShippableLines.totals(emptyList()).requiresShipping)
    }

    @Test
    fun `a bundle total is split over the child units and the remainder goes to the first child line`() {
        // 100.00 over two children with 1 + 2 units: 33.33 per unit -> 33.33, 66.66, remainder 0.01 on the first
        val split = ShippableLines.splitBundleValue(10_000, listOf(1, 2))

        assertEquals(listOf(3334L, 6666L), split)
        assertEquals(10_000, split.sum())

        // half up per unit can make the remainder negative: 2.00 / 3 = 0.67 -> 3 x 0.67 = 2.01
        val negative = ShippableLines.splitBundleValue(200, listOf(1, 2))
        assertEquals(listOf(66L, 134L), negative)
        assertEquals(200, negative.sum())

        assertEquals(listOf(0L, 0L), ShippableLines.splitBundleValue(0, listOf(1, 1)))
        assertEquals(listOf(500L), ShippableLines.splitBundleValue(500, listOf(3)))
        assertThrows(IllegalArgumentException::class.java) { ShippableLines.splitBundleValue(100, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ShippableLines.splitBundleValue(100, listOf(0, 1)) }
    }

    @Test
    fun `a bundle split always adds up and stays within one unit step of an equal share`() {
        var seed = 77L

        repeat(1_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val total = Math.floorMod(seed ushr 10, 1_000_000L)
            val qs = List(1 + Math.floorMod(seed ushr 50, 4L).toInt()) { 1 + Math.floorMod(seed ushr (20 + it * 5), 5L).toInt() }
            val units = qs.sum()
            val split = ShippableLines.splitBundleValue(total, qs)

            assertEquals(total, split.sum())
            split.forEachIndexed { i, v -> assertTrue(v >= 0, "line $i of $total over $qs") }
            assertTrue(split.drop(1).withIndex().all { (i, v) -> v % qs[i + 1] == 0L })
            assertTrue(Math.abs(split[0] - total * qs[0] / units) <= units + 1)
        }
    }

    @Test
    fun `line validation`() {
        assertThrows(IllegalArgumentException::class.java) { line(0) }
        assertThrows(IllegalArgumentException::class.java) { line(10, 0) }
        assertThrows(IllegalArgumentException::class.java) { line(10, value = -1) }
    }

    // ---- QuoteCacheKey ----

    private fun addr(country: String = "TR", state: String? = null, city: String? = "İstanbul", district: String? = "Kadıköy", postal: String? = "34710") =
        Address(null, null, null, null, null, country, state, city, district, null, null, null, postal, null, null, null)

    private val sender = addr("TR", null, "Ankara", "Çankaya", "06100")

    private fun key(
        provider: String = "geliver", test: Boolean = false, updated: Long = 1, service: String? = null, to: Address = addr(),
        parcels: List<Parcel> = listOf(Parcel(1500, 300, 200, 100)), value: Long = 50_000, currency: String = "TRY", from: Address? = sender
    ) = QuoteCacheKey.of(provider, test, updated, service, from, to, parcels, value, currency)

    @Test
    fun `cache key is stable under case spacing and accents of the address`() {
        val base = key()

        assertEquals(64, base.length)
        assertEquals(base, key(to = addr("tr", null, "  ISTANBUL ", "kadikoy", "34 710")))
        assertEquals(base, key(to = addr("TR", null, "istanbul", "KADIKOY", "34-710")))
        assertEquals(base, key())
    }

    @Test
    fun `cache key changes with every price relevant part`() {
        val base = key()
        val others = listOf(
            key(parcels = listOf(Parcel(1501, 300, 200, 100))),
            key(parcels = listOf(Parcel(1500, 301, 200, 100))),
            key(parcels = listOf(Parcel(1500, null, null, null))),
            key(parcels = listOf(Parcel(1500, 300, 200, 100), Parcel(1, null, null, null))),
            key(to = addr(postal = "34711")),
            key(to = addr(city = "Ankara")),
            key(to = addr(district = "Üsküdar")),
            key(to = addr(state = "X")),
            key(to = addr(country = "DE")),
            key(service = "express"),
            key(test = true),
            key(updated = 2),
            key(provider = "other"),
            key(value = 50_001),
            key(currency = "EUR"),
            key(from = addr("TR", null, "İzmir", "Konak", "35000")),
            key(from = null)
        )

        others.forEach { assertNotEquals(base, it) }
        assertEquals(others.size, others.toSet().size)
    }

    @Test
    fun `a missing service code and the star are the same and fields cannot be shifted into each other`() {
        assertEquals(key(service = null), key(service = "*"))
        assertNotEquals(key(to = addr(city = "ab", district = "c")), key(to = addr(city = "a", district = "bc")))
    }
}
