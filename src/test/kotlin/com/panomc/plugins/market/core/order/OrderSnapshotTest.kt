package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.config.BillingInfoMode
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/** The item snapshot, the billing snapshot (06 section 8.2) and the request fingerprint (06 section 5.1), MK-075. Pure. */
class OrderSnapshotTest {
    private fun product(actions: String? = null, attributes: String? = null, meta: JsonObject = JsonObject()) = SnapshotProduct(
        slug = "vip", imageFileName = "vip.png", kind = "STANDARD", billingMode = "TIMED", periodUnit = "DAY", periodCount = 30, physical = true,
        weightGrams = 250, lengthMm = 100, widthMm = 50, heightMm = 20, hsCode = "6109.10", originCountry = "TR", tierCategoryId = 7, tierRank = 2,
        actions = actions, variantAttributes = attributes, providerMeta = meta
    )

    // ---------------------------------------------------------------------------------------------- item snapshot

    @Test
    fun `the item snapshot carries every key of 01 section 5 2, actions and attributes as JSON values`() {
        val snapshot = ItemSnapshot.of(
            product("[{\"id\":\"a1\",\"type\":\"COMMAND\"}]", "{\"color\":\"red\"}", JsonObject().put("paytr", JsonObject().put("packageId", "9")))
        )

        assertEquals(
            setOf(
                "slug", "imageFileName", "kind", "billingMode", "periodUnit", "periodCount", "physical", "weightGrams", "lengthMm", "widthMm", "heightMm",
                "hsCode", "originCountry", "tierCategoryId", "tierRank", "actions", "variantAttributes", "providerMeta"
            ),
            snapshot.fieldNames()
        )
        assertEquals("a1", snapshot.getJsonArray("actions").getJsonObject(0).getString("id"))
        assertEquals("red", snapshot.getJsonObject("variantAttributes").getString("color"))
        assertEquals("9", snapshot.getJsonObject("providerMeta").getJsonObject("paytr").getString("packageId"))
        assertEquals(250, snapshot.getInteger("weightGrams"))
        assertEquals(true, snapshot.getBoolean("physical"))
        assertEquals(2, snapshot.getInteger("tierRank"))
    }

    @Test
    fun `a product without actions or attributes gets an empty array and null, and unusable JSON text is not copied`() {
        val plain = ItemSnapshot.of(product(null, null))

        assertEquals(JsonArray(), plain.getJsonArray("actions"))
        assertNull(plain.getValue("variantAttributes"))
        assertTrue(plain.containsKey("variantAttributes"), "the key is present with null")

        val broken = ItemSnapshot.of(product("not json", "[1,2]"))

        assertEquals(JsonArray(), broken.getJsonArray("actions"))
        assertNull(broken.getValue("variantAttributes"))
    }

    @Test
    fun `the top-up line snapshot is the one of 07 section 8 2`() {
        val snapshot = ItemSnapshot.topUp()

        assertEquals("CREDIT_TOPUP", snapshot.getString("kind"))
        assertEquals(JsonArray(), snapshot.getJsonArray("actions"))
        assertEquals(2, snapshot.size())
    }

    // -------------------------------------------------------------------------------------------- billing snapshot

    private fun billing(mode: BillingInfoMode, json: String?, required: List<String> = emptyList(), have: Set<String> = emptySet()) =
        BillingSnapshot.check(json?.let { JsonObject(it) }, mode, required, have)

    private fun valid(result: BillingSnapshot.Result): JsonObject? = (result as BillingSnapshot.Result.Valid).json

    private fun invalid(result: BillingSnapshot.Result): List<String> = (result as BillingSnapshot.Result.Invalid).fields

    private val individual = "{\"type\":\"INDIVIDUAL\",\"firstName\":\" Ada \",\"lastName\":\"Lovelace\",\"country\":\"gb\",\"city\":\"London\",\"line1\":\"1 Main Street\"}"

