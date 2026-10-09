package com.panomc.plugins.market.frontend

import com.panomc.platform.annotation.FallbackPageDefinition
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.frontend.FallbackPage
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.api.OrderRole
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.resolveOrder
import com.panomc.plugins.market.routes.panel.invoice.marketI18n
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.util.MarketLinks
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The built-in page of the target `market.order` (doc 05 section 10.3): where a payment return and an order mail land until a front-end takes
 * the target over. It shows the order's status, its total (to the owner only) and a link back to the store. The request is the one of the order
 * page: `id` (the public id), `token` (the access token of a guest order) and `return` (what the gateway said), all query parameters, because the
 * URL map appends what the target does not name.
 *
 * The order is read through the same access rules as `GET orders/:publicId` ([resolveOrder]): a stranger gets the "not found" text, never a hint
 * that the order exists.
 */
@FallbackPageDefinition
class OrderFallbackPage(private val plugin: MarketPlugin) : FallbackPage(TARGET, TEMPLATE) {
    override suspend fun model(context: RoutingContext): Map<String, Any?> {
        val request = context.request()
        val publicId = request.getParam("id")
        val hint = request.getParam("return")
        val backUrl = MarketLinks.platform.store() ?: "/"
        val siteLocale = runCatching { plugin.applicationContext.getBean(ConfigManager::class.java).config.locale }.getOrNull().orEmpty()

        if (!MarketRuntime.isReady) {
            return OrderPageModel.unavailable(OrderPageModel.texts(siteLocale), backUrl)
        }

        return try {
            val sqlClient = plugin.applicationContext.getBean(DatabaseManager::class.java).getSqlClient()
            val access = resolveOrder(plugin, context, publicId, sqlClient)
            val order = access.order
            val owner = access.role == OrderRole.OWNER
            val status = paymentService(plugin).status(order, owner, sqlClient)
            val locale = order.locale?.takeIf { it.isNotBlank() } ?: siteLocale
            val total = if (owner) marketI18n(plugin).second.money(order.totalPrice, order.currency, locale) else null

            OrderPageModel.found(OrderPageModel.texts(locale), order.publicId.orEmpty(), status.getString("status"), total, hint, backUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // not found, a bad token, the limiter, a database that is away: the visitor sees the same sentence and nothing is learned from it
            LoggerFactory.getLogger("Market:OrderPage").debug("order fallback page: {}", e.javaClass.simpleName)

            OrderPageModel.notFound(OrderPageModel.texts(siteLocale), backUrl)
        }
    }

    companion object {
        /** Without the namespace: the registry gives it `market.order`, the id of `frontend-targets.json`. */
        const val TARGET = "order"
        const val TEMPLATE = "fallback/order.hbs"
    }
}

/** The values of `fallback/order.hbs` and its texts; no platform class, so a test fills them with a status and reads the result. */
object OrderPageModel {
    private val LOCALE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")
    private const val DEFAULT_LOCALE = "en-US"

    /** The text bundle of [locale]: its file laid over the English one, the language alone (`tr-TR` to `tr`) tried second. */
    fun texts(locale: String): Map<String, Any?> {
        val english = read(DEFAULT_LOCALE).orEmpty()

        if (locale == DEFAULT_LOCALE || !LOCALE.matches(locale)) return english

        val own = read(locale) ?: read(locale.substringBefore('-')) ?: return english

        return merge(english, own)
    }

    fun found(texts: Map<String, Any?>, publicId: String, status: String?, total: String?, hint: String?, backUrl: String?): Map<String, Any?> {
        val known = status?.takeIf { section(texts, "statusLabel").containsKey(it) }
        val leadKey = if (known == "PENDING" && hint == "cancel") "PENDING_cancel" else known

        return mapOf(
            "m" to texts, "found" to true, "publicId" to publicId, "statusLabel" to (known?.let { section(texts, "statusLabel")[it] } ?: status.orEmpty()),
            "lead" to (leadKey?.let { section(texts, "lead")[it] } ?: ""), "total" to total, "backUrl" to backUrl
        )
    }

    fun notFound(texts: Map<String, Any?>, backUrl: String?): Map<String, Any?> = mapOf("m" to texts, "found" to false, "backUrl" to backUrl)

    /** The store is not READY: the same page with the "try again" sentence in place of the order. */
    fun unavailable(texts: Map<String, Any?>, backUrl: String?): Map<String, Any?> =
        mapOf("m" to texts + ("notFound" to texts["unavailable"]), "found" to false, "backUrl" to backUrl)

    private fun section(texts: Map<String, Any?>, name: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return texts[name] as? Map<String, Any?> ?: emptyMap()
    }

    private fun read(locale: String): Map<String, Any?>? {
        if (!LOCALE.matches(locale)) return null

        return try {
            OrderPageModel::class.java.classLoader.getResourceAsStream("fallback/order.texts.$locale.json")
                ?.use { JsonObject(it.readBytes().toString(Charsets.UTF_8)).map }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun merge(base: Map<String, Any?>, over: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(base)

        over.forEach { (key, value) ->
            val current = out[key]

            out[key] = if (current is Map<*, *> && value is Map<*, *>) merge(current as Map<String, Any?>, value as Map<String, Any?>) else value
        }

        return out
    }
}
