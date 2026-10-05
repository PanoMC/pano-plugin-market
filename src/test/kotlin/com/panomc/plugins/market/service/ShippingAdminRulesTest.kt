package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.model.ShippingRateBasis
import com.panomc.plugins.market.db.model.ShippingRateSource
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Zone, method and rate-set validation of 10 sections 4.2 and 5.1 (pure, MK-131). */
class ShippingAdminRulesTest {
    private val usable = ShippingAdminRules.MethodContext(providerUsable = true, canQuote = false)

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    private fun zone(vararg pairs: Pair<String, Any?>) = ShippingAdminRules.parseZone(obj("name" to "Z", "countries" to JsonArray().add("TR"), *pairs), null)

    private fun method(vararg pairs: Pair<String, Any?>) = ShippingAdminRules.parseMethod(obj("name" to "M", *pairs), null, usable)

    private fun rate(zoneId: Long, basis: String, from: Any? = null, to: Any? = null, price: Any? = 1, per: Any? = null) =
        obj("zoneId" to zoneId, "basis" to basis, "rangeFrom" to from, "rangeTo" to to, "price" to price, "perUnitPrice" to per)

    private fun rates(vararg rows: JsonObject) = method("rates" to JsonArray(rows.toList()))

    // ---- zones

    @Test
    fun `a valid zone is accepted and normalised`() {
        val p = ShippingAdminRules.parseZone(
            obj(
                "name" to "  Turkey ", "countries" to JsonArray().add("tr").add("TR").add("DE"),
                "regions" to JsonArray().add(obj("country" to "tr", "states" to JsonArray().add(" İstanbul "))),
                "postalPatterns" to JsonArray().add("34*").add("1000-1999").add("SW1A1"), "status" to "INACTIVE"
            ),
            null
        )

        assertTrue(p.errors.isEmpty(), p.errors.toString())
        assertEquals("Turkey", p.draft.name)
        assertEquals(listOf("TR", "DE"), p.draft.countries.list)
        assertEquals("INACTIVE", p.draft.status)
        assertEquals(3, p.draft.postalPatterns!!.size())
    }

    @Test
    fun `name and countries are required`() {
        val p = ShippingAdminRules.parseZone(obj(), null)

        assertEquals("REQUIRED", p.errors["name"])
        assertEquals("REQUIRED", p.errors["countries"])
        assertEquals("TOO_LONG", ShippingAdminRules.parseZone(obj("name" to "x".repeat(129), "countries" to JsonArray().add("TR")), null).errors["name"])
        assertEquals("REQUIRED", ShippingAdminRules.parseZone(obj("name" to "Z", "countries" to JsonArray()), null).errors["countries"])
    }

    @Test
    fun `a star is the only country when present and unknown countries are refused`() {
        assertEquals("WILDCARD_EXCLUSIVE", zone("countries" to JsonArray().add("*").add("TR")).errors["countries"])
        assertEquals("INVALID_COUNTRY", zone("countries" to JsonArray().add("XX")).errors["countries"])
        assertTrue(zone("countries" to JsonArray().add("*")).errors.isEmpty())
    }

    @Test
    fun `a region country must be in countries and is not allowed with a star`() {
        val notIn = zone("regions" to JsonArray().add(obj("country" to "DE", "states" to JsonArray().add("Bayern"))))
        val withStar = zone("countries" to JsonArray().add("*"), "regions" to JsonArray().add(obj("country" to "TR", "states" to JsonArray().add("Izmir"))))
        val noStates = zone("regions" to JsonArray().add(obj("country" to "TR", "states" to JsonArray())))

        assertEquals("INVALID_REGION_COUNTRY", notIn.errors["regions[0].country"])
        assertEquals("INVALID_REGION_COUNTRY", withStar.errors["regions[0].country"])
        assertEquals("REQUIRED", noStates.errors["regions[0].states"])
    }

    @Test
    fun `postal patterns follow the three forms of 4_2`() {
        val ok = zone("postalPatterns" to JsonArray().add("34*").add("0100-0199").add("AB12"))
        val bad = zone("postalPatterns" to JsonArray().add("34*5").add("1000-199").add("200-100").add("a b").add(""))

        assertTrue(ok.errors.isEmpty(), ok.errors.toString())
        assertEquals("INVALID_PATTERN", bad.errors["postalPatterns[0]"])
        assertEquals("RANGE_LENGTH", bad.errors["postalPatterns[1]"])
        assertEquals("RANGE_ORDER", bad.errors["postalPatterns[2]"])
        assertEquals("INVALID_PATTERN", bad.errors["postalPatterns[3]"])
        assertEquals("INVALID_PATTERN", bad.errors["postalPatterns[4]"])
        assertEquals("TOO_MANY", zone("postalPatterns" to JsonArray((1..201).map { "A$it*" })).errors["postalPatterns"])
    }

