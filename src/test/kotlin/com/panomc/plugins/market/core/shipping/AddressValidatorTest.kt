package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.shipping.AddressField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `AddressValidator` (10 section 3, tests 1 to 6 of section 16). */
class AddressValidatorTest {
    private fun addr(
        country: String? = "DE", state: String? = null, city: String? = "Berlin", district: String? = null,
        postal: String? = "10115", line1: String? = "Hauptstr. 1", phone: String? = "+4930123456",
        first: String? = "Ada", last: String? = "Lovelace", email: String? = null, identity: String? = null,
        line2: String? = null, company: String? = null, neighborhood: String? = null
    ) = Address(first, last, company, phone, email, country, state, city, district, neighborhood, line1, line2, postal, null, null, identity)

    private fun check(a: Address, vararg provider: AddressField) = AddressValidator.check(AddressValidator.normalize(a, null, true), provider.toList())

    private val turkey = addr(country = "TR", city = "İstanbul", district = "Kadıköy", postal = null, phone = "0532 123 45 67", line1 = "Moda Cd. 5")

    @Test
    fun `turkey without postal code is valid and without district misses the district`() {
        assertTrue(check(turkey).valid)

        val result = check(addr(country = "TR", city = "İstanbul", district = null, postal = null, phone = "0532 123 45 67"))
        assertFalse(result.valid)
        assertEquals(listOf("district"), result.missing)
        assertEquals(emptyList<String>(), result.invalid)
        assertEquals(listOf("district"), result.fields)
    }

    @Test
    fun `a state is required for US and not for DE and AE needs no postal code`() {
        val us = addr(country = "US", city = "NYC", postal = "10001", phone = "+12125550100")

        assertEquals(listOf("state"), check(us).missing)
        assertTrue(check(addr(country = "US", state = "NY", city = "NYC", postal = "10001", phone = "+12125550100")).valid)
        assertTrue(check(addr(country = "DE", state = null)).valid)
        assertTrue(check(addr(country = "AE", city = "Dubai", postal = null, phone = "+971501234567")).valid)
        assertEquals(listOf("postalCode"), check(addr(country = "FR", city = "Paris", postal = null, phone = "+33123456789")).missing)
    }

    @Test
    fun `a provider field adds to the required set and identity number format is checked`() {
        val noIdentity = turkey
        assertEquals(listOf("identityNumber"), check(noIdentity, AddressField.IDENTITY_NUMBER).missing)
        assertEquals(listOf("neighborhood"), check(noIdentity, AddressField.NEIGHBORHOOD).missing)

        val short = AddressValidator.check(
            AddressValidator.normalize(addr(country = "TR", city = "İstanbul", district = "Kadıköy", postal = null, identity = "1234567890"), null, true),
            listOf(AddressField.IDENTITY_NUMBER)
        )
        assertEquals(listOf("identityNumber"), short.invalid)

        val ok = AddressValidator.check(
            AddressValidator.normalize(addr(country = "TR", city = "İstanbul", district = "Kadıköy", postal = null, identity = "12345678901"), null, true),
            listOf(AddressField.IDENTITY_NUMBER)
        )
        assertTrue(ok.valid)

        // other countries: 5 to 20 of [A-Za-z0-9-]
        assertTrue(check(addr(identity = "AB-1234"), AddressField.IDENTITY_NUMBER).valid)
        assertEquals(listOf("identityNumber"), check(addr(identity = "AB 12"), AddressField.IDENTITY_NUMBER).invalid)
    }

    @Test
    fun `provider fields map to address property names without duplicates`() {
        assertEquals(
            listOf("firstName", "lastName", "phone", "country", "city", "district", "line1", "neighborhood", "postalCode", "state", "email", "identityNumber"),
            AddressValidator.requiredFields("TR", AddressField.values().toList())
        )
        assertEquals(
            listOf("firstName", "lastName", "phone", "country", "city", "line1", "postalCode", "state"),
            AddressValidator.requiredFields("US", listOf(AddressField.STATE, AddressField.PHONE, AddressField.POSTAL_CODE))
        )
        assertEquals(AddressValidator.requiredFields("DE"), AddressValidator.requiredFields(null))
    }

    @Test
    fun `identity number is dropped unless a provider needs it`() {
        val a = addr(identity = "AB-12345")

        assertNull(AddressValidator.normalize(a, null, false).identityNumber)
        assertEquals("AB-12345", AddressValidator.normalize(a, null, true).identityNumber)
    }