    private val requiredMode = listOf("billingInfo.type", "billingInfo.firstName", "billingInfo.lastName", "billingInfo.country", "billingInfo.city", "billingInfo.line1")

    @Test
    fun `mode OFF discards the object and keeps only what a provider requires`() {
        assertNull(valid(billing(BillingInfoMode.OFF, individual)))

        val kept = valid(billing(BillingInfoMode.OFF, individual, listOf("billingInfo.firstName", "billingInfo.lastName")))!!

        assertEquals(setOf("firstName", "lastName", "type"), kept.fieldNames())
        assertEquals("Ada", kept.getString("firstName"), "trimmed")
    }

    @Test
    fun `mode OPTIONAL validates and stores what was sent, nothing when nothing was sent`() {
        val stored = valid(billing(BillingInfoMode.OPTIONAL, individual))!!

        assertEquals("GB", stored.getString("country"), "the country code is upper-cased")
        assertEquals("INDIVIDUAL", stored.getString("type"))
        assertEquals("1 Main Street", stored.getString("line1"))

        assertNull(valid(billing(BillingInfoMode.OPTIONAL, null)))
        assertNull(valid(billing(BillingInfoMode.OPTIONAL, "{}")))
        assertEquals(listOf("billingInfo.phone"), invalid(billing(BillingInfoMode.OPTIONAL, "{\"phone\":\"0555\"}")))
    }

    @Test
    fun `mode REQUIRED lists every missing path, a company adds its own, and the type defaults to INDIVIDUAL`() {
        assertEquals(
            listOf("billingInfo.firstName", "billingInfo.lastName", "billingInfo.country", "billingInfo.city", "billingInfo.line1"),
            invalid(billing(BillingInfoMode.REQUIRED, null, requiredMode))
        )

        val ok = valid(billing(BillingInfoMode.REQUIRED, "{\"firstName\":\"Ada\",\"lastName\":\"L\",\"country\":\"TR\",\"city\":\"Ankara\",\"line1\":\"Cankaya\"}", requiredMode))!!

        assertEquals("INDIVIDUAL", ok.getString("type"))

        val company = requiredMode + listOf("billingInfo.company", "billingInfo.taxNumber")

        assertEquals(
            listOf("billingInfo.company", "billingInfo.taxNumber"),
            invalid(billing(BillingInfoMode.REQUIRED, "{\"type\":\"COMPANY\",\"firstName\":\"Ada\",\"lastName\":\"L\",\"country\":\"TR\",\"city\":\"Ankara\",\"line1\":\"Cankaya\"}", company))
        )
    }

    @Test
    fun `every offending path is listed, missing and invalid together`() {
        val fields = invalid(
            billing(
                BillingInfoMode.REQUIRED,
                "{\"firstName\":\"\",\"lastName\":\"L\",\"country\":\"ZZ\",\"line1\":\"ab\",\"postalCode\":\"!!\",\"taxNumber\":\"a b\",\"type\":\"CORP\"}",
                requiredMode + "billingInfo.postalCode"
            )
        )

        assertEquals(
            setOf("billingInfo.country", "billingInfo.line1", "billingInfo.postalCode", "billingInfo.taxNumber", "billingInfo.type", "billingInfo.firstName", "billingInfo.city"),
            fields.toSet()
        )
        assertEquals(fields.size, fields.toSet().size, "no path twice")
    }