    @Test
    fun `status is ACTIVE or INACTIVE`() {
        assertEquals("INVALID", zone("status" to "PAUSED").errors["status"])
    }

    // ---- methods

    @Test
    fun `a valid method is stored with x100 money and basis point vat`() {
        val p = method(
            "description" to "Fast", "freeShippingThreshold" to 250.5, "handlingFee" to 1.25, "vatPercent" to 8.5,
            "minDeliveryDays" to 1, "maxDeliveryDays" to 3, "maxWeightGrams" to 30_000, "carrierName" to "UPS",
            "trackingUrlTemplate" to "https://track.example/{tracking}"
        )

        assertTrue(p.errors.isEmpty(), p.errors.toString())
        assertEquals(25_050L, p.draft.freeShippingThreshold)
        assertEquals(125L, p.draft.handlingFee)
        assertEquals(850L, p.draft.vatPercent)
        assertEquals(ShippingRateSource.RULES, p.draft.rateSource)
        assertEquals("manual", p.draft.providerId)
        assertNull(p.rates)
    }

    @Test
    fun `every method field has its bound`() {
        val p = method(
            "name" to "", "description" to "d".repeat(513), "freeShippingThreshold" to 0, "handlingFee" to -1, "vatPercent" to 100.01,
            "minDeliveryDays" to 5, "maxDeliveryDays" to 2, "maxWeightGrams" to 0, "carrierName" to "c".repeat(129)
        )

        assertEquals("REQUIRED", p.errors["name"])
        assertEquals("TOO_LONG", p.errors["description"])
        assertEquals("MUST_BE_POSITIVE", p.errors["freeShippingThreshold"])
        assertEquals("NEGATIVE", p.errors["handlingFee"])
        assertEquals("OUT_OF_RANGE", p.errors["vatPercent"])
        assertEquals("MIN_GREATER_THAN_MAX", p.errors["minDeliveryDays"])
        assertEquals("OUT_OF_RANGE", p.errors["maxWeightGrams"])
        assertEquals("TOO_LONG", p.errors["carrierName"])
        assertEquals("OUT_OF_RANGE", method("maxDeliveryDays" to 366).errors["maxDeliveryDays"])
        assertEquals("OUT_OF_RANGE", method("maxWeightGrams" to 2_000_000_001L).errors["maxWeightGrams"])
        assertTrue(method("maxWeightGrams" to 2_000_000_000L, "vatPercent" to 100, "minDeliveryDays" to 0).errors.isEmpty())
    }

    @Test
    fun `money has at most two decimals`() {
        assertEquals("INVALID", method("handlingFee" to 1.005).errors["handlingFee"])
        assertEquals("INVALID", method("handlingFee" to "1").errors["handlingFee"])
        assertEquals(1L, ShippingAdminRules.money(0.01))
        assertEquals(1999L, ShippingAdminRules.money(19.99))
        assertNull(ShippingAdminRules.money(2_000_000_000))
    }

    @Test
    fun `the tracking template needs the placeholder and an http scheme`() {
        assertEquals("INVALID_TEMPLATE", method("trackingUrlTemplate" to "https://track.example/").errors["trackingUrlTemplate"])
        assertEquals("INVALID_TEMPLATE", method("trackingUrlTemplate" to "ftp://track.example/{tracking}").errors["trackingUrlTemplate"])
        assertEquals("INVALID_TEMPLATE", method("trackingUrlTemplate" to "javascript:alert({tracking})").errors["trackingUrlTemplate"])
        assertEquals("TOO_LONG", method("trackingUrlTemplate" to "https://t.example/" + "a".repeat(500) + "{tracking}").errors["trackingUrlTemplate"])
        assertTrue(method("trackingUrlTemplate" to "http://track.example/?n={tracking}").errors.isEmpty())
    }

