package com.panomc.plugins.market.config

import com.google.gson.Gson
import com.panomc.platform.config.HoconWriter
import com.typesafe.config.ConfigFactory
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `MarketConfig` version 2 (00 section 12): every key, its default, type and range; migration 1 -> 2. */
class MarketConfigTest {
    private val gson = Gson()

    /** Key, kind, default: the table of 00 section 12 written out independently of the code under test. */
    private val documented: List<Triple<String, ConfigKind, Any?>> = listOf(
        // existing
        Triple("storeName", ConfigKind.STRING, "Market"),
        Triple("storeDescription", ConfigKind.STRING, ""),
        Triple("currency", ConfigKind.STRING, "TRY"),
        Triple("statsCurrency", ConfigKind.STRING, "TRY"),
        Triple("exchangeRateMode", ConfigKind.ENUM, "AUTO"),
        Triple("exchangeRate", ConfigKind.DOUBLE, 1.0),
        Triple("exchangeRateUpdatedAt", ConfigKind.INT, 0L),
        Triple("exchangeRateAutoIntervalHours", ConfigKind.INT, 6),
        Triple("vatPercent", ConfigKind.DOUBLE, 20.0),
        Triple("showVatInPrice", ConfigKind.BOOL, true),
        Triple("testMode", ConfigKind.BOOL, false),
        Triple("allowGuestCheckout", ConfigKind.BOOL, true),
        Triple("minimumOrderAmount", ConfigKind.DOUBLE, 0.0),
        Triple("removeCents", ConfigKind.BOOL, false),
        Triple("showBestsellers", ConfigKind.BOOL, true),
        Triple("showFeaturedProducts", ConfigKind.BOOL, true),
        Triple("showComparisons", ConfigKind.BOOL, true),
        Triple("sendEmailAfterPurchase", ConfigKind.BOOL, true),
        Triple("combineDiscountsAndCoupons", ConfigKind.BOOL, true),
        Triple("creditsEnabled", ConfigKind.BOOL, true),
        Triple("creditName", ConfigKind.STRING, ""),
        Triple("cashbackPercent", ConfigKind.DOUBLE, 0.0),
        Triple("onlyAcceptCredits", ConfigKind.BOOL, false),
        // new in version 2
        Triple("storeEnabled", ConfigKind.BOOL, true),
        Triple("storeTimeZone", ConfigKind.STRING, ""),
        Triple("currencyMode", ConfigKind.ENUM, "SINGLE"),
        Triple("additionalCurrencies", ConfigKind.STRING_LIST, emptyList<String>()),
        Triple("multiCurrencyFallback", ConfigKind.ENUM, "CONVERT"),
        Triple("mailDisabledKinds", ConfigKind.STRING_LIST, emptyList<String>()),
        Triple("mailAttachInvoice", ConfigKind.BOOL, true),
        Triple("mailReplyTo", ConfigKind.STRING, ""),
        Triple("mailOrderDeliveredDelayMinutes", ConfigKind.INT, 10),
        Triple("allowGiftPurchase", ConfigKind.BOOL, true),
        Triple("billingInfoMode", ConfigKind.ENUM, "OPTIONAL"),
        Triple("legalTextRequired", ConfigKind.BOOL, false),
        Triple("orderExpiryMinutes", ConfigKind.INT, 60),
        Triple("bankTransferExpiryHours", ConfigKind.INT, 72),
        Triple("autoRefundDuplicatePayments", ConfigKind.BOOL, true),
        Triple("invoiceEnabled", ConfigKind.BOOL, true),
        Triple("invoiceSeries", ConfigKind.STRING, "INV"),
        Triple("invoiceSellerName", ConfigKind.STRING, ""),
        Triple("invoiceSellerAddress", ConfigKind.STRING, ""),
        Triple("invoiceSellerTaxOffice", ConfigKind.STRING, ""),
        Triple("invoiceSellerTaxNumber", ConfigKind.STRING, ""),
        Triple("invoiceFooter", ConfigKind.STRING, ""),
        Triple("invoiceCreditNoteSeries", ConfigKind.STRING, "CN"),
        Triple("invoiceCreditOrders", ConfigKind.BOOL, false),
        Triple("invoiceLocale", ConfigKind.STRING, ""),
        Triple("invoiceShowLogo", ConfigKind.BOOL, true),
        Triple("creditValue", ConfigKind.DOUBLE, 1.0),
        Triple("allowMixedCreditPayment", ConfigKind.BOOL, false),
        Triple("creditTopUpEnabled", ConfigKind.BOOL, false),
        Triple("creditTopUpFreeAmount", ConfigKind.BOOL, false),
        Triple("creditTopUpMin", ConfigKind.DOUBLE, 1.0),
        Triple("creditTopUpMax", ConfigKind.DOUBLE, 10000.0),
        Triple("revokeOnRefund", ConfigKind.BOOL, true),
        Triple("revokeOnChargeback", ConfigKind.BOOL, true),
        Triple("deliveryMaxAttempts", ConfigKind.INT, 5),
        Triple("deliveryOnlineWaitDays", ConfigKind.INT, 0),
        Triple("deliveryAckTimeoutSeconds", ConfigKind.INT, 30),
        Triple("subscriptionGraceDays", ConfigKind.INT, 3),
        Triple("subscriptionReminderDays", ConfigKind.INT, 3),
        Triple("subscriptionManualFallback", ConfigKind.BOOL, true),
        Triple("autoBlockOnChargeback", ConfigKind.BOOL, true),
        Triple("revokeCreditOrdersOnTopUpChargeback", ConfigKind.BOOL, true),
        Triple("creatorEarningHoldDays", ConfigKind.INT, 14),
        Triple("chargebackActions", ConfigKind.STRING, "[]"),
        Triple("checkoutRateLimitPerMinute", ConfigKind.INT, 6),
        Triple("quoteRateLimitPerMinute", ConfigKind.INT, 60),
        Triple("couponLockThreshold", ConfigKind.INT, 5),
        Triple("couponLockMinutes", ConfigKind.INT, 15),
        Triple("allowPrivateWebhookTargets", ConfigKind.BOOL, false),
        Triple("storePageSize", ConfigKind.INT, 24),
        Triple("moduleRecentBuyers", ConfigKind.BOOL, true),
        Triple("moduleRecentBuyersCount", ConfigKind.INT, 10),
        Triple("moduleRecentBuyersShowAmount", ConfigKind.BOOL, false),
        Triple("moduleTopSupporters", ConfigKind.BOOL, true),
        Triple("moduleTopSupportersPeriod", ConfigKind.ENUM, "MONTH"),
        Triple("moduleTopSupportersCount", ConfigKind.INT, 5),
        Triple("moduleGoal", ConfigKind.BOOL, true),
        Triple("moduleSaleBadges", ConfigKind.BOOL, true),
        Triple("moduleSaleCountdown", ConfigKind.BOOL, true),
        Triple("moduleStats", ConfigKind.BOOL, false),
        Triple("moduleSidebars", ConfigKind.STRING_LIST, listOf("home")),
        Triple("mcStoreCommand", ConfigKind.BOOL, true),
        Triple("mcCreditsCommand", ConfigKind.BOOL, true),
        Triple("mcJoinNotifications", ConfigKind.BOOL, true),
        Triple("mcStoreMenu", ConfigKind.BOOL, true),
        Triple("mcAdminCommands", ConfigKind.BOOL, true),
        Triple("mcPlaceholders", ConfigKind.BOOL, true),
        Triple("mcLuckPerms", ConfigKind.BOOL, true),
        Triple("mcBroadcast", ConfigKind.BOOL, false),
        Triple("mcBroadcastTemplate", ConfigKind.STRING, MarketConfig.DEFAULT_BROADCAST_TEMPLATE),
        Triple("mcDisabledAdminCommands", ConfigKind.STRING_LIST, emptyList<String>()),
        Triple("mcVaultMode", ConfigKind.ENUM, "OFF"),
        Triple("mcVaultRate", ConfigKind.DOUBLE, 1.0),
        Triple("mcVaultDirection", ConfigKind.ENUM, "BOTH")
    )

