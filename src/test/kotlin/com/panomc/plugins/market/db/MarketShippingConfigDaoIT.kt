package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketShippingCarrierDaoImpl
import com.panomc.plugins.market.db.impl.MarketShippingMethodDaoImpl
import com.panomc.plugins.market.db.impl.MarketShippingRateDaoImpl
import com.panomc.plugins.market.db.impl.MarketShippingZoneDaoImpl
import com.panomc.plugins.market.db.model.*
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_shipping_zone`, `_method`, `_rate` and `_carrier` (01 sections 11.1 to 11.4). */
class MarketShippingConfigDaoIT : MarketDaoITBase() {
    private val zones = MarketShippingZoneDaoImpl()
    private val methods = MarketShippingMethodDaoImpl()
    private val rates = MarketShippingRateDaoImpl()
    private val carriers = MarketShippingCarrierDaoImpl()

    // --- zone ---

    @Test
    fun `a zone round-trips every column and a minimal insert gets the defaults`(): Unit = runBlocking {
        val id = zones.add(
            MarketShippingZone(
                name = "EU", countries = """["DE","FR"]""", regions = """[{"country":"US","states":["CA"]}]""",
                postalPatterns = """["34*","1000-1999"]""", position = 3, status = "DISABLED", createdAt = 10, updatedAt = 20
            ), pool
        )
        val r = zones.getById(id, pool)!!
        assertEquals(
            listOf<Any?>("EU", """["DE","FR"]""", """[{"country":"US","states":["CA"]}]""", """["34*","1000-1999"]""", 3, "DISABLED", 10L, 20L),
            listOf(r.name, r.countries, r.regions, r.postalPatterns, r.position, r.status, r.createdAt, r.updatedAt)
        )
        sql("INSERT INTO `pano_market_shipping_zone` (`name`, `countries`, `createdAt`, `updatedAt`) VALUES ('min', '[\"*\"]', 1, 1)")
        val min = zones.getAll(pool).single { it.name == "min" }
        assertEquals(listOf<Any?>(0, "ACTIVE"), listOf(min.position, min.status))
        assertNull(min.regions)
        assertNull(min.postalPatterns)
        assertNull(zones.getById(9999, pool))
    }

    @Test
    fun `zones are listed in matching order, active filters on status, update and delete work`(): Unit = runBlocking {
        val c = zones.add(MarketShippingZone(name = "c", countries = """["*"]""", position = 5), pool)
        val a = zones.add(MarketShippingZone(name = "a", countries = """["DE"]""", position = 1), pool)
        val b = zones.add(MarketShippingZone(name = "b", countries = """["FR"]""", position = 1, status = "DISABLED"), pool)
        assertEquals(listOf(a, b, c), zones.getAll(pool).map { it.id })
        assertEquals(listOf(a, c), zones.getActive(pool).map { it.id })
        assertTrue(zones.update(MarketShippingZone(id = b, name = "b2", countries = """["FR","BE"]""", position = 9, status = "ACTIVE", updatedAt = 77), pool))
        val updated = zones.getById(b, pool)!!
        assertEquals(listOf<Any?>("b2", """["FR","BE"]""", 9, "ACTIVE", 77L), listOf(updated.name, updated.countries, updated.position, updated.status, updated.updatedAt))
        assertEquals(listOf(a, c, b), zones.getAll(pool).map { it.id })
        assertTrue(zones.delete(a, pool))
        assertFalse(zones.delete(a, pool))
        assertFalse(zones.update(MarketShippingZone(id = 9999, name = "x", countries = "[]"), pool))
    }

    // --- method ---

    private fun method(name: String = "Standard", position: Int = 0, status: String = "ACTIVE") = MarketShippingMethod(
        name = name, description = "d", providerId = "manual", serviceCode = "svc", rateSource = ShippingRateSource.CARRIER_WITH_FALLBACK,
        freeShippingThreshold = 5000, handlingFee = 150, vatPercent = 1900, minDeliveryDays = 2, maxDeliveryDays = 5, maxWeightGrams = 30000,
        carrierName = "DHL", trackingUrlTemplate = "https://t.example/{tracking}", settings = """{"a":1}""", position = position, status = status,
        deletedAt = null, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a method round-trips every column`(): Unit = runBlocking {
        val id = methods.add(method(), pool)
        val r = methods.getById(id, pool)!!
        assertEquals(
            listOf<Any?>(
                "Standard", "d", "manual", "svc", ShippingRateSource.CARRIER_WITH_FALLBACK, 5000L, 150L, 1900L, 2, 5, 30000, "DHL",
                "https://t.example/{tracking}", """{"a":1}""", 0, "ACTIVE", null, 10L, 20L
            ),
            listOf(
                r.name, r.description, r.providerId, r.serviceCode, r.rateSource, r.freeShippingThreshold, r.handlingFee, r.vatPercent,
                r.minDeliveryDays, r.maxDeliveryDays, r.maxWeightGrams, r.carrierName, r.trackingUrlTemplate, r.settings, r.position,
                r.status, r.deletedAt, r.createdAt, r.updatedAt
            )
        )
    }

    @Test
    fun `a minimal method gets the column defaults`(): Unit = runBlocking {
        sql("INSERT INTO `pano_market_shipping_method` (`name`, `createdAt`, `updatedAt`) VALUES ('m', 1, 1)")
        val r = methods.getAll(pool).single()
        assertEquals(listOf<Any?>("manual", ShippingRateSource.RULES, 0L, 0, "ACTIVE"), listOf(r.providerId, r.rateSource, r.handlingFee, r.position, r.status))
        assertNull(r.freeShippingThreshold)
        assertNull(r.vatPercent)
        assertNull(r.deletedAt)
    }

    @Test
    fun `soft delete hides a method from the active list once and update keeps the other columns`(): Unit = runBlocking {
        val a = methods.add(method("a", position = 2), pool)
        val b = methods.add(method("b", position = 1), pool)
        val c = methods.add(method("c", position = 3, status = "DISABLED"), pool)
        assertEquals(listOf(b, a, c), methods.getAll(pool).map { it.id })
        assertEquals(listOf(b, a), methods.getActive(pool).map { it.id })
        assertTrue(methods.softDelete(b, 500, pool))
        assertFalse(methods.softDelete(b, 600, pool), "second delete does nothing")
        assertEquals(500L, methods.getById(b, pool)!!.deletedAt)
        assertEquals(listOf(a), methods.getActive(pool).map { it.id })
        assertEquals(3, methods.getAll(pool).size, "soft-deleted rows stay")

        assertTrue(methods.update(MarketShippingMethod(id = a, name = "a2", handlingFee = 7, rateSource = ShippingRateSource.CARRIER, updatedAt = 99), pool))
        val updated = methods.getById(a, pool)!!
        assertEquals(listOf<Any?>("a2", 7L, ShippingRateSource.CARRIER, 99L, 10L), listOf(updated.name, updated.handlingFee, updated.rateSource, updated.updatedAt, updated.createdAt))
        assertEquals(500L, methods.getById(b, pool)!!.deletedAt, "update of a does not touch b")
        assertFalse(methods.softDelete(9999, 1, pool))
    }

    // --- rate ---

    private fun rate(method: Long, zone: Long, from: Long = 0, position: Int = 0, basis: ShippingRateBasis = ShippingRateBasis.WEIGHT) = MarketShippingRate(
        methodId = method, zoneId = zone, basis = basis, rangeFrom = from, rangeTo = from + 999, price = 490, perUnitPrice = 120, position = position,
        createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a rate round-trips every column, an open range stays null and the defaults apply`(): Unit = runBlocking {
        val id = rates.add(rate(1, 2, from = 1000, position = 4), pool)
        val r = rates.getById(id, pool)!!
        assertEquals(
            listOf<Any?>(1L, 2L, ShippingRateBasis.WEIGHT, 1000L, 1999L, 490L, 120L, 4, 10L, 20L),
            listOf(r.methodId, r.zoneId, r.basis, r.rangeFrom, r.rangeTo, r.price, r.perUnitPrice, r.position, r.createdAt, r.updatedAt)
        )
        sql("INSERT INTO `pano_market_shipping_rate` (`methodId`, `zoneId`, `basis`, `price`, `createdAt`, `updatedAt`) VALUES (5, 6, 'FLAT', 300, 1, 1)")
        val min = rates.getByMethodId(5, pool).single()
        assertEquals(listOf<Any?>(0L, 0L, 0, ShippingRateBasis.FLAT), listOf(min.rangeFrom, min.perUnitPrice, min.position, min.basis))
        assertNull(min.rangeTo)
    }

    @Test
    fun `rates are read per method and zone in rule order and deleted by method, zone or id`(): Unit = runBlocking {
        val second = rates.add(rate(1, 1, from = 1000, position = 2), pool)
        val first = rates.add(rate(1, 1, from = 0, position = 1), pool)
        val otherZone = rates.add(rate(1, 2, position = 1), pool)
        val otherMethod = rates.add(rate(2, 1, position = 1), pool)
        assertEquals(listOf(first, second), rates.getByMethodAndZone(1, 1, pool).map { it.id })
        assertEquals(listOf(first, second, otherZone), rates.getByMethodId(1, pool).map { it.id })
        assertTrue(rates.update(MarketShippingRate(id = first, methodId = 1, zoneId = 1, basis = ShippingRateBasis.AMOUNT, price = 1, updatedAt = 5), pool))
        assertEquals(listOf<Any?>(ShippingRateBasis.AMOUNT, 1L), rates.getById(first, pool)!!.let { listOf(it.basis, it.price) })
        assertTrue(rates.delete(second, pool))
        assertFalse(rates.delete(second, pool))
        assertEquals(1, rates.deleteByZoneId(2, pool))
        assertEquals(1, rates.deleteByMethodId(1, pool))
        assertEquals(listOf(otherMethod), rates.getByMethodId(2, pool).map { it.id })
        assertEquals(0, rates.deleteByMethodId(1, pool))
    }

    // --- carrier ---

    private val token = "a".repeat(40)

    private fun carrier(providerId: String = "manual", webhookToken: String = token) = MarketShippingCarrier(
        providerId = providerId, enabled = true, settings = "v1:abc", testMode = true, webhookToken = webhookToken,
        lastInboundAt = 5, lastError = "boom", lastErrorAt = 6, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a carrier round-trips every column`(): Unit = runBlocking {
        val id = carriers.add(carrier(), pool)!!
        val r = carriers.getById(id, pool)!!
        assertEquals(
            listOf<Any?>("manual", true, "v1:abc", true, token, 5L, "boom", 6L, 10L, 20L),
            listOf(r.providerId, r.enabled, r.settings, r.testMode, r.webhookToken, r.lastInboundAt, r.lastError, r.lastErrorAt, r.createdAt, r.updatedAt)
        )
        sql("INSERT INTO `pano_market_shipping_carrier` (`providerId`, `webhookToken`, `createdAt`, `updatedAt`) VALUES ('p2', '${"b".repeat(40)}', 1, 1)")
        val min = carriers.getByProviderId("p2", pool)!!
        assertFalse(min.enabled)
        assertFalse(min.testMode)
        assertNull(min.settings)
        assertNull(min.lastError)
    }

    @Test
    fun `providerId is unique and a duplicate leaves the first row intact`(): Unit = runBlocking {
        assertNotNull(carriers.add(carrier(), pool))
        assertNull(carriers.add(MarketShippingCarrier(providerId = "manual", webhookToken = "c".repeat(40), settings = "other"), pool))
        assertEquals("v1:abc", carriers.getByProviderId("manual", pool)!!.settings)
        assertNotNull(carriers.add(carrier("other", "d".repeat(40)), pool))
        val failure = runCatching {
            sql("INSERT INTO `pano_market_shipping_carrier` (`providerId`, `webhookToken`, `createdAt`, `updatedAt`) VALUES ('manual', 'x', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure, "raw duplicate must fail")
        val results = Race.run(4) { carriers.add(MarketShippingCarrier(providerId = "raced", webhookToken = "e".repeat(40)), pool) }
        assertEquals(1, results.count { it.getOrThrow() != null })
        assertEquals(1L, count("market_shipping_carrier", "`providerId` = 'raced'"))
    }

    @Test
    fun `lookup by token, enabled list, inbound stamp, error recording and update`(): Unit = runBlocking {
        val enabled = carriers.add(carrier("a", "1".repeat(40)), pool)!!
        val disabled = carriers.add(MarketShippingCarrier(providerId = "b", webhookToken = "2".repeat(40)), pool)!!
        assertEquals(enabled, carriers.getByWebhookToken("1".repeat(40), pool)!!.id)
        assertNull(carriers.getByWebhookToken("9".repeat(40), pool))
        assertEquals(listOf(enabled, disabled), carriers.getAll(pool).map { it.id })
        assertEquals(listOf(enabled), carriers.getEnabled(pool).map { it.id })

        assertTrue(carriers.recordInbound("b", 1234, pool))
        assertEquals(1234L, carriers.getById(disabled, pool)!!.lastInboundAt)
        assertFalse(carriers.recordInbound("missing", 1, pool))

        assertTrue(carriers.recordError(disabled, "network", 2000, pool))
        val withError = carriers.getById(disabled, pool)!!
        assertEquals(listOf<Any?>("network", 2000L), listOf(withError.lastError, withError.lastErrorAt))
        assertTrue(carriers.recordError(disabled, null, 3000, pool))
        val cleared = carriers.getById(disabled, pool)!!
        assertNull(cleared.lastError)
        assertNull(cleared.lastErrorAt)

        assertTrue(carriers.update(MarketShippingCarrier(id = disabled, providerId = "b", enabled = true, settings = "v1:new", webhookToken = "3".repeat(40), updatedAt = 9), pool))
        val updated = carriers.getById(disabled, pool)!!
        assertEquals(listOf<Any?>("b", true, "v1:new", "3".repeat(40), 9L), listOf(updated.providerId, updated.enabled, updated.settings, updated.webhookToken, updated.updatedAt))
        assertEquals(listOf(enabled, disabled), carriers.getEnabled(pool).map { it.id })
    }
}
