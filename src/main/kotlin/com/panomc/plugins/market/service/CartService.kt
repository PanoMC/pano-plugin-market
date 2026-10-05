package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.core.cart.CartMerger
import com.panomc.plugins.market.core.cart.CartMessage
import com.panomc.plugins.market.core.cart.CartValidator
import com.panomc.plugins.market.core.cart.ProductFacts
import com.panomc.plugins.market.core.money.Currencies
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketCartDao
import com.panomc.plugins.market.db.dao.MarketCartItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.model.MarketCart
import com.panomc.plugins.market.db.model.MarketCartItem
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InvalidCart
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

/**
 * The server cart of a logged-in buyer (06 section 2.2 / 2.3, MK-070): get-or-create, line writes, replace, merge on
 * login, clear, and the clear that belongs to the checkout transaction. Writes that touch more than one row run in one
 * `MarketDb.tx` whose first statement is the lock of the cart row, so the 50-line limit, the merge and `PUT /me/cart`
 * never see a half-written cart; a line that is already there is merged by one `INSERT ... ON DUPLICATE KEY UPDATE`.
 *
 * Only the structure of a line is judged on a write (the product exists and is not deleted, the variant belongs to it,
 * the field keys are its fields); stock, `maxQuantityPerOrder` and the per-player limit are reported as advice in
 * [CartView] (`maxQuantity`, `errors`) so the buyer can see and fix them, and judged for real by the quote and by
 * checkout.
 */