    private fun defaultsJson(): JsonObject = JsonObject(gson.toJson(MarketConfig()))

    @Test
    fun `the key table equals the documented table, the class and its defaults`() {
        val keys = MarketConfigKeys.all

        assertEquals(documented.map { it.first }.sorted(), keys.map { it.name }.sorted(), "same key set")
        assertEquals(keys.size, keys.map { it.name }.toSet().size, "no duplicate key")

        val fields = MarketConfig::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(keys.map { it.name }.toSet(), fields, "every constructor property is in the table and the other way round")

        val defaults = defaultsJson()

        for ((name, kind, default) in documented) {
            val key = MarketConfigKeys.byName.getValue(name)

            assertEquals(kind, key.kind, "$name kind")
            assertEquals(default, key.default, "$name table default")

            val actual = defaults.getValue(name)
            val expected: Any? = when (default) {
                is List<*> -> io.vertx.core.json.JsonArray(default)
                else -> default
            }

            if (expected is Number) assertEquals(expected.toDouble(), (actual as Number).toDouble(), "$name class default")
            else assertEquals(expected, actual, "$name class default")
        }
    }

    @Test
    fun `every default passes its own check`() {
        for (key in MarketConfigKeys.all) {
            val value = defaultsJson().getValue(key.name)

            assertTrue(MarketConfigKeys.typeMatches(key, value), "${key.name} type")

            if (key.kind == ConfigKind.ENUM) assertTrue(value in key.enumValues, "${key.name} enum")
            else assertNull(key.check(value), "${key.name} default is in range")
        }
    }

