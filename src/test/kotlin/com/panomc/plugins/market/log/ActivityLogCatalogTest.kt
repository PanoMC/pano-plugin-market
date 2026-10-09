package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.plugins.market.support.PanelEndpointMatrix
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The activity-log catalogue of 04 section 10 (MK-160): every type has a class under the market package and a key `activity-logs.<TYPE>` in the three
 * core locale fragments, no class or key is left over, every class puts the acting `username` into its details and never a secret, and every class that
 * has an endpoint today is actually written by main code.
 */
class ActivityLogCatalogTest {
    /** The 89 types of 04 section 10 that the plugin still writes (28 existing + the new ones; the five webhook types moved to core), in the spelling of the class name. */
    private val catalogue: List<String> = """
        CREATED_MARKET_CATEGORY
        CREATED_MARKET_PRODUCT
        CREATED_MARKET_COMPARISON
        CREATED_MARKET_COUPON
        CREATED_MARKET_CREATOR_CODE
        CREATED_MARKET_DISCOUNT
        CREATED_MARKET_GIFT
        UPDATED_MARKET_CATEGORY
        UPDATED_MARKET_PRODUCT
        UPDATED_MARKET_COMPARISON
        UPDATED_MARKET_COUPON
        UPDATED_MARKET_CREATOR_CODE
        UPDATED_MARKET_DISCOUNT
        UPDATED_MARKET_GIFT
        DELETED_MARKET_CATEGORY
        DELETED_MARKET_PRODUCT
        DELETED_MARKET_COMPARISON
        DELETED_MARKET_COUPON
        DELETED_MARKET_CREATOR_CODE
        DELETED_MARKET_DISCOUNT
        DELETED_MARKET_GIFT
        SORTED_MARKET_CATEGORIES
        UPDATED_MARKET_SETTINGS
        UPDATED_MARKET_PAYMENT_METHOD
        REVEALED_MARKET_PAYMENT_SECRET
        UPDATED_MARKET_ORDER_STATUS
        UPDATED_MARKET_ORDER_EXCHANGE_RATE
        REFRESHED_EXCHANGE_RATE
        UPDATED_MARKET_PRODUCT_STOCK
        IMPORTED_MARKET_CATALOG
        CREATED_MARKET_GOAL
        UPDATED_MARKET_GOAL
        DELETED_MARKET_GOAL
        CREATED_MARKET_ORDER
        CANCELLED_MARKET_ORDER
        REVIEWED_MARKET_ORDER
        APPROVED_MARKET_BANK_TRANSFER
        REJECTED_MARKET_BANK_TRANSFER
        UPDATED_MARKET_ORDER_NOTE
        UPDATED_MARKET_ORDER_SHIPPING_ADDRESS
        ANONYMIZED_MARKET_ORDER
        EXPORTED_MARKET_ORDERS
        RESENT_MARKET_ORDER_MAIL
        RETRIED_MARKET_MAIL
        REGENERATED_MARKET_INVOICE
        REFUNDED_MARKET_ORDER
        RETRIED_MARKET_REFUND
        CANCELLED_MARKET_REFUND
        OPENED_MARKET_DISPUTE
        UPDATED_MARKET_DISPUTE
        GRANTED_MARKET_CREDITS
        REVOKED_MARKET_CREDITS
        CREATED_MARKET_CREATOR_PAYOUT
        CANCELLED_MARKET_CREATOR_PAYOUT
        CANCELLED_MARKET_SUBSCRIPTION
        RETRIED_MARKET_SUBSCRIPTION_CHARGE
        REPLAYED_MARKET_PAYMENT_EVENT
        GRANTED_MARKET_CREDITS_INGAME
        REVOKED_MARKET_CREDITS_INGAME
        SET_MARKET_CREDITS_INGAME
        GRANTED_MARKET_PRODUCT_INGAME
        RERAN_MARKET_DELIVERY
        RAN_MARKET_CHARGEBACK_ACTIONS
        RETRIED_MARKET_DELIVERY
        CANCELLED_MARKET_DELIVERY
        REVOKED_MARKET_ORDER
        CREATED_MARKET_SHIPMENT
        UPDATED_MARKET_SHIPMENT
        CANCELLED_MARKET_SHIPMENT
        CREATED_MARKET_BLOCK
        DELETED_MARKET_BLOCK
        UPDATED_MARKET_CURRENCIES
        UPDATED_MARKET_LEGAL_TEXT
        TOGGLED_MARKET_PAYMENT_METHOD
        SORTED_MARKET_PAYMENT_METHODS
        RAN_MARKET_PROVIDER_ACTION
        FAILED_MARKET_SECRET_REVEAL
        UPDATED_MARKET_INVOICE_SEQUENCE
        UPDATED_MARKET_SERVER_SETTINGS
        SENT_MARKET_TEST_MAIL
        CREATED_MARKET_SHIPPING_ZONE
        UPDATED_MARKET_SHIPPING_ZONE
        DELETED_MARKET_SHIPPING_ZONE
        CREATED_MARKET_SHIPPING_METHOD
        UPDATED_MARKET_SHIPPING_METHOD
        DELETED_MARKET_SHIPPING_METHOD
        UPDATED_MARKET_SHIPPING_CARRIER
        TOGGLED_MARKET_SHIPPING_CARRIER
        REVEALED_MARKET_SHIPPING_SECRET
    """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Classes of landed slices that 04 section 10 does not list (it has no row for them); each has its key and is written by its endpoint. */
    private val extras = setOf("REFRESHED_MARKET_CURRENCY_RATES", "RAN_MARKET_SHIPPING_ACTION", "SORTED_MARKET_SHIPPING_ZONES", "SORTED_MARKET_SHIPPING_METHODS")

