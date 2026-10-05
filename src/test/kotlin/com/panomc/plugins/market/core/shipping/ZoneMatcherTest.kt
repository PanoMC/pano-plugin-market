package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.spi.common.Address
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `ZoneMatcher` (10 section 4.1, tests 7 to 12 of section 16). */
class ZoneMatcherTest {
    private fun to(country: String?, state: String? = null, city: String? = null, postal: String? = null) =
        Address(null, null, null, null, null, country, state, city, null, null, null, null, postal, null, null, null)

    private fun zone(id: Long, countries: List<String>, position: Int = id.toInt(), regions: List<ZoneRegion>? = null, patterns: List<String>? = null, active: Boolean = true) =
        Zone(id, countries, regions, patterns, position, active)

    @Test
    fun `first match by position and the wildcard zone below a turkey zone only catches the rest`() {
        val zones = listOf(zone(2, listOf("*"), 5), zone(1, listOf("TR"), 1))

        assertEquals(1, ZoneMatcher.match(zones, to("TR"))!!.id)
        assertEquals(2, ZoneMatcher.match(zones, to("DE"))!!.id)
        assertEquals(1, ZoneMatcher.match(zones.reversed(), to("TR"))!!.id)
    }

    @Test
    fun `equal positions are ordered by id`() {
        val zones = listOf(zone(9, listOf("DE"), 0), zone(4, listOf("DE"), 0))

        assertEquals(4, ZoneMatcher.match(zones, to("DE"))!!.id)
    }

    @Test
    fun `region match uses the city for turkey and ignores case and accents`() {
        val z = zone(1, listOf("TR"), regions = listOf(ZoneRegion("TR", listOf("İstanbul", "Ankara"))))

        listOf("İSTANBUL", "istanbul", "Istanbul", "  istanbul ", "ISTANBUL").forEach {
            assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", city = it))!!.id, it)
        }
        assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", city = "ankara"))!!.id)
        assertNull(ZoneMatcher.match(listOf(z), to("TR", city = "İzmir")))
        assertNull(ZoneMatcher.match(listOf(z), to("TR")))

        val state = zone(2, listOf("US"), regions = listOf(ZoneRegion("US", listOf("New  York"))))
        assertEquals(2, ZoneMatcher.match(listOf(state), to("US", state = "new york", city = "Somewhere"))!!.id)
        assertNull(ZoneMatcher.match(listOf(state), to("US", state = "Texas", city = "New York")))   // state wins over city
    }

    @Test
    fun `a region entry of another country does not restrict and empty states mean no restriction`() {
        val other = zone(1, listOf("TR", "DE"), regions = listOf(ZoneRegion("TR", listOf("Ankara"))))

        assertEquals(1, ZoneMatcher.match(listOf(other), to("DE", city = "Berlin"))!!.id)
        assertNull(ZoneMatcher.match(listOf(other), to("TR", city = "İzmir")))

        val empty = zone(2, listOf("TR"), regions = listOf(ZoneRegion("TR", emptyList())))
        assertEquals(2, ZoneMatcher.match(listOf(empty), to("TR", city = "İzmir"))!!.id)
    }

    @Test
    fun `postal prefix range and exact patterns`() {
        val z = zone(1, listOf("TR"), patterns = listOf("34*", "1000-1999", "ab12"))

        assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", postal = "34710"))!!.id)
        assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", postal = "1500"))!!.id)
        assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", postal = "1000"))!!.id)
        assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", postal = "1999"))!!.id)
        assertNull(ZoneMatcher.match(listOf(z), to("TR", postal = "2000")))
        assertNull(ZoneMatcher.match(listOf(z), to("TR", postal = "999")))
        assertNull(ZoneMatcher.match(listOf(z), to("TR", postal = "01500")))       // length differs
        assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", postal = "AB 12"))!!.id)   // exact, case and space insensitive
        assertNull(ZoneMatcher.match(listOf(z), to("TR", postal = "AB123")))
        assertEquals(1, ZoneMatcher.match(listOf(z), to("TR", postal = "34-710"))!!.id)
    }

    @Test
    fun `prefix patterns are case insensitive and a range needs digits`() {
        assertTrue(ZoneMatcher.matches("sw*", "SW1A1AA"))
        assertFalse(ZoneMatcher.matches("SW*", "NW1"))
        assertFalse(ZoneMatcher.matches("1000-1999", "1A00"))
        assertFalse(ZoneMatcher.matches("A000-A999", "A500"))
        assertFalse(ZoneMatcher.matches("1-2-3", "2"))
        assertTrue(ZoneMatcher.matches("0100-0200", "0150"))
        assertFalse(ZoneMatcher.matches("0100-0200", "150"))
        assertTrue(ZoneMatcher.matches("34", "34"))
    }