    // name, valid values, invalid values: one row per ranged key (bounds are inclusive).
    private data class Range(val name: String, val valid: List<Any>, val invalid: List<Any>)

    private val ranges = listOf(
        Range("vatPercent", listOf(0, 0.0, 20.5, 100), listOf(-0.01, 100.01, Double.NaN)),
        Range("cashbackPercent", listOf(0, 100.0), listOf(-1, 100.5)),
        Range("exchangeRate", listOf(0.5, 1000), listOf(0, 0.0, -1)),
        Range("exchangeRateAutoIntervalHours", listOf(1, 168), listOf(0, 169)),
        Range("minimumOrderAmount", listOf(0, 10.5), listOf(-0.5)),
        Range("mailOrderDeliveredDelayMinutes", listOf(0, 1440), listOf(-1, 1441)),
        Range("orderExpiryMinutes", listOf(5, 10080), listOf(4, 10081)),
        Range("bankTransferExpiryHours", listOf(1, 720), listOf(0, 721)),
        Range("creditValue", listOf(0.01, 5), listOf(0.009, 0, -1)),
        Range("creditTopUpMin", listOf(0.01, 50), listOf(0.0, -1)),
        Range("creditTopUpMax", listOf(1.0, 99999), listOf(0, -5)),
        Range("deliveryMaxAttempts", listOf(1, 50), listOf(0, 51)),
        Range("deliveryOnlineWaitDays", listOf(0, 365), listOf(-1, 366)),
        Range("deliveryAckTimeoutSeconds", listOf(5, 3600), listOf(4, 3601)),
        Range("subscriptionGraceDays", listOf(0, 60), listOf(-1, 61)),
        Range("subscriptionReminderDays", listOf(0, 60), listOf(-1, 61)),
        Range("creatorEarningHoldDays", listOf(0, 365), listOf(-1, 366)),
        Range("checkoutRateLimitPerMinute", listOf(0, 1, 100000), listOf(-1, 100001)),
        Range("quoteRateLimitPerMinute", listOf(1, 100000), listOf(0, 100001)),
        Range("couponLockThreshold", listOf(1, 1000), listOf(0, 1001)),
        Range("couponLockMinutes", listOf(1, 10080), listOf(0, 10081)),
        Range("storePageSize", listOf(1, 100), listOf(0, 101)),
        Range("moduleRecentBuyersCount", listOf(1, 50), listOf(0, 51)),
        Range("moduleTopSupportersCount", listOf(1, 50), listOf(0, 51)),
        Range("mcVaultRate", listOf(0.5, 100), listOf(0, -1)),
        Range("creditName", listOf("", "x".repeat(32)), listOf("x".repeat(33))),
        Range("storeName", listOf("a", "x".repeat(64)), listOf("x".repeat(65))),
        Range("storeTimeZone", listOf("", "Europe/Istanbul", "UTC"), listOf("Mars/Olympus", "istanbul")),
        Range("mailReplyTo", listOf("", "a@b.co"), listOf("nope", "a@b", "a b@c.de")),
        Range("invoiceSeries", listOf("A", "INV", "ABCD1234"), listOf("", "inv", "TEST", "ABCDEFGHI", "IN-V")),
        Range("invoiceCreditNoteSeries", listOf("CN", "X9"), listOf("", "cn", "TEST", "TOOLONG123")),
        Range("invoiceLocale", listOf("", "tr", "en-US", "ru"), listOf("TR", "english", "e")),
        Range("chargebackActions", listOf("[]", """[{"type":"COMMAND"}]"""), listOf("{}", "not json", "")),
        Range("mcBroadcastTemplate", listOf("{player} bought {product}"), listOf("", "   ")),
        Range("additionalCurrencies", listOf(emptyList<String>(), listOf("USD", "EUR")), listOf(listOf("usd"), listOf("XXXX"), listOf("USD", "USD"), listOf("ZZZ"))),
        Range("moduleSidebars", listOf(emptyList<String>(), listOf("home", "profile")), listOf(listOf("admin"), listOf("home", "home"))),
        Range("mcDisabledAdminCommands", listOf(emptyList<String>(), listOf("give-credits", "purchases")), listOf(listOf("op"), listOf("purchases", "purchases"))),
        Range("mailDisabledKinds", listOf(emptyList<String>(), listOf("ORDER_CONFIRMED")), listOf(listOf(""), listOf("x".repeat(65))))
    )

