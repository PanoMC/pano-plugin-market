package com.panomc.plugins.market.routes.user.cart

import com.panomc.plugins.market.service.CartService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The `{cart, quote}` answer every cart endpoint returns (04 section 4). The `quote` of this slice is the cart's own
 * advice: per line `maxQuantity` and `errors` (the stock and `maxQuantityPerOrder` the buyer must respect), plus the
 * messages of a merge. The pricing quote of `POST /checkout/quote` (MK-072) replaces it with the full shape.
 */
internal object CartJson {
    fun render(view: CartService.CartView): Map<String, Any?> {
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
            "quote" to JsonObject()
                .put(
                    "lines",
                    JsonArray(
                        view.lines.map {
                            JsonObject()
                                .put("lineKey", it.item.lineKey)
                                .put("productId", it.item.productId)
                                .put("variantId", it.item.variantId)
                                .put("quantity", it.item.quantity)
                                .put("maxQuantity", it.maxQuantity)
                                .put("fieldValues", JsonObject(LinkedHashMap(it.fieldValues)))
                                .put("targetServerId", it.item.targetServerId)
                                .put("errors", JsonArray(it.errors))
                        }
                    )
                )
                .put(
                    "messages",
                    JsonArray(
                        view.messages.map {
                            JsonObject().put("code", it.code).put("level", it.level).apply { it.lineKey?.let { key -> put("lineKey", key) } }
                        }
                    )
                )
                .put("canCheckout", view.canCheckout)
        )
    }
}