    @Test
    fun `a zone with patterns and an address without a postal code does not match`() {
        val z = zone(1, listOf("TR"), patterns = listOf("34*"))

        assertNull(ZoneMatcher.match(listOf(z), to("TR")))
        assertNull(ZoneMatcher.match(listOf(z), to("TR", postal = "")))
        assertNull(ZoneMatcher.match(listOf(z), to("TR", postal = " - ")))
    }

    @Test
    fun `no zone gives null and inactive zones are ignored`() {
        assertNull(ZoneMatcher.match(emptyList(), to("TR")))
        assertNull(ZoneMatcher.match(listOf(zone(1, listOf("DE"))), to("TR")))
        assertNull(ZoneMatcher.match(listOf(zone(1, listOf("*"), active = false)), to("TR")))
        assertEquals(2, ZoneMatcher.match(listOf(zone(1, listOf("*"), active = false), zone(2, listOf("*"))), to("TR"))!!.id)
        assertNull(ZoneMatcher.match(listOf(zone(1, listOf("*"))), to(null)))
        assertEquals(1, ZoneMatcher.match(listOf(zone(1, listOf("TR"))), to(" tr "))!!.id)
    }

    @Test
    fun `a zone above the catch-all with no rates blocks a destination by being matched first`() {
        val zones = listOf(zone(1, listOf("RU"), 0), zone(2, listOf("*"), 1))

        assertEquals(1, ZoneMatcher.match(zones, to("RU"))!!.id)
        assertEquals(2, ZoneMatcher.match(zones, to("FR"))!!.id)
    }

    @Test
    fun `shadowedBy reports a zone below an unrestricted wildcard or covering zone`() {
        val everywhere = zone(1, listOf("*"), 0)
        val turkey = zone(2, listOf("TR"), 1)
        val zones = listOf(everywhere, turkey)

        assertEquals(1L, ZoneMatcher.shadowedBy(zones, turkey))
        assertNull(ZoneMatcher.shadowedBy(zones, everywhere))

        val tr = zone(3, listOf("TR", "DE"), 0)
        val de = zone(4, listOf("DE"), 1)
        val fr = zone(5, listOf("FR"), 2)
        assertEquals(3L, ZoneMatcher.shadowedBy(listOf(tr, de, fr), de))
        assertNull(ZoneMatcher.shadowedBy(listOf(tr, de, fr), fr))
        assertNull(ZoneMatcher.shadowedBy(listOf(de, tr), tr))                  // earlier zone does not cover every country

        val restricted = zone(6, listOf("*"), 0, patterns = listOf("34*"))
        assertNull(ZoneMatcher.shadowedBy(listOf(restricted, turkey), turkey))   // earlier zone has patterns
        val regional = zone(7, listOf("*"), 0, regions = listOf(ZoneRegion("TR", listOf("Ankara"))))
        assertNull(ZoneMatcher.shadowedBy(listOf(regional, turkey), turkey))

        val inactive = zone(8, listOf("*"), 0, active = false)
        assertNull(ZoneMatcher.shadowedBy(listOf(inactive, turkey), turkey))

        val star = zone(9, listOf("*"), 5)
        assertNull(ZoneMatcher.shadowedBy(listOf(turkey, star), star))           // a wildcard zone needs an earlier wildcard
        assertEquals(1L, ZoneMatcher.shadowedBy(listOf(everywhere, star), star))
    }

    @Test
    fun `norm folds turkish letters accents case and spaces`() {
        assertEquals("istanbul", ZoneMatcher.norm("İSTANBUL"))
        assertEquals("istanbul", ZoneMatcher.norm("ISTANBUL"))
        assertEquals("sirnak", ZoneMatcher.norm("Şırnak"))
        assertEquals("gumushane", ZoneMatcher.norm("Gümüşhane"))
        assertEquals("cankiri", ZoneMatcher.norm("ÇANKIRI"))
        assertEquals("sao paulo", ZoneMatcher.norm("  São   Paulo "))
        assertEquals("diyarbakir", ZoneMatcher.norm("Diyarbakır"))
        assertEquals("ogdur", ZoneMatcher.norm("Öğdür"))
    }
}
