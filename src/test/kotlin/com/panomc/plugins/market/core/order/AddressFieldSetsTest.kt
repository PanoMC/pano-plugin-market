package com.panomc.plugins.market.core.order

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Address field sets of 10 section 3.3 as served by the checkout config. */
class AddressFieldSetsTest {
    @Test
    fun `default turkey state and no postal code sets`() {
        val map = AddressFieldSets.asMap()

        assertEquals(listOf("firstName", "lastName", "phone", "country", "city", "line1", "postalCode"), map["*"])
        assertEquals(listOf("firstName", "lastName", "phone", "country", "city", "district", "line1"), map["TR"])
        listOf("US", "CA", "AU", "BR", "MX", "IN").forEach { assertEquals(map["*"]!! + "state", map[it], it) }
        listOf("AE", "HK", "MO", "QA", "PA", "BS", "JM", "FJ", "GH", "KE", "UG", "AO", "BW", "BZ", "ZW", "CI").forEach {
            assertEquals(map["*"]!! - "postalCode", map[it], it)
            assertFalse("postalCode" in map[it]!!)
        }
    }

    @Test
    fun `countries outside the map use the default entry and keys are sorted after the default`() {
        assertEquals(AddressFieldSets.asMap()["*"], AddressFieldSets.forCountry("DE"))
        assertFalse(AddressFieldSets.asMap().containsKey("DE"))

        val keys = AddressFieldSets.asMap().keys.toList()
        assertEquals("*", keys.first())
        assertEquals(keys.drop(1).sorted(), keys.drop(1))
        assertTrue(keys.size > 20)
    }
}