class CartService(
    private val db: MarketDb,
    private val clock: Clock,
    private val carts: MarketCartDao,
    private val cartItems: MarketCartItemDao,
    private val products: MarketProductDao,
    private val variants: MarketProductVariantDao,
    private val fields: MarketProductFieldDao
) {
    /** One line of a cart with the advice the buyer needs to fix it. [maxQuantity] is 0 when the line cannot be bought now. */
    class CartLineView(
        val item: MarketCartItem,
        /** The stored values with their JSON types. */
        val fieldValues: Map<String, Any?>,
        val maxQuantity: Int,
        /** Line codes of 04 section 11: `PRODUCT_UNAVAILABLE`, `VARIANT_UNAVAILABLE`, `OUT_OF_STOCK`, `MAX_QUANTITY`. */
        val errors: List<String>
    )

    class CartView(val cart: MarketCart, val lines: List<CartLineView>, val messages: List<CartMessage> = emptyList()) {
        val canCheckout: Boolean get() = lines.isNotEmpty() && lines.all { it.errors.isEmpty() } && messages.none { it.level == "error" }
    }

    /** A change of one line (`PUT /me/cart/items/:itemId`); a `null` wrapper means "not sent". */
    class ItemPatch(
        val quantity: Int? = null,
        val fieldValues: Field<Map<String, Any?>?>? = null,
        val targetServerId: Field<Long?>? = null
    )

    class Field<T>(val value: T)

    /**
     * `PUT /me/cart`: [items] `null` = leave the lines alone; the other parts are applied only when present
     * (`null` inside a [Field] clears).
     */
    class Replacement(
        val items: List<CartLine>? = null,
        val currency: Field<String?>? = null,
        val couponCode: Field<String?>? = null,
        val creatorCode: Field<String?>? = null,
        val recipientUsername: Field<String?>? = null,
        val giftMessage: Field<String?>? = null,
        val shippingAddressId: Field<Long?>? = null,
        val shippingMethodId: Field<Long?>? = null
    )

    // ----- reads -----------------------------------------------------------------------------------------------------

    /** `GET /me/cart`: get-or-create, and store [currency] when one is given. */
    suspend fun get(userId: Long, currency: String? = null): CartView {
        if (currency != null && !Currencies.isSupported(currency)) throw BadRequest()

        return db.tx { conn ->
            val cartId = carts.ensure(userId, clock.now(), conn)

            if (currency != null) carts.updateFields(cartId, mapOf("currency" to currency), clock.now(), conn)

            view(conn, cartId)
        }
    }

    // ----- line writes -----------------------------------------------------------------------------------------------

    /** `POST /me/cart/items`: merged into an identical line; `INVALID_CART` for a structurally bad line or a full cart. */
    suspend fun addItem(userId: Long, line: CartLine): CartView {
        return db.tx { conn ->
            val id = lockedCart(conn, userId)
            val key = line.lineKey

            judge(line, key, loadFacts(conn, setOf(line.productId))[line.productId])

            if (cartItems.getByCartIdAndLineKey(id, key, conn) == null && cartItems.countByCartId(id, conn) >= CartLimits.MAX_LINES) {
                throw InvalidCart(mapOf(key to listOf(CART_FULL)))
            }

            cartItems.upsertAdd(toItem(id, line, key), conn)

            view(conn, id)
        }
    }

    /** `PUT /me/cart/items/:itemId`: `NOT_FOUND` for a line of another cart; a changed key merges into an identical line. */
    suspend fun updateItem(userId: Long, itemId: Long, patch: ItemPatch): CartView {
        return db.tx { conn ->
            val id = lockedCart(conn, userId)
            val item = cartItems.getByIdInCart(itemId, id, conn) ?: throw NotFound()

            val line = CartLine(
                productId = item.productId,
                variantId = item.variantId,
                quantity = patch.quantity?.let { CartLimits.clampQuantity(it.toLong()) } ?: item.quantity,
                fieldValues = if (patch.fieldValues != null) CartLineKey.normalize(patch.fieldValues.value) else storedValues(item),
                targetServerId = if (patch.targetServerId != null) patch.targetServerId.value else item.targetServerId
            )
            val key = line.lineKey

            judge(line, key, loadFacts(conn, setOf(line.productId))[line.productId])

            if (key == item.lineKey) {
                cartItems.setQuantity(item.id, id, line.quantity, clock.now(), conn)
            } else {
                cartItems.deleteByIdInCart(item.id, id, conn)
                cartItems.upsertAdd(toItem(id, line, key), conn)
            }

            view(conn, id)
        }
    }

    /** `DELETE /me/cart/items/:itemId`: idempotent, a missing row is success. */
    suspend fun removeItem(userId: Long, itemId: Long): CartView {
        return db.tx { conn ->
            val cartId = carts.ensure(userId, clock.now(), conn)

            cartItems.deleteByIdInCart(itemId, cartId, conn)

            view(conn, cartId)
        }
    }

    /** `DELETE /me/cart`: every line, and codes, recipient, gift message and shipping selection (the currency stays). */
    suspend fun clear(userId: Long): CartView {
        return db.tx { conn ->
            val id = lockedCart(conn, userId)

            wipe(conn, id)

            view(conn, id)
        }
    }

    /**
     * `PUT /me/cart`: replaces the lines (equal keys summed first, capped at 999) and updates the cart-level fields that
     * are present. Codes are stored as typed (trimmed, upper-cased) without validation: the quote validates them.
     */
    suspend fun replace(userId: Long, request: Replacement): CartView {
        request.currency?.value?.let { if (!Currencies.isSupported(it)) throw BadRequest() }

        val changes = LinkedHashMap<String, Any?>()

        request.currency?.let { changes["currency"] = it.value }
        request.couponCode?.let { changes["couponCode"] = code(it.value) }
        request.creatorCode?.let { changes["creatorCode"] = code(it.value) }
        request.recipientUsername?.let { changes["recipientUsername"] = it.value?.trim()?.takeIf { v -> v.isNotEmpty() } }
        request.giftMessage?.let {
            val text = it.value?.takeIf { v -> v.isNotBlank() }

            if (text != null && text.length > CartLimits.MAX_GIFT_MESSAGE) throw BadRequest()

            changes["giftMessage"] = text
        }
        request.shippingAddressId?.let { changes["shippingAddressId"] = it.value }
        request.shippingMethodId?.let { changes["shippingMethodId"] = it.value }

        return db.tx { conn ->
            val id = lockedCart(conn, userId)

            if (request.items != null) {
                val lines = CartMerger.sumByKey(request.items)

                if (lines.size > CartLimits.MAX_LINES) throw InvalidCart(mapOf("cart" to listOf(CART_FULL)))

                val facts = loadFacts(conn, lines.map { it.productId }.toSet())
                val lineErrors = LinkedHashMap<String, List<String>>()

                for (line in lines) {
                    val errors = CartValidator.lineErrors(line, facts[line.productId])

                    if (errors.isNotEmpty()) lineErrors[line.lineKey] = errors
                }

                if (lineErrors.isNotEmpty()) throw InvalidCart(lineErrors)

                cartItems.deleteByCartId(id, conn)

                lines.forEach { cartItems.upsertAdd(toItem(id, it, it.lineKey), conn) }

                if (changes.isEmpty()) carts.updateFields(id, emptyMap(), clock.now(), conn)
            }

            if (changes.isNotEmpty()) carts.updateFields(id, changes, clock.now(), conn)

            view(conn, id)
        }
    }

    /**
     * `POST /me/cart/merge`: the browser cart after login (06 section 2.3). Equal lines are summed, structurally bad lines
     * are dropped with a `PRODUCT_UNAVAILABLE` warning, an equal server line takes `max(server, browser)` (a repeated merge
     * changes nothing), new lines are added while the cart has fewer than 50 lines (the rest is dropped with a
     * `MAX_QUANTITY` warning). [unreadable] browser entries that could not even be parsed count as dropped lines.
     * Cart-level fields are not touched.
     */
    suspend fun merge(userId: Long, browser: List<CartLine>, unreadable: Int = 0): CartView {
        return db.tx { conn ->
            val id = lockedCart(conn, userId)
            val normalized = CartMerger.normalizeBrowser(browser)
            val facts = loadFacts(conn, normalized.map { it.productId }.toSet())
            val messages = ArrayList<CartMessage>()
            repeat(unreadable) { messages += CartMessage(CartMerger.PRODUCT_UNAVAILABLE, "warning") }
            val valid = ArrayList<CartLine>()

            for (line in normalized) {
                if (CartValidator.isValid(line, facts[line.productId])) valid += line
                else messages += CartMessage(CartMerger.PRODUCT_UNAVAILABLE, "warning", line.lineKey)
            }

            val serverItems = cartItems.getByCartId(id, conn)
            val plan = CartMerger.planLogin(serverItems.associate { it.lineKey to it.quantity }, valid)
            val now = clock.now()

            for (item in serverItems) plan.updates[item.lineKey]?.let { cartItems.setQuantity(item.id, id, it, now, conn) }

            plan.inserts.forEach { cartItems.upsertAdd(toItem(id, it, it.lineKey), conn) }

            if (plan.updates.isNotEmpty() || plan.inserts.isNotEmpty()) carts.updateFields(id, emptyMap(), now, conn)

            view(conn, id, messages + plan.messages)
        }
    }

    /**
     * Called inside the order transaction of a checkout that used the server cart (06 section 2.4): the lines and the
     * cart-level fields go, same statements as [clear]. `false` when the user has no cart. [conn] is the checkout's
     * connection; the cart row lock is taken here, the checkout takes it after its product / stock locks (00 section 8.3).
     */
    suspend fun clearAfterCheckout(conn: SqlClient, userId: Long): Boolean {
        val cart = carts.getByUserId(userId, conn) ?: return false

        carts.getByIdForUpdate(cart.id, conn) ?: return false

        wipe(conn, cart.id)

        return true
    }

    // ----- internals -------------------------------------------------------------------------------------------------

    private suspend fun wipe(conn: SqlClient, cartId: Long) {
        cartItems.deleteByCartId(cartId, conn)
        carts.clearFields(cartId, clock.now(), conn)
    }

    /** Get-or-create, then the row lock: the first statements of every multi-row cart write. */
    private suspend fun lockedCart(conn: SqlClient, userId: Long): Long {
        val id = carts.ensure(userId, clock.now(), conn)

        carts.getByIdForUpdate(id, conn)

        return id
    }

    private fun judge(line: CartLine, key: String, facts: ProductFacts?) {
        val errors = CartValidator.lineErrors(line, facts)

        if (errors.isNotEmpty()) throw InvalidCart(mapOf(key to errors))
    }

    private fun code(value: String?): String? {
        val code = CartLimits.normalizeCode(value)

        if (!CartLimits.codeFits(code)) throw BadRequest()

        return code
    }

    private fun toItem(cartId: Long, line: CartLine, key: String): MarketCartItem {
        val now = clock.now()

        return MarketCartItem(
            cartId = cartId,
            productId = line.productId,
            variantId = line.variantId,
            quantity = line.quantity,
            fieldValues = JsonObject(LinkedHashMap<String, Any?>(line.fieldValues)).encode(),
            targetServerId = line.targetServerId,
            lineKey = key,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun storedValues(item: MarketCartItem): Map<String, Any> =
        CartLineKey.normalize(item.fieldValues?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it).map }.getOrNull() })

    private suspend fun loadFacts(conn: SqlClient, productIds: Set<Long>): Map<Long, ProductFacts> {
        if (productIds.isEmpty()) return emptyMap()

        val ids = productIds.toList()
        val live = products.getByIds(ids, conn).filter { it.deletedAt == null }.associateBy { it.id }
        val keysByProduct = fields.getByProductIds(live.keys.toList(), conn).groupBy({ it.productId }, { it.fieldKey })

        return live.mapValues { (id, _) ->
            ProductFacts(
                available = true,
                variantIds = variants.getByProductId(id, false, conn).map { it.id }.toSet(),
                fieldKeys = keysByProduct[id]?.toSet() ?: emptySet()
            )
        }
    }

    /** The cart as the buyer sees it, read inside the transaction of the write; advice per line from the current catalogue. */
    private suspend fun view(conn: SqlClient, cartId: Long, messages: List<CartMessage> = emptyList()): CartView {
        val cart = carts.getById(cartId, conn)!!
        val items = cartItems.getByCartId(cartId, conn)
        val productsById = products.getByIds(items.map { it.productId }.distinct(), conn).associateBy { it.id }
        val variantsById = variants.getByIds(items.map { it.variantId }.filter { it != 0L }.distinct(), conn).associateBy { it.id }

        return CartView(cart, items.map { advise(it, productsById[it.productId], variantsById[it.variantId]) }, messages)
    }

    private fun advise(item: MarketCartItem, product: MarketProduct?, variant: MarketProductVariant?): CartLineView {
        val values = storedValues(item)

        if (product == null || product.deletedAt != null) return CartLineView(item, values, 0, listOf(CartValidator.PRODUCT_UNAVAILABLE))

        if (item.variantId != 0L && (variant == null || variant.deletedAt != null || variant.productId != product.id)) {
            return CartLineView(item, values, 0, listOf(CartValidator.VARIANT_UNAVAILABLE))
        }

        val stock = if (item.variantId != 0L) variant!!.stock else product.stock
        val max = CartLimits.maxQuantity(stock, product.maxQuantityPerOrder)
        val errors = when {
            stock != null && stock <= 0 -> listOf(OUT_OF_STOCK)
            item.quantity > max -> listOf(MAX_QUANTITY)
            else -> emptyList()
        }

        return CartLineView(item, values, max, errors)
    }

    companion object {
        const val CART_FULL = "CART_FULL"
        const val OUT_OF_STOCK = "OUT_OF_STOCK"
        const val MAX_QUANTITY = "MAX_QUANTITY"
    }
}