    /** Types whose writing endpoint is a later slice; each is exempt from the "written by main code" rule until it lands (the class and the key exist now). */
    private val writtenLater = mapOf("IMPORTED_MARKET_CATALOG" to "GW (provider catalogue import)")

    /**
     * The webhook types of the time before core owned webhooks (doc 06 section 4.4): the plugin no longer writes them, but rows already in the activity log still
     * need their text, so the keys stay in the three fragments while the classes are gone.
     */
    private val retired = setOf("CREATED_MARKET_WEBHOOK", "UPDATED_MARKET_WEBHOOK", "DELETED_MARKET_WEBHOOK", "TESTED_MARKET_WEBHOOK", "REDELIVERED_MARKET_WEBHOOK")

    private val sourceRoot = File("src/main/kotlin/com/panomc/plugins/market")

    /** type -> class, for every PluginActivityLog subclass anywhere in the plugin. */
    private val classes: Map<String, Class<*>> = PanelEndpointMatrix.classesUnder("com.panomc.plugins.market")
        .filter { PluginActivityLog::class.java.isAssignableFrom(it) && it != PluginActivityLog::class.java }
        .associateBy { PanelActivityLog.typeOf(it.asSubclass(PanelActivityLog::class.java)) }

    /** `activity-logs` of the fragments of [locale]: the core fragment holds almost all of them, a feature fragment (webhook) its own; `mergeLocales` refuses a key defined twice. */
    private fun keys(locale: String): Map<String, String> {
        val merged = linkedMapOf<String, String>()

        for (dir in File("src/locales").listFiles { f -> f.isDirectory }!!.sortedBy { it.name }) {
            val file = File(dir, "$locale.json")

            if (!file.isFile) continue

            val logs = JsonObject(file.readText()).getJsonObject("activity-logs") ?: continue

            for (name in logs.fieldNames()) assertTrue(merged.put(name, logs.getString(name)) == null, "$locale: activity-logs.$name is defined in two fragments")
        }

        return merged
    }

    @Test
    fun `every type of the catalogue has a class`() {
        assertEquals(89, catalogue.size)
        assertEquals(catalogue.size, catalogue.toSet().size)

        val missing = catalogue.filter { it !in classes }

        assertTrue(missing.isEmpty(), "no log class for: $missing")
    }

    @Test
    fun `every log class is in the catalogue or a documented extra`() {
        val unknown = classes.keys - catalogue.toSet() - extras

        assertTrue(unknown.isEmpty(), "a log class that 04 section 10 does not list (add it there or to the extras): $unknown")
    }