    @Test
    fun `table-driven ranges accept the valid and refuse the invalid values`() {
        val current = defaultsJson()
        var checked = 0

        for (range in ranges) {
            val scope = MarketConfigKeys.byName.getValue(range.name).scope

            for (value in range.valid) {
                val errors = MarketConfigKeys.validate(JsonObject().put(range.name, value), current, scope)

                assertFalse(errors.containsKey(range.name), "${range.name}=$value must be valid, got $errors")
                checked++
            }

            for (value in range.invalid) {
                val errors = MarketConfigKeys.validate(JsonObject().put(range.name, value), current, scope)

                assertTrue(errors.containsKey(range.name), "${range.name}=$value must be refused")
                checked++
            }
        }

        assertTrue(checked > 100, "the table ran ($checked cases)")
    }

    @Test
    fun `enum keys accept their values only`() {
        val current = defaultsJson()

        for (key in MarketConfigKeys.all.filter { it.kind == ConfigKind.ENUM }) {
            for (v in key.enumValues) {
                assertTrue(MarketConfigKeys.validate(JsonObject().put(key.name, v), current, key.scope).isEmpty(), "${key.name}=$v")
            }

            assertEquals(
                mapOf(key.name to MarketConfigKeys.INVALID_VALUE),
                MarketConfigKeys.validate(JsonObject().put(key.name, "NOPE"), current, key.scope)
            )
        }

        assertEquals(setOf("SINGLE", "DISPLAY", "MULTI"), MarketConfigKeys.byName.getValue("currencyMode").enumValues.toSet())
        assertEquals(setOf("OFF", "OPTIONAL", "REQUIRED"), MarketConfigKeys.byName.getValue("billingInfoMode").enumValues.toSet())
        assertEquals(setOf("MONTH", "ALL_TIME"), MarketConfigKeys.byName.getValue("moduleTopSupportersPeriod").enumValues.toSet())
        assertEquals(setOf("OFF", "PROVIDER", "CONVERT"), MarketConfigKeys.byName.getValue("mcVaultMode").enumValues.toSet())
        assertEquals(setOf("BOTH", "TO_SERVER", "TO_CREDITS"), MarketConfigKeys.byName.getValue("mcVaultDirection").enumValues.toSet())
    }

    @Test
    fun `wrong json types are refused for every kind`() {
        val current = defaultsJson()

        for (key in MarketConfigKeys.all) {
            val wrong: Any = when (key.kind) {
                ConfigKind.BOOL -> "yes"
                ConfigKind.INT -> 1.5
                ConfigKind.DOUBLE -> "1"
                ConfigKind.STRING, ConfigKind.ENUM -> 7
                ConfigKind.STRING_LIST -> "home"
            }

            assertEquals(
                MarketConfigKeys.INVALID_TYPE,
                MarketConfigKeys.validate(JsonObject().put(key.name, wrong), current, key.scope)[key.name],
                key.name
            )
        }
    }

    @Test
    fun `invoice series rules`() {
        val current = defaultsJson()

        assertEquals("SAME_AS_OTHER_SERIES", MarketConfigKeys.validate(JsonObject().put("invoiceSeries", "CN"), current)["invoiceSeries"])
        assertEquals("SAME_AS_OTHER_SERIES", MarketConfigKeys.validate(JsonObject().put("invoiceCreditNoteSeries", "INV"), current)["invoiceCreditNoteSeries"])
        assertTrue(MarketConfigKeys.validate(JsonObject().put("invoiceSeries", "SHOP").put("invoiceCreditNoteSeries", "RET"), current).isEmpty())
        assertEquals(MarketConfigKeys.INVALID_VALUE, MarketConfigKeys.validate(JsonObject().put("invoiceSeries", "TEST"), current)["invoiceSeries"], "TEST is reserved")
    }

