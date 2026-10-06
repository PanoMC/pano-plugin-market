package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.util.CurrencyType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Everything a panel list page needs besides its rows: the currency symbols and the feature flags (04 section 8).
 * Defaults are the documented defaults of 00 section 12 for the keys `MarketConfig` does not have yet (MK-022 adds them
 * and fills this value from the real keys).
 */
data class MarketContextInput(
    val currency: String,
    val currencySymbol: String,
    val statsCurrency: String,
    val statsCurrencySymbol: String,
    val currencyMode: String = "SINGLE",
    val additionalCurrencies: List<String> = emptyList(),
    val creditsEnabled: Boolean,
    val creditName: String,
    val creditValue: Double = 1.0,
    val allowMixedCreditPayment: Boolean = false,
    val vatPercent: Double,
    val showVatInPrice: Boolean,
    val testMode: Boolean,
    val storeTimeZone: String = "",
    val revokeOnRefund: Boolean = true,
    val revokeOnChargeback: Boolean = true,
    val billingInfoMode: String = "OPTIONAL",
    val invoiceEnabled: Boolean = true,
    val mailEnabled: Boolean,
    val shippingEnabled: Boolean = false,
    val storeUrl: String,
    val runtimeState: String
)

/** The ISO currencies the panel can pick (code, symbol, minor-unit exponent). */
fun marketCurrencies(): List<Map<String, Any?>> =
    CurrencyType.entries.map { mapOf("code" to it.name, "symbol" to it.symbol, "exponent" to 2) }

/** The `GET /context` body without `result`. `productMetaSchemas` is only present for [includeProductMeta] (CAT holders). */
fun marketContextBody(input: MarketContextInput, includeProductMeta: Boolean): Map<String, Any?> {
    val body = linkedMapOf<String, Any?>(
        "currency" to input.currency,
        "currencySymbol" to input.currencySymbol,
        "statsCurrency" to input.statsCurrency,
        "statsCurrencySymbol" to input.statsCurrencySymbol,
        "currencyMode" to input.currencyMode,
        "additionalCurrencies" to input.additionalCurrencies,
        "currencies" to marketCurrencies(),
        "creditsEnabled" to input.creditsEnabled,
        "creditName" to input.creditName,
        "creditValue" to input.creditValue,
        "allowMixedCreditPayment" to input.allowMixedCreditPayment,
        "vatPercent" to input.vatPercent,
        "showVatInPrice" to input.showVatInPrice,
        "testMode" to input.testMode,
        "storeTimeZone" to input.storeTimeZone,
        "revokeOnRefund" to input.revokeOnRefund,
        "revokeOnChargeback" to input.revokeOnChargeback,
        "billingInfoMode" to input.billingInfoMode,
        "invoiceEnabled" to input.invoiceEnabled,
        "mailEnabled" to input.mailEnabled,
        "shippingEnabled" to input.shippingEnabled,
        "storeUrl" to input.storeUrl,
        "runtimeState" to input.runtimeState
    )

    if (includeProductMeta) body["productMetaSchemas"] = emptyList<Any>()

    return body
}

/** `<website-url>/store`, or an empty string while the platform has no public URL. */
fun marketStoreUrl(websiteUrl: String): String =
    websiteUrl.trim().trimEnd('/').let { if (it.isEmpty()) "" else "$it/store" }

/**
 * `GET /api/panel/market/context` (04 section 8): any market node. Answers while the market is not READY and reports
 * the state, so the panel can show its banner instead of an error page.
 */
@Endpoint
class PanelGetContextAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/context", RouteType.GET))

    override val nodes: Set<MarketNode> = emptySet()

    override val exemptFromRuntimeGate = true

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val config = currentConfig(plugin)
        val websiteUrl = runCatching { plugin.applicationContext.getBean(ConfigManager::class.java).config.websiteUrl }
            .getOrDefault("")

        val input = marketContextInput(
            config, storeUrl = marketStoreUrl(websiteUrl), mailEnabled = MarketRuntime.capabilities.mail, runtimeState = MarketRuntime.state.name
        )

        return Successful(marketContextBody(input, includeProductMeta = has(context, MarketNode.CATALOG)))
    }
}

/**
 * The context of [config]. Every key the body documents comes from the configuration: the handler used to fill only the currency, credit-name, VAT and test-mode
 * keys and left the rest at the data class defaults, so the context said `currencyMode = SINGLE` with no additional currency whatever the settings were, and the
 * product form never offered the per-currency price grid (found by the panel browser scenario 71).
 */
internal fun marketContextInput(config: MarketConfig, storeUrl: String, mailEnabled: Boolean, runtimeState: String) = MarketContextInput(
    currency = config.currency.name,
    currencySymbol = config.currency.symbol,
    statsCurrency = config.statsCurrency.name,
    statsCurrencySymbol = config.statsCurrency.symbol,
    currencyMode = config.currencyMode.name,
    additionalCurrencies = config.additionalCurrencies,
    creditsEnabled = config.creditsEnabled,
    creditName = config.creditName,
    creditValue = config.creditValue,
    allowMixedCreditPayment = config.allowMixedCreditPayment,
    vatPercent = config.vatPercent,
    showVatInPrice = config.showVatInPrice,
    testMode = config.testMode,
    storeTimeZone = config.storeTimeZone,
    revokeOnRefund = config.revokeOnRefund,
    revokeOnChargeback = config.revokeOnChargeback,
    billingInfoMode = config.billingInfoMode.name,
    invoiceEnabled = config.invoiceEnabled,
    mailEnabled = mailEnabled,
    storeUrl = storeUrl,
    runtimeState = runtimeState
)

/** The market configuration when its manager exists; the defaults before the plugin initialised (setup pending). */
internal fun currentConfig(plugin: MarketPlugin): MarketConfig = try {
    @Suppress("UNCHECKED_CAST")
    (plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>).config
} catch (e: Exception) {
    MarketConfig()
}