    @Test
    fun `every type has a non-empty key in tr, en-US and ru and no key is left without a class`() {
        for (locale in listOf("tr", "en-US", "ru")) {
            val keys = keys(locale)

            assertEquals(emptySet<String>(), classes.keys - keys.keys, "$locale: classes without activity-logs.<TYPE>")
            assertEquals(emptySet<String>(), keys.keys - classes.keys - retired, "$locale: keys without a class")
            assertEquals(emptySet<String>(), retired - keys.keys, "$locale: a retired type lost its text (old log rows need it)")

            for ((type, text) in keys) {
                assertTrue(text.isNotBlank(), "$locale $type is empty")
                assertTrue(text.contains("{username}"), "$locale $type names the acting user")
            }
        }
    }

    @Test
    fun `the three fragments carry the same placeholders per type`() {
        val placeholder = Regex("\\{([A-Za-z]+)\\}")
        val tr = keys("tr")
        val en = keys("en-US")
        val ru = keys("ru")

        for (type in en.keys) {
            val expected = placeholder.findAll(en.getValue(type)).map { it.groupValues[1] }.toSet()

            assertEquals(expected, placeholder.findAll(tr.getValue(type)).map { it.groupValues[1] }.toSet(), "tr $type")
            assertEquals(expected, placeholder.findAll(ru.getValue(type)).map { it.groupValues[1] }.toSet(), "ru $type")
        }
    }

    @Test
    fun `every placeholder of a text is a detail the class writes`() {
        val placeholder = Regex("\\{([A-Za-z]+)\\}")
        val problems = mutableListOf<String>()

        for ((type, text) in keys("en-US")) {
            if (type in retired) continue

            val details = build(classes.getValue(type)).details

            for (name in placeholder.findAll(text).map { it.groupValues[1] }) if (!details.containsKey(name)) problems += "$type: {$name} is not in the details ${details.fieldNames()}"
        }

        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `every class writes the acting username and no secret, token, address or e-mail`() {
        val forbidden = Regex("(?i)secret|token|password|passwd|address|e-?mail|apikey|authorization")

        for ((type, clazz) in classes) {
            val log = build(clazz)

            assertEquals("x", log.details.getString("username"), "$type puts the actor into details.username")
            assertEquals(type, log.type)
            assertTrue(!log.pluginId.isNullOrBlank(), "$type carries the plugin id")
            assertEquals(1L, log.userId)

            val bad = log.details.fieldNames().filter { forbidden.containsMatchIn(it) }

            assertTrue(bad.isEmpty(), "$type has detail keys that must never be logged: $bad")
        }
    }

    @Test
    fun `every type with an endpoint today is written by main code`() {
        val main = sourceRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.map { it to it.readText() }.toList()
        val notWritten = classes.filter { (type, clazz) ->
            val name = clazz.simpleName

            type !in writtenLater && main.none { (_, text) -> !text.contains("class $name(") && text.contains("$name(") }
        }.keys

        assertTrue(notWritten.isEmpty(), "never constructed outside the file that declares it (no endpoint writes it): $notWritten")
    }

    @Test
    fun `an exemption for a later slice names a type that exists`() {
        assertTrue(writtenLater.keys.all { it in classes }, "writtenLater names unknown types: ${writtenLater.keys - classes.keys}")
    }

    /** Builds [clazz] with `x` as the username, 1 for numbers, `v` for other text, true and empty containers; the acting user id is 1. */
    private fun build(clazz: Class<*>): PanelActivityLog {
        val constructor = clazz.constructors.single { !it.isSynthetic }

        val args = constructor.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> 1L
                index == 1 -> "x"
                else -> when (type.name) {
                    "long", "java.lang.Long" -> 1L
                    "int", "java.lang.Integer" -> 1
                    "boolean", "java.lang.Boolean" -> true
                    "double", "java.lang.Double" -> 1.0
                    "java.lang.String" -> "v"
                    "java.util.List" -> emptyList<String>()
                    JsonObject::class.java.name -> JsonObject()
                    else -> error("${clazz.simpleName}: no sample for parameter $index (${type.name})")
                }
            }
        }

        return constructor.newInstance(*args.toTypedArray()) as PanelActivityLog
    }
}