    @Test
    fun `a carrier rate source needs a provider that can quote unless it is unavailable`() {
        val cannot = ShippingAdminRules.parseMethod(obj("name" to "M", "providerId" to "ups", "rateSource" to "CARRIER"), null, usable)
        val can = ShippingAdminRules.parseMethod(obj("name" to "M", "providerId" to "ups", "rateSource" to "CARRIER_WITH_FALLBACK"), null, ShippingAdminRules.MethodContext(true, true))
        val gone = ShippingAdminRules.parseMethod(obj("name" to "M", "providerId" to "ups", "rateSource" to "CARRIER"), null, ShippingAdminRules.MethodContext(false, false))

        assertEquals("RATE_QUOTE_NOT_SUPPORTED", cannot.errors["rateSource"])
        assertTrue(can.errors.isEmpty())
        assertTrue(gone.errors.isEmpty(), "an unavailable provider is accepted as stored")
        assertEquals("INVALID", method("rateSource" to "MAGIC").errors["rateSource"])
    }

    @Test
    fun `serviceCode is dropped for the manual provider`() {
        assertNull(method("serviceCode" to "EXPRESS").draft.serviceCode)
        assertEquals("EXPRESS", ShippingAdminRules.parseMethod(obj("name" to "M", "providerId" to "ups", "serviceCode" to "EXPRESS"), null, usable).draft.serviceCode)
    }

    // ---- rate sets

    @Test
    fun `a clean rate set is parsed with the right units`() {
        val p = rates(
            rate(1, "WEIGHT", 0, 999, 5.0), rate(1, "WEIGHT", 1000, null, 8.0, per = 1.5),
            rate(2, "AMOUNT", 0, 49.99, 4.0), rate(2, "AMOUNT", 50, null, 0), rate(3, "QUANTITY", 1, 3, 2, per = 0.5), rate(4, "FLAT", price = 3)
        )

        assertTrue(p.errors.isEmpty(), p.errors.toString())
        val r = p.rates!!
        assertEquals(500L, r[0].price)
        assertEquals(1000L, r[1].rangeFrom)
        assertNull(r[1].rangeTo)
        assertEquals(150L, r[1].perUnitPrice)
        assertEquals(4_999L, r[2].rangeTo)
        assertEquals(5_000L, r[3].rangeFrom)
        assertEquals(50L, r[4].perUnitPrice)
        assertEquals(0L, r[5].rangeFrom)
        assertNull(r[5].rangeTo)
    }

    @Test
    fun `overlapping ranges of one basis are RATE_OVERLAP and the edges are inclusive`() {
        assertEquals("RATE_OVERLAP", rates(rate(1, "WEIGHT", 0, 1000), rate(1, "WEIGHT", 1000, 2000)).errors["rates[1]"])
        assertEquals("RATE_OVERLAP", rates(rate(1, "WEIGHT", 500, 900), rate(1, "WEIGHT", 0, 600)).errors["rates[1]"])
        assertTrue(rates(rate(1, "WEIGHT", 0, 999), rate(1, "WEIGHT", 1000, 2000)).errors.isEmpty())
        assertTrue(rates(rate(1, "WEIGHT", 0, 1000), rate(2, "WEIGHT", 0, 1000)).errors.isEmpty(), "another zone")
        assertTrue(rates(rate(1, "WEIGHT", 0, 1000), rate(1, "AMOUNT", 0, 10)).errors.isEmpty(), "another basis")
    }

    @Test
    fun `an open ended row followed by a row of its basis overlaps`() {
        assertEquals("RATE_OVERLAP", rates(rate(1, "WEIGHT", 0, null), rate(1, "WEIGHT", 5000, 6000)).errors["rates[1]"])
        assertEquals("RATE_OVERLAP", rates(rate(1, "QUANTITY", 1, null), rate(1, "QUANTITY", 100, null)).errors["rates[1]"])
        assertTrue(rates(rate(1, "WEIGHT", 0, 100), rate(1, "WEIGHT", 101, null)).errors.isEmpty(), "open row last")
    }

    @Test
    fun `no row may follow a FLAT row of the same zone`() {
        assertEquals("RATE_UNREACHABLE", rates(rate(1, "FLAT"), rate(1, "FLAT")).errors["rates[1]"])
        assertEquals("RATE_UNREACHABLE", rates(rate(1, "FLAT"), rate(1, "WEIGHT", 0, 10)).errors["rates[1]"])
        assertTrue(rates(rate(1, "WEIGHT", 0, 10), rate(1, "FLAT")).errors.isEmpty(), "a FLAT row last is the catch-all")
        assertTrue(rates(rate(1, "FLAT"), rate(2, "FLAT")).errors.isEmpty(), "one FLAT row per zone")
    }