    @Test
    fun `field rules of 06 section 8 2 hold at their boundaries`() {
        fun bad(key: String, value: Any?) = invalid(billing(BillingInfoMode.OPTIONAL, JsonObject().put(key, value).encode())) == listOf("billingInfo.$key")
        fun good(key: String, value: Any?) = billing(BillingInfoMode.OPTIONAL, JsonObject().put(key, value).encode()) is BillingSnapshot.Result.Valid

        assertTrue(good("firstName", "a".repeat(100)) && bad("firstName", "a".repeat(101)))
        assertTrue(good("company", "c".repeat(255)) && bad("company", "c".repeat(256)))
        assertTrue(good("phone", "+905551234567") && bad("phone", "905551234567") && bad("phone", "+0555123456") && bad("phone", "+123"))
        assertTrue(good("state", "s".repeat(100)) && bad("state", "s".repeat(101)))
        assertTrue(good("line1", "abc") && bad("line1", "ab") && bad("line1", "l".repeat(256)))
        assertTrue(good("line2", "l".repeat(255)) && bad("line2", "l".repeat(256)))
        assertTrue(good("postalCode", "SW1A 1AA") && bad("postalCode", "p".repeat(17)) && bad("postalCode", "12_34"))
        assertTrue(good("taxOffice", "t".repeat(100)) && bad("taxOffice", "t".repeat(101)))
        assertTrue(good("taxNumber", "12-34") && bad("taxNumber", "t".repeat(33)) && bad("taxNumber", "12 34"))
        assertTrue(good("email", "Buyer@Example.com") && bad("email", "nope"))
        assertTrue(good("country", "TR") && good("country", "tr") && bad("country", "TUR") && bad("country", "ZZ"))
        assertTrue(bad("type", "OTHER"))
    }

    @Test
    fun `control characters are removed before the length is judged`() {
        val stored = valid(billing(BillingInfoMode.OPTIONAL, JsonObject().put("firstName", "A\u0000d\na").encode()))!!

        assertEquals("Ada", stored.getString("firstName"))
        assertEquals(listOf("billingInfo.firstName"), invalid(billing(BillingInfoMode.OPTIONAL, JsonObject().put("firstName", "\u0000\n ").put("lastName", "x").encode(), listOf("billingInfo.firstName"))))
    }

    @Test
    fun `the Turkish identity number needs 11 digits and the checksum, other countries 32 characters`() {
        assertTrue(BillingSnapshot.isTckn("10000000146"))
        assertFalse(BillingSnapshot.isTckn("10000000147"), "last digit")
        assertFalse(BillingSnapshot.isTckn("10000000156"), "tenth digit")
        assertFalse(BillingSnapshot.isTckn("00000000000"), "first digit 0")
        assertFalse(BillingSnapshot.isTckn("1000000014"), "ten digits")
        assertFalse(BillingSnapshot.isTckn("1000000014a"), "letter")

        val tr = { id: String -> billing(BillingInfoMode.OPTIONAL, "{\"country\":\"TR\",\"identityNumber\":\"$id\"}") }

        assertEquals("10000000146", valid(tr("10000000146"))!!.getString("identityNumber"))
        assertEquals(listOf("billingInfo.identityNumber"), invalid(tr("10000000147")))
        assertEquals(listOf("billingInfo.identityNumber"), invalid(billing(BillingInfoMode.OPTIONAL, "{\"country\":\"DE\",\"identityNumber\":\"${"x".repeat(33)}\"}")))
        assertEquals("ABC-123", valid(billing(BillingInfoMode.OPTIONAL, "{\"country\":\"DE\",\"identityNumber\":\"ABC-123\"}"))!!.getString("identityNumber"))
    }

    @Test
    fun `the identity number is judged against the country whatever the key order is`() {
        assertEquals(
            listOf("billingInfo.identityNumber"),
            invalid(billing(BillingInfoMode.OPTIONAL, "{\"identityNumber\":\"12345678901\",\"country\":\"TR\"}"))
        )
    }

