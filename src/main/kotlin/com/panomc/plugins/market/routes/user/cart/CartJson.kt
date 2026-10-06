package com.panomc.plugins.market.routes.user.cart

import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.api.checkout.checkoutService
import com.panomc.plugins.market.service.CartService
import com.panomc.plugins.market.service.Quote
import com.panomc.plugins.market.service.QuoteCaller
import com.panomc.plugins.market.service.QuoteInput
import com.panomc.plugins.market.service.QuoteMessage
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The `{cart, quote}` answer every cart endpoint returns (04 section 4): the cart as stored and the full `Quote` of it
 * (MK-072); the messages of a merge ride along in `quote.messages`.
 */
internal object CartJson {
    fun render(view: CartService.CartView, quote: Quote): Map<String, Any?> {
        val cart = view.cart

        return mapOf(
            "cart" to JsonObject()
                .put(
                    "items",
                    JsonArray(
                        view.lines.map {
                            JsonObject()
                                .put("id", it.item.id)
                                .put("productId", it.item.productId)
                                .put("variantId", it.item.variantId)
                                .put("quantity", it.item.quantity)
                                .put("fieldValues", JsonObject(LinkedHashMap(it.fieldValues)))
                                .put("targetServerId", it.item.targetServerId)
                        }
                    )
                )
                .put("couponCode", cart.couponCode)
                .put("creatorCode", cart.creatorCode)
                .put("recipientUsername", cart.recipientUsername)
                .put("giftMessage", cart.giftMessage)
                .put("shippingAddressId", cart.shippingAddressId)
                .put("shippingMethodId", cart.shippingMethodId)
                .put("currency", cart.currency),
            "quote" to quote.toJson().also { json ->
                // the merge / cart messages stay next to the quote's own (06 section 2.3), never twice
                val own = quote.messages.toSet()
                val extra = view.messages.map { QuoteMessage(it.code, it.level, it.lineKey) }.filter { it !in own }

                if (extra.isNotEmpty()) {
                    val all = json.getJsonArray("messages") ?: JsonArray()

                    extra.forEach { all.add(it.toJson()) }
                    json.put("messages", all)
                }
            }
        )
    }

    /** The answer of a cart route: the cart as stored plus the real quote of the stored cart (`CheckoutService.quote`, MK-072). */
    suspend fun answer(plugin: MarketPlugin, userId: Long, view: CartService.CartView): Map<String, Any?> {
        val databaseManager = plugin.applicationContext.getBean(DatabaseManager::class.java)
        val quote = checkoutService(plugin).quote(QuoteInput(items = null), QuoteCaller(userId), databaseManager.getSqlClient())

        return render(view, quote)
    }
}
