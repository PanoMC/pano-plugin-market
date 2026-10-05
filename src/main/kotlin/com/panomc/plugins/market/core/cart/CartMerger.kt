package com.panomc.plugins.market.core.cart

/** A cart message (`quote.messages` entry of 04 section 3): [level] is `error`, `warning` or `info`. */
data class CartMessage(val code: String, val level: String, val lineKey: String? = null)

/** Pure merge rules of the cart (06 section 2.2 PUT, section 2.3 merge on login). */
object CartMerger {
    const val PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE"
    const val MAX_QUANTITY = "MAX_QUANTITY"

    /** Lines with an equal `lineKey` summed (capped at 999), first appearance keeps its position and its content. */
    fun sumByKey(lines: List<CartLine>): List<CartLine> {
        val byKey = LinkedHashMap<String, CartLine>()

        for (line in lines) {
            val key = line.lineKey
            val seen = byKey[key]

            byKey[key] = if (seen == null) line.withQuantity(CartLimits.clampQuantity(line.quantity.toLong()))
            else seen.withQuantity(CartLimits.addQuantities(seen.quantity, CartLimits.clampQuantity(line.quantity.toLong())))
        }

        return byKey.values.toList()
    }

    /** Step 1 of the merge: [sumByKey], then at most [CartLimits.MAX_LINES] lines (the first ones). */
    fun normalizeBrowser(lines: List<CartLine>): List<CartLine> = sumByKey(lines).take(CartLimits.MAX_LINES)

    /** What the login merge does to the server cart. */
    class LoginPlan(
        /** `lineKey` -> new quantity, for lines the server cart already has. */
        val updates: Map<String, Int>,
        /** Lines to insert, in browser order. */
        val inserts: List<CartLine>,
        /** Lines dropped because the cart is full. */
        val overflow: List<CartLine>
    ) {
        val messages: List<CartMessage> get() = overflow.map { CartMessage(MAX_QUANTITY, "warning", it.lineKey) }
    }

    /**
     * Step 3 of the merge. [server] maps the `lineKey` of every line of the server cart to its quantity; [browser] are the
     * normalised, structurally valid browser lines. An equal line takes `max(server, browser)` (not the sum: a repeated
     * merge after a failed client clear must not double the cart); a new line is inserted while the cart has fewer than
     * [maxLines] lines, the rest is overflow.
     */
    fun planLogin(server: Map<String, Int>, browser: List<CartLine>, maxLines: Int = CartLimits.MAX_LINES): LoginPlan {
        val updates = LinkedHashMap<String, Int>()
        val inserts = ArrayList<CartLine>()
        val overflow = ArrayList<CartLine>()
        var size = server.size

        for (line in browser) {
            val key = line.lineKey
            val existing = server[key]

            when {
                existing != null -> if (line.quantity > existing) updates[key] = line.quantity
                size < maxLines -> {
                    inserts += line
                    size++
                }

                else -> overflow += line
            }
        }

        return LoginPlan(updates, inserts, overflow)
    }
}
