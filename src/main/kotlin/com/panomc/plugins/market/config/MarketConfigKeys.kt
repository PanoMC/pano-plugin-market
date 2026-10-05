package com.panomc.plugins.market.config

import com.panomc.plugins.market.util.CurrencyType
import com.panomc.plugins.market.util.ExchangeRateMode
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.time.ZoneId
import java.util.Currency

/**
 * The key table of `MarketConfig` (00 section 12): name, type, documented default, who may write it and the range
 * check. One table drives the settings endpoints' schema and validation, and the table-driven `MarketConfigTest`
 * compares it to the real `MarketConfig` defaults, so a key cannot exist in one place only.
 */
enum class ConfigKind { BOOL, INT, DOUBLE, STRING, ENUM, STRING_LIST }

/** Which endpoint writes the key: `POST /settings` (GENERAL), `POST /settings/credits` (CREDIT) or nobody (SYSTEM). */
enum class ConfigScope { GENERAL, CREDIT, SYSTEM }

class ConfigKey(
    val name: String,
    val kind: ConfigKind,
    val default: Any?,
    val scope: ConfigScope = ConfigScope.GENERAL,
    val enumValues: List<String> = emptyList(),
    /** Range / format check of a value of the right [kind]; returns an error code or null. */
    val check: (Any) -> String? = { null }
)

object MarketConfigKeys {
    const val OUT_OF_RANGE = "OUT_OF_RANGE"
    const val INVALID_TYPE = "INVALID_TYPE"
    const val INVALID_VALUE = "INVALID_VALUE"
    const val UNKNOWN_KEY = "UNKNOWN_KEY"
    const val TOO_LONG = "TOO_LONG"

    val INVOICE_SERIES = Regex("^[A-Z0-9]{1,8}$")
    private val LOCALE = Regex("^[a-z]{2,3}(-[A-Za-z]{2,4})?$")
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /** The host sidebars a plugin can address (15 section 4.5). */
    val SIDEBARS = listOf("home", "profile")

    /** In-game admin sub-commands that can be switched off (19 section 9). */
    val ADMIN_COMMANDS = listOf("give-credits", "take-credits", "set-credits", "grant-product", "purchases")

    private fun bool(name: String, default: Boolean, scope: ConfigScope = ConfigScope.GENERAL) =
        ConfigKey(name, ConfigKind.BOOL, default, scope)

    private fun int(name: String, default: Int, min: Int, max: Int, scope: ConfigScope = ConfigScope.GENERAL) =
        ConfigKey(name, ConfigKind.INT, default, scope) { v -> if ((v as Number).toLong() in min..max) null else OUT_OF_RANGE }

    private fun dbl(
        name: String, default: Double, min: Double, max: Double = Double.MAX_VALUE,
        scope: ConfigScope = ConfigScope.GENERAL
    ) = ConfigKey(name, ConfigKind.DOUBLE, default, scope) { v ->
        val d = (v as Number).toDouble()
        if (d.isFinite() && d >= min && d <= max) null else OUT_OF_RANGE
    }

    private fun str(name: String, default: String, maxLength: Int, scope: ConfigScope = ConfigScope.GENERAL,
                    extra: (String) -> String? = { null }) =
        ConfigKey(name, ConfigKind.STRING, default, scope) { v ->
            val s = v as String
            if (s.length > maxLength) TOO_LONG else extra(s)
        }

    private fun <E : Enum<E>> enm(name: String, default: E, values: List<String>, scope: ConfigScope = ConfigScope.GENERAL) =
        ConfigKey(name, ConfigKind.ENUM, default.name, scope, values)

    private fun list(name: String, default: List<String>, check: (List<String>) -> String?) =
        ConfigKey(name, ConfigKind.STRING_LIST, default) { v -> check(asStrings(v)) }

    private inline fun <reified E : Enum<E>> values() = enumValues<E>().map { it.name }