    @Test
    fun `rate row fields have their bounds`() {
        assertEquals("NEGATIVE", rates(rate(1, "WEIGHT", -1)).errors["rates[0].rangeFrom"])
        assertEquals("BEFORE_FROM", rates(rate(1, "WEIGHT", 10, 5)).errors["rates[0].rangeTo"])
        assertEquals("INVALID", rates(rate(1, "WEIGHT", 0.5)).errors["rates[0].rangeFrom"])
        assertEquals("INVALID", rates(rate(1, "NOPE")).errors["rates[0].basis"])
        assertEquals("INVALID", rates(rate(0, "FLAT")).errors["rates[0].zoneId"])
        assertEquals("INVALID", rates(rate(1, "FLAT", price = null)).errors["rates[0].price"])
        assertEquals("OUT_OF_RANGE", rates(rate(1, "FLAT", price = -1)).errors["rates[0].price"])
        assertEquals("MUST_BE_ZERO", rates(rate(1, "AMOUNT", 0, 10, 1, per = 1)).errors["rates[0].perUnitPrice"])
        assertEquals("MUST_BE_ZERO", rates(rate(1, "FLAT", price = 1, per = 1)).errors["rates[0].perUnitPrice"])
        assertEquals("OUT_OF_RANGE", rates(rate(1, "WEIGHT", 0, 10, 1, per = -1)).errors["rates[0].perUnitPrice"])
        assertEquals("INVALID", rates(rate(1, "AMOUNT", 0, 10.001)).errors["rates[0].rangeTo"])
        assertTrue(rates(rate(1, "WEIGHT", 0, 10, 1, per = 2)).errors.isEmpty())
    }

    @Test
    fun `a FLAT row ignores its range`() {
        val p = rates(rate(1, "FLAT", 7, 9, 2))

        assertTrue(p.errors.isEmpty())
        assertEquals(0L, p.rates!![0].rangeFrom)
        assertNull(p.rates!![0].rangeTo)
    }

    @Test
    fun `at most 200 rows and the zone ids are checked against the existing zones`() {
        val many = JsonArray((0 until 201).map { rate(1, "QUANTITY", it.toLong() * 2, it.toLong() * 2 + 1) })

        assertEquals("TOO_MANY", method("rates" to many).errors["rates"])
        assertEquals(200, method("rates" to JsonArray((0 until 200).map { rate(1, "QUANTITY", it.toLong() * 2, it.toLong() * 2 + 1) })).rates!!.size)

        val p = rates(rate(1, "FLAT"), rate(9, "FLAT"))
        assertEquals(mapOf("rates[1].zoneId" to "NOT_FOUND"), p.rateZoneErrors(setOf(1L, 2L)))
        assertNotNull(p.rates)
    }

    @Test
    fun `an update keeps the stored values and the rates when the form omits them`() {
        val base = com.panomc.plugins.market.db.model.MarketShippingMethod(
            id = 5, name = "Old", providerId = "ups", rateSource = ShippingRateSource.CARRIER, handlingFee = 300, vatPercent = 1000, status = "INACTIVE"
        )
        val p = ShippingAdminRules.parseMethod(obj("name" to "New"), base, ShippingAdminRules.MethodContext(true, true))

        assertTrue(p.errors.isEmpty(), p.errors.toString())
        assertEquals("New", p.draft.name)
        assertEquals("ups", p.draft.providerId)
        assertEquals(300L, p.draft.handlingFee)
        assertEquals(1000L, p.draft.vatPercent)
        assertEquals("INACTIVE", p.draft.status)
        assertNull(p.rates, "no rates key keeps the stored set")
        assertEquals(ShippingRateBasis.FLAT, ShippingAdminRules.parseMethod(obj("rates" to JsonArray().add(rate(1, "FLAT"))), base, usable).rates!![0].basis)
        assertEquals(emptyList<Any>(), ShippingAdminRules.parseMethod(obj("rates" to null), base, usable).rates)
    }

    @Test
    fun `install tokens are 40 hex characters and differ`() {
        val a = ShippingAdminRules.newInstallToken()

        assertTrue(Regex("^[0-9a-f]{40}$").matches(a))
        assertTrue(a != ShippingAdminRules.newInstallToken())
    }
}