    @Test
    fun `an object or an array is no value, it never satisfies a required path as an empty string`() {
        val address = "\"firstName\":\"Ada\",\"lastName\":\"L\",\"country\":\"TR\",\"line1\":\"Cankaya\""

        // REQUIRED: the city is an array / an object / an array with content
        for (value in listOf("[]", "{}", "[1]", "{\"a\":1}")) {
            assertEquals(
                listOf("billingInfo.city"),
                invalid(billing(BillingInfoMode.REQUIRED, "{$address,\"city\":$value}", requiredMode)),
                "city: $value"
            )
        }

        // a company that is an object, a provider that needs the identity number outside TR
        assertEquals(
            listOf("billingInfo.company"),
            invalid(billing(BillingInfoMode.REQUIRED, "{\"type\":\"COMPANY\",$address,\"city\":\"Ankara\",\"company\":{},\"taxNumber\":\"123\"}", requiredMode + listOf("billingInfo.company", "billingInfo.taxNumber")))
        )
        assertEquals(
            listOf("billingInfo.identityNumber"),
            invalid(billing(BillingInfoMode.OFF, "{\"country\":\"DE\",\"identityNumber\":[]}", listOf("billingInfo.identityNumber")))
        )
        assertEquals(
            listOf("billingInfo.company", "billingInfo.city", "billingInfo.identityNumber"),
            invalid(billing(BillingInfoMode.REQUIRED, "{\"country\":\"DE\",\"city\":[],\"company\":{},\"identityNumber\":[]}", listOf("billingInfo.city", "billingInfo.company", "billingInfo.identityNumber"))),
            "every offending path is listed once"
        )
    }

    @Test
    fun `an object or an array is invalid for every key of the address, also when nothing requires it`() {
        for (key in listOf("firstName", "lastName", "company", "phone", "email", "country", "state", "city", "district", "neighborhood", "line1", "line2", "postalCode", "taxOffice", "taxNumber", "identityNumber")) {
            for (value in listOf("[]", "{}", "[\"x\"]")) {
                assertEquals(listOf("billingInfo.$key"), invalid(billing(BillingInfoMode.OPTIONAL, "{\"$key\":$value}")), "$key: $value")
                assertEquals(listOf("billingInfo.$key"), invalid(billing(BillingInfoMode.OFF, "{\"$key\":$value}")), "$key: $value (OFF)")
            }
        }

        assertEquals(listOf("billingInfo.type"), invalid(billing(BillingInfoMode.OPTIONAL, "{\"type\":[]}")))
        assertEquals(listOf("billingInfo.type"), invalid(billing(BillingInfoMode.OPTIONAL, "{\"type\":{\"a\":1}}")))
    }

    @Test
    fun `null and blank values are absent, numbers and booleans are text`() {
        assertNull(valid(billing(BillingInfoMode.OPTIONAL, "{\"city\":null,\"state\":\"  \",\"line2\":\"\"}")))
        assertEquals(listOf("billingInfo.city"), invalid(billing(BillingInfoMode.REQUIRED, "{\"city\":null}", listOf("billingInfo.city"))))
        assertEquals("12345", valid(billing(BillingInfoMode.OPTIONAL, "{\"postalCode\":12345}"))!!.getString("postalCode"))
        assertEquals("true", valid(billing(BillingInfoMode.OPTIONAL, "{\"district\":true}"))!!.getString("district"))
    }

    @Test
    fun `cleanText is the text the snapshot stores, so a padded type reads as the type it will be stored as`() {
        assertEquals("COMPANY", BillingSnapshot.cleanText("COMPANY "))
        assertEquals("COMPANY", BillingSnapshot.cleanText(" \tCOMPANY\n"))
        assertEquals("TR", BillingSnapshot.cleanText("T\u0000R "))
        assertEquals("5", BillingSnapshot.cleanText(5))
        assertEquals("true", BillingSnapshot.cleanText(true))
        assertNull(BillingSnapshot.cleanText(null))
        assertNull(BillingSnapshot.cleanText("  "))
        assertNull(BillingSnapshot.cleanText("\u0000"))
        assertNull(BillingSnapshot.cleanText(JsonArray()))
        assertNull(BillingSnapshot.cleanText(JsonObject()))
        assertNull(BillingSnapshot.cleanText(listOf("COMPANY")))

        // the stored snapshot of a padded type is COMPANY, and the paths the request required for a company are the ones that check
        val required = RequiredBuyerFields.of(
            BillingInfoMode.REQUIRED, emptySet(), BillingSnapshot.cleanText("COMPANY ").equals("COMPANY", ignoreCase = true), BillingSnapshot.cleanText(" tr "), false
        )

        assertTrue("billingInfo.company" in required && "billingInfo.taxNumber" in required)
        assertEquals(
            listOf("billingInfo.company", "billingInfo.taxNumber"),
            invalid(billing(BillingInfoMode.REQUIRED, "{\"type\":\"COMPANY \",\"firstName\":\"Ada\",\"lastName\":\"L\",\"country\":\"TR\",\"city\":\"Ankara\",\"line1\":\"Cankaya\"}", required))
        )
        assertEquals("COMPANY", valid(billing(BillingInfoMode.OPTIONAL, "{\"type\":\"COMPANY \",\"company\":\"Acme\"}"))!!.getString("type"))
    }