    fun isIsoCurrency(code: String): Boolean =
        code.length == 3 && code == code.uppercase() && runCatching { Currency.getInstance(code) }.isSuccess

    fun isTimeZone(id: String): Boolean = runCatching { ZoneId.of(id) }.isSuccess

    @Suppress("UNCHECKED_CAST")
    private fun asStrings(value: Any): List<String> = when (value) {
        is JsonArray -> value.list.map { it as String }
        else -> value as List<String>
    }

    val all: List<ConfigKey> = listOf(
        // Store
        str("storeName", "Market", 64),
        str("storeDescription", "", 1000),
        bool("storeEnabled", true),
        str("storeTimeZone", "", 64) { if (it.isEmpty() || isTimeZone(it)) null else INVALID_VALUE },
        enm("currencyMode", CurrencyMode.SINGLE, values<CurrencyMode>()),
        list("additionalCurrencies", emptyList()) { l ->
            when {
                l.size > 20 -> OUT_OF_RANGE
                l.any { !isIsoCurrency(it) } -> INVALID_VALUE
                l.toSet().size != l.size -> INVALID_VALUE
                else -> null
            }
        },
        enm("multiCurrencyFallback", MultiCurrencyFallback.CONVERT, values<MultiCurrencyFallback>()),
        enm("currency", CurrencyType.TRY, values<CurrencyType>()),
        enm("statsCurrency", CurrencyType.TRY, values<CurrencyType>()),
        enm("exchangeRateMode", ExchangeRateMode.AUTO, values<ExchangeRateMode>()),
        dbl("exchangeRate", 1.0, 0.000001, 1_000_000_000.0),
        ConfigKey("exchangeRateUpdatedAt", ConfigKind.INT, 0L, ConfigScope.SYSTEM) { v -> if ((v as Number).toLong() >= 0) null else OUT_OF_RANGE },
        int("exchangeRateAutoIntervalHours", 6, 1, 168),
        dbl("vatPercent", 20.0, 0.0, 100.0),
        bool("showVatInPrice", true),
        bool("testMode", false),
        bool("allowGuestCheckout", true),
        dbl("minimumOrderAmount", 0.0, 0.0, 1_000_000_000.0),
        bool("removeCents", false),
        bool("showBestsellers", true),
        bool("showFeaturedProducts", true),
        bool("showComparisons", true),
        bool("sendEmailAfterPurchase", true),
        list("mailDisabledKinds", emptyList()) { l ->
            if (l.size > 64 || l.any { it.isBlank() || it.length > 64 }) INVALID_VALUE else null
        },
        bool("mailAttachInvoice", true),
        str("mailReplyTo", "", 254) { if (it.isEmpty() || EMAIL.matches(it)) null else INVALID_VALUE },
        int("mailOrderDeliveredDelayMinutes", 10, 0, 1440),
        bool("combineDiscountsAndCoupons", true),
        bool("allowGiftPurchase", true),
        enm("billingInfoMode", BillingInfoMode.OPTIONAL, values<BillingInfoMode>()),
        bool("legalTextRequired", false),
        int("orderExpiryMinutes", 60, 5, 10_080),
        int("bankTransferExpiryHours", 72, 1, 720),
        bool("autoRefundDuplicatePayments", true),
        // Invoices
        bool("invoiceEnabled", true),
        str("invoiceSeries", "INV", 8) { if (INVOICE_SERIES.matches(it) && it != "TEST") null else INVALID_VALUE },
        str("invoiceSellerName", "", 120),
        str("invoiceSellerAddress", "", 500),
        str("invoiceSellerTaxOffice", "", 120),
        str("invoiceSellerTaxNumber", "", 64),
        str("invoiceFooter", "", 1000),
        str("invoiceCreditNoteSeries", "CN", 8) { if (INVOICE_SERIES.matches(it) && it != "TEST") null else INVALID_VALUE },
        bool("invoiceCreditOrders", false),
        str("invoiceLocale", "", 16) { if (it.isEmpty() || LOCALE.matches(it)) null else INVALID_VALUE },
        bool("invoiceShowLogo", true),
        // Credits
        bool("creditsEnabled", true, ConfigScope.CREDIT),
        str("creditName", "", 32, ConfigScope.CREDIT),
        dbl("cashbackPercent", 0.0, 0.0, 100.0, ConfigScope.CREDIT),
        bool("onlyAcceptCredits", false, ConfigScope.CREDIT),
        dbl("creditValue", 1.0, 0.01, 1_000_000_000.0, ConfigScope.CREDIT),
        bool("allowMixedCreditPayment", false, ConfigScope.CREDIT),
        bool("creditTopUpEnabled", false, ConfigScope.CREDIT),
        bool("creditTopUpFreeAmount", false, ConfigScope.CREDIT),
        dbl("creditTopUpMin", 1.0, 0.01, 1_000_000_000.0, ConfigScope.CREDIT),
        dbl("creditTopUpMax", 10000.0, 0.01, 1_000_000_000.0, ConfigScope.CREDIT),
        bool("revokeOnRefund", true),
        bool("revokeOnChargeback", true),
        // Delivery, subscriptions
        int("deliveryMaxAttempts", 5, 1, 50),
        int("deliveryOnlineWaitDays", 0, 0, 365),
        int("deliveryAckTimeoutSeconds", 30, 5, 3600),
        int("subscriptionGraceDays", 3, 0, 60),
        int("subscriptionReminderDays", 3, 0, 60),
        bool("subscriptionManualFallback", true),
        // Abuse, chargebacks
        bool("autoBlockOnChargeback", true),
        bool("revokeCreditOrdersOnTopUpChargeback", true),
        int("creatorEarningHoldDays", 14, 0, 365),
        str("chargebackActions", "[]", 8000) { if (isJsonArray(it)) null else INVALID_VALUE },
        int("checkoutRateLimitPerMinute", 6, 0, 100_000),
        int("quoteRateLimitPerMinute", 60, 1, 100_000),
        int("couponLockThreshold", 5, 1, 1000),
        int("couponLockMinutes", 15, 1, 10_080),
        bool("allowPrivateWebhookTargets", false),
        // Storefront
        int("storePageSize", 24, 1, 100),
        bool("moduleRecentBuyers", true),
        int("moduleRecentBuyersCount", 10, 1, 50),
        bool("moduleRecentBuyersShowAmount", false),
        bool("moduleTopSupporters", true),
        enm("moduleTopSupportersPeriod", TopSupportersPeriod.MONTH, values<TopSupportersPeriod>()),
        int("moduleTopSupportersCount", 5, 1, 50),
        bool("moduleGoal", true),
        bool("moduleSaleBadges", true),
        bool("moduleSaleCountdown", true),
        bool("moduleStats", false),
        list("moduleSidebars", listOf("home")) { l ->
            if (l.any { it !in SIDEBARS } || l.toSet().size != l.size) INVALID_VALUE else null
        },
        // In-game (19 section 9)
        bool("mcStoreCommand", true),
        bool("mcCreditsCommand", true),
        bool("mcJoinNotifications", true),
        bool("mcStoreMenu", true),
        bool("mcAdminCommands", true),
        bool("mcPlaceholders", true),
        bool("mcLuckPerms", true),
        bool("mcBroadcast", false),
        str("mcBroadcastTemplate", MarketConfig.DEFAULT_BROADCAST_TEMPLATE, 256) { if (it.isBlank()) INVALID_VALUE else null },
        list("mcDisabledAdminCommands", emptyList()) { l ->
            if (l.any { it !in ADMIN_COMMANDS } || l.toSet().size != l.size) INVALID_VALUE else null
        },
        enm("mcVaultMode", VaultMode.OFF, values<VaultMode>()),
        dbl("mcVaultRate", 1.0, 0.000001, 1_000_000_000.0),
        enm("mcVaultDirection", VaultDirection.BOTH, values<VaultDirection>())
    )