    @Test
    fun `postal code formats`() {
        fun postal(country: String, code: String) =
            AddressValidator.check(AddressValidator.normalize(addr(country = country, state = "S", district = "D", postal = code, phone = "+4930123456"))).invalid

        assertEquals(emptyList<String>(), postal("TR", "34000"))
        assertEquals(listOf("postalCode"), postal("TR", "3400"))
        assertEquals(emptyList<String>(), postal("GB", "sw1a 1aa"))
        assertEquals(emptyList<String>(), postal("NL", "1234 ab"))
        assertEquals(emptyList<String>(), postal("CA", "K1A 0B1"))
        assertEquals(emptyList<String>(), postal("US", "12345-6789"))
        assertEquals(listOf("postalCode"), postal("US", "1234"))
        assertEquals(listOf("postalCode"), postal("DE", "1011"))
        assertEquals(emptyList<String>(), postal("AU", "2000"))
        assertEquals(emptyList<String>(), postal("SE", "114 55"))     // any other country: letters, digits, space, hyphen
        assertEquals(listOf("postalCode"), postal("SE", "!"))
    }

    @Test
    fun `phone is normalised and an invalid phone is reported`() {
        assertEquals("+905321234567", AddressValidator.normalize(turkey).phone)
        assertEquals("+4930123456", AddressValidator.normalize(addr(phone = "00 49 30 123456")).phone)

        val bad = check(addr(phone = "123"))
        assertEquals(listOf("phone"), bad.invalid)
        assertEquals("123", AddressValidator.normalize(addr(phone = "123")).phone)
        assertEquals(listOf("phone"), check(addr(phone = null)).missing)
    }

    @Test
    fun `control characters and line breaks are removed whitespace collapses and empty becomes null`() {
        val n = AddressValidator.normalize(addr(line1 = "  Main\r\nStreet \t 5\u0000 ", city = "  Ber   lin ", line2 = "  \n ", company = ""))

        assertEquals("Main Street 5", n.line1)
        assertEquals("Ber lin", n.city)
        assertNull(n.line2)
        assertNull(n.company)
    }

    @Test
    fun `country and postal code are upper-cased and e-mail is lower-cased with the order e-mail as default`() {
        val n = AddressValidator.normalize(addr(country = " de ", postal = "sw1a 1aa", email = "Ada@Example.COM"), "order@x.io")
        assertEquals("DE", n.country)
        assertEquals("SW1A 1AA", n.postalCode)
        assertEquals("ada@example.com", n.email)

        assertEquals("order@x.io", AddressValidator.normalize(addr(), "Order@X.io").email)
        assertNull(AddressValidator.normalize(addr(), null).email)
    }

    @Test
    fun `billing only keys are dropped`() {
        val a = Address("A", "B", null, "+4930123456", null, "DE", null, "Berlin", null, null, "x 1", null, "10115", "Kadikoy VD", "1234567890", null)
        val n = AddressValidator.normalize(a)

        assertNull(n.taxOffice)
        assertNull(n.taxNumber)
    }

    @Test
    fun `maximum lengths`() {
        assertEquals(listOf("city"), check(addr(city = "x".repeat(101))).invalid)
        assertTrue(check(addr(city = "x".repeat(100))).valid)
        assertEquals(listOf("line1"), check(addr(line1 = "x".repeat(256))).invalid)
        assertTrue(check(addr(line1 = "x".repeat(255), line2 = "y".repeat(255))).valid)
        assertEquals(listOf("line2"), check(addr(line2 = "y".repeat(256))).invalid)
        assertEquals(listOf("firstName"), check(addr(first = "x".repeat(101))).invalid)
        assertEquals(listOf("postalCode"), check(addr(postal = "1".repeat(17))).invalid)
    }

    @Test
    fun `unknown country and bad e-mail are invalid and fields lists missing before invalid`() {
        val result = check(addr(country = "ZZ", first = null, email = "not-an-email"))

        assertEquals(listOf("firstName"), result.missing)
        assertEquals(listOf("email", "country"), result.invalid)
        assertEquals(listOf("firstName", "email", "country"), result.fields)
        assertTrue(check(addr(email = "ada@example.com")).valid)
    }

    @Test
    fun `an address without a country misses it and uses the default set`() {
        val result = check(addr(country = null))

        assertEquals(listOf("country"), result.missing)
        assertFalse(result.valid)
    }
}