    @Test
    fun `cross field rules`() {
        val current = defaultsJson()

        assertEquals("CONTAINS_BASE_CURRENCY", MarketConfigKeys.validate(JsonObject().put("additionalCurrencies", io.vertx.core.json.JsonArray().add("TRY")), current)["additionalCurrencies"])
        assertEquals(MarketConfigKeys.OUT_OF_RANGE, MarketConfigKeys.validate(JsonObject().put("creditTopUpMin", 5.0).put("creditTopUpMax", 2.0), current, ConfigScope.CREDIT)["creditTopUpMax"])
        assertEquals("REQUIRES_TOP_UP", MarketConfigKeys.validate(JsonObject().put("creditTopUpFreeAmount", true), current, ConfigScope.CREDIT)["creditTopUpFreeAmount"])
        assertTrue(MarketConfigKeys.validate(JsonObject().put("creditTopUpFreeAmount", true).put("creditTopUpEnabled", true), current, ConfigScope.CREDIT).isEmpty())

        val creditsOff = current.copy().put("creditsEnabled", false)

        assertEquals("REQUIRES_CREDITS", MarketConfigKeys.validate(JsonObject().put("onlyAcceptCredits", true), creditsOff, ConfigScope.CREDIT)["onlyAcceptCredits"])
    }

    @Test
    fun `currency codes follow ISO 4217`() {
        assertTrue(MarketConfigKeys.isIsoCurrency("USD"))
        assertTrue(MarketConfigKeys.isIsoCurrency("JPY"))
        assertFalse(MarketConfigKeys.isIsoCurrency("usd"))
        assertFalse(MarketConfigKeys.isIsoCurrency("US"))
        assertFalse(MarketConfigKeys.isIsoCurrency("ZZZ"))
        assertTrue(MarketConfigKeys.isTimeZone("America/New_York"))
        assertFalse(MarketConfigKeys.isTimeZone("Nowhere/City"))
    }

    @Test
    fun `migration 1 to 2 keeps every existing value and adds the defaults`() {
        // A version 1 file as an earlier release wrote it, with non-default values everywhere.
        val v1 = JsonObject()
            .put("storeName", "Diamond Shop").put("storeDescription", "d").put("currency", "EUR").put("statsCurrency", "USD")
            .put("exchangeRateMode", "MANUAL").put("exchangeRate", 1.37).put("exchangeRateUpdatedAt", 1700000000000L)
            .put("exchangeRateAutoIntervalHours", 12).put("vatPercent", 8.0).put("showVatInPrice", false)
            .put("testMode", true).put("allowGuestCheckout", false).put("minimumOrderAmount", 5.5).put("removeCents", true)
            .put("showBestsellers", false).put("showFeaturedProducts", false).put("showComparisons", false)
            .put("sendEmailAfterPurchase", false).put("combineDiscountsAndCoupons", false).put("creditsEnabled", false)
            .put("creditName", "Gems").put("cashbackPercent", 3.5).put("onlyAcceptCredits", true).put("version", 1)

        val migration = MarketConfigMigration1to2()

        assertTrue(migration.isMigratable(1))
        assertFalse(migration.isMigratable(2))
        assertEquals(1, migration.from)
        assertEquals(2, migration.to)

        // What PluginConfigManager does: parse, serialise, set the new version, run the migration, parse again.
        val parsed = gson.fromJson(v1.toString(), MarketConfig::class.java)
        val asJson = JsonObject(gson.toJson(parsed))

        asJson.put("version", migration.to)
        migration.migrate(asJson)

        val migrated = gson.fromJson(asJson.toString(), MarketConfig::class.java)

        assertEquals(2, migrated.version)

        val after = JsonObject(gson.toJson(migrated))

        for (name in v1.fieldNames().filter { it != "version" }) {
            val before = v1.getValue(name)

            if (before is Number) assertEquals(before.toDouble(), (after.getValue(name) as Number).toDouble(), name)
            else assertEquals(before, after.getValue(name), name)
        }

        val defaults = defaultsJson()

        for (key in MarketConfigKeys.all.filter { !v1.containsKey(it.name) }) {
            assertEquals(defaults.getValue(key.name), after.getValue(key.name), "${key.name} takes its default")
        }
    }

    @Test
    fun `the config file renders as HOCON and reads back unchanged`() {
        val custom = JsonObject(gson.toJson(MarketConfig()))
            .put("moduleSidebars", io.vertx.core.json.JsonArray().add("home").add("profile"))
            .put("storeTimeZone", "Europe/Istanbul").put("mcVaultMode", "CONVERT").put("version", 2)

        val text = HoconWriter.render(custom, MarketConfig::class.java)
        val read = JsonObject(ConfigFactory.parseString(text).root().unwrapped())
        val back = gson.fromJson(read.toString(), MarketConfig::class.java)

        assertEquals(listOf("home", "profile"), back.moduleSidebars)
        assertEquals("Europe/Istanbul", back.storeTimeZone)
        assertEquals(VaultMode.CONVERT, back.mcVaultMode)
        assertEquals(2, back.version)
        assertEquals(MarketConfig.DEFAULT_BROADCAST_TEMPLATE, back.mcBroadcastTemplate)
    }
}