    val byName: Map<String, ConfigKey> = all.associateBy { it.name }

    /** Keys a given endpoint may write. */
    fun writable(scope: ConfigScope): List<ConfigKey> = all.filter { it.scope == scope }

    private fun isJsonArray(text: String): Boolean = runCatching { JsonArray(text) }.isSuccess

    /** Checks the Kotlin type of a body value against the key's kind (an integral number for INT, any number for DOUBLE). */
    fun typeMatches(key: ConfigKey, value: Any?): Boolean = when (key.kind) {
        ConfigKind.BOOL -> value is Boolean
        ConfigKind.INT -> value is Int || value is Long || value is Short || value is Byte
        ConfigKind.DOUBLE -> value is Number
        ConfigKind.STRING -> value is String
        ConfigKind.ENUM -> value is String
        ConfigKind.STRING_LIST -> (value is JsonArray && value.list.all { it is String }) ||
            (value is List<*> && value.all { it is String })
    }

    /**
     * Validates the keys present in [body] (a partial update) against the table and, on the merged result with
     * [current], the cross-field rules whose keys are touched. Returns `{field: CODE}`; empty = valid.
     * Keys outside [scope] (and unknown keys) are refused, as is `version`.
     */
    fun validate(body: JsonObject, current: JsonObject, scope: ConfigScope = ConfigScope.GENERAL): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        val allowed = writable(scope).associateBy { it.name }