    @Test
    fun `email and shippingAddress paths are satisfied only when the caller vouches for them`() {
        val required = listOf("email", "shippingAddress", "billingInfo.firstName")

        assertEquals(listOf("email", "shippingAddress", "billingInfo.firstName"), invalid(billing(BillingInfoMode.OFF, null, required)))
        assertEquals(listOf("billingInfo.firstName"), invalid(billing(BillingInfoMode.OFF, null, required, setOf("email", "shippingAddress"))))
    }

    // ------------------------------------------------------------------------------------------ fingerprint

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `the canonical form sorts keys at every level and writes no whitespace`() {
        val body = JsonObject("{ \"b\" : 1, \"a\" : [ true, null, \"x\", {\"z\":1,\"y\":2} ] }")

        assertEquals("{\"a\":[true,null,\"x\",{\"y\":2,\"z\":1}],\"b\":1}", RequestFingerprint.canonical(body))
        assertEquals(sha("{\"a\":[true,null,\"x\",{\"y\":2,\"z\":1}],\"b\":1}"), RequestFingerprint.hash(body))
    }

    @Test
    fun `numbers are written in their shortest form`() {
        val a = RequestFingerprint.canonical(JsonObject("{\"x\":1.0,\"y\":10.50,\"z\":100,\"w\":0.0,\"v\":-2.0}"))

        assertEquals("{\"v\":-2,\"w\":0,\"x\":1,\"y\":10.5,\"z\":100}", a)
        assertEquals(RequestFingerprint.hash(JsonObject("{\"total\":19.99}")), RequestFingerprint.hash(JsonObject("{\"total\":19.990}")))
    }

    @Test
    fun `key order and whitespace do not change the hash, any value does`() {
        val one = RequestFingerprint.hash(JsonObject("{\"a\":1,\"b\":{\"c\":\"d\",\"e\":[1,2]}}"))
        val two = RequestFingerprint.hash(JsonObject("{\"b\":{\"e\":[1,2],\"c\":\"d\"},\"a\":1}"))

        assertEquals(one, two)
        assertEquals(64, one.length)
        assertNotEquals(one, RequestFingerprint.hash(JsonObject("{\"a\":2,\"b\":{\"c\":\"d\",\"e\":[1,2]}}")))
        assertNotEquals(one, RequestFingerprint.hash(JsonObject("{\"a\":1,\"b\":{\"c\":\"d\",\"e\":[2,1]}}")), "array order counts")
        assertNotEquals(one, RequestFingerprint.hash(JsonObject("{\"a\":1,\"b\":{\"c\":\"d\",\"e\":[1,2]},\"f\":null}")), "a null key counts: the whole body counts")
    }

    @Test
    fun `strings are escaped and keys compare by code point`() {
        val body = JsonObject().put("q\"", "line\nbreak ç 😀").put("a", "b").put("😀", 1).put("～", 2)

        // U+FF5E (BMP, above the surrogates) sorts BEFORE the astral U+1F600 by code point, after it by UTF-16 units
        assertEquals("{\"a\":\"b\",\"q\\\"\":\"line\\nbreak ç 😀\",\"～\":2,\"😀\":1}", RequestFingerprint.canonical(body))
    }
}
