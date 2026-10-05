package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketAddressDaoImpl
import com.panomc.plugins.market.db.model.MarketAddress
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_address` (01 section 5.6). */
class MarketAddressDaoIT : MarketDaoITBase() {
    private val dao = MarketAddressDaoImpl()

    private fun address(user: Long = 5, default: Boolean = false, label: String = "Home") = MarketAddress(
        userId = user, label = label, isDefault = default, firstName = "Ayse", lastName = "Yilmaz", company = "Acme",
        phone = "+905551112233", email = "a@example.com", country = "TR", state = "Istanbul", city = "Istanbul",
        district = "Kadikoy", neighborhood = "Moda", line1 = "Bagdat Cd. 1", line2 = "Kat 2", postalCode = "34710",
        identityNumber = "12345678901", type = "COMPANY", taxOffice = "Kadikoy", taxNumber = "1234567890",
        createdAt = 10, updatedAt = 20
    )

    @Test
    fun `an address round-trips every column`(): Unit = runBlocking {
        val written = address(default = true)
        EntityRoundTrip.differsFromDefaults(written, MarketAddress())
        val id = dao.add(written, pool)
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `the addresses of a user come default first and never include another user`(): Unit = runBlocking {
        val plain = dao.add(address(label = "Work"), pool)
        dao.add(address(user = 6), pool)
        val default = dao.add(address(default = true), pool)
        assertEquals(listOf(default, plain), dao.getByUserId(5, pool).map { it.id })
        assertEquals(emptyList<MarketAddress>(), dao.getByUserId(77, pool))
    }

    @Test
    fun `a minimal address keeps the optional columns null and is deletable`(): Unit = runBlocking {
        val id = dao.add(MarketAddress(userId = 5), pool)
        val read = dao.getById(id, pool)!!
        assertNull(read.label)
        assertNull(read.taxNumber)
        assertFalse(read.isDefault)
        assertTrue(dao.deleteById(id, pool))
        assertFalse(dao.deleteById(id, pool))
        assertNull(dao.getById(id, pool))
    }
}