        for (name in body.fieldNames()) {
            val key = allowed[name]

            if (key == null) {
                errors[name] = UNKNOWN_KEY

                continue
            }

            val value = body.getValue(name)

            if (!typeMatches(key, value)) {
                errors[name] = INVALID_TYPE

                continue
            }

            val code = if (key.kind == ConfigKind.ENUM) {
                if ((value as String) in key.enumValues) null else INVALID_VALUE
            } else {
                key.check(value!!)
            }

            if (code != null) errors[name] = code
        }

        // Cross-field rules read typed values: only meaningful once every single field is valid.
        if (errors.isNotEmpty()) return errors

        val merged = current.copy().mergeIn(body)

        fun touched(vararg names: String) = names.any { body.containsKey(it) }
        fun fail(field: String, code: String) { errors.putIfAbsent(field, code) }

        if (touched("invoiceSeries", "invoiceCreditNoteSeries") &&
            merged.getString("invoiceSeries") == merged.getString("invoiceCreditNoteSeries")
        ) {
            fail(if (body.containsKey("invoiceCreditNoteSeries")) "invoiceCreditNoteSeries" else "invoiceSeries", "SAME_AS_OTHER_SERIES")
        }

        if (touched("additionalCurrencies", "currency")) {
            val extra = merged.getValue("additionalCurrencies")
            val base = merged.getValue("currency")?.toString()

            if (extra is JsonArray && extra.list.any { it == base }) fail("additionalCurrencies", "CONTAINS_BASE_CURRENCY")
        }

        if (touched("creditTopUpMin", "creditTopUpMax") &&
            merged.getDouble("creditTopUpMax") < merged.getDouble("creditTopUpMin")
        ) fail("creditTopUpMax", OUT_OF_RANGE)

        if (touched("creditTopUpFreeAmount", "creditTopUpEnabled") &&
            merged.getBoolean("creditTopUpFreeAmount") && !merged.getBoolean("creditTopUpEnabled")
        ) fail("creditTopUpFreeAmount", "REQUIRES_TOP_UP")

        if (touched("onlyAcceptCredits", "creditsEnabled") &&
            merged.getBoolean("onlyAcceptCredits") && !merged.getBoolean("creditsEnabled")
        ) fail("onlyAcceptCredits", "REQUIRES_CREDITS")

        return errors
    }
}
