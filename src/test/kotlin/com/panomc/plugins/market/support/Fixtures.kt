package com.panomc.plugins.market.support

import com.google.gson.JsonObject
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.plugins.market.db.model.*
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.GiftType
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple

/** A fixture user: the id the in-memory directory gave, the name, and the id of the credit account row. */
data class TestUser(val id: Long, val username: String, val accountId: Long)

/**
 * Builders of 17 section 5.4: functions with defaults that insert rows through the DAOs of a [TestWiring] and return
 * the entity. Amounts are `Long` times 100 (credits and money alike). Columns a DAO does not write yet (the scheme
 * version 3 and later columns of product, gift and payment method; the DAOs of the catalogue and promotion slices
 * will take them over) are set with [setColumns] right after the insert, so a builder never depends on which slice
 * extended which DAO. Service level request builders (`cartInput`) arrive with the checkout slice.
 *
 * [insertRaw] and [setColumns] are also usable without a wiring (the invariant self-test seeds violations with them).
 */
class Fixtures(private val w: TestWiring) {
    private val pool get() = w.pool

    private var sequence = 0
    private fun next() = ++sequence

    suspend fun category(name: String = "Category ${next()}", parentId: Long? = null, tiered: Boolean = false, upgradeMode: String = "DIFFERENCE"): MarketCategory {
        val id = w.categories.add(MarketCategory(name = name, parentId = parentId), pool)
        if (tiered || upgradeMode != "DIFFERENCE") setColumns(pool, "market_category", id, mapOf("tiered" to tiered, "upgradeMode" to upgradeMode))
        return w.categories.getById(id, pool)!!
    }

    suspend fun product(
        slug: String = "product-${next()}",
        name: String = slug,
        price: Long = 1000,
        stock: Int? = null,
        creditPrice: Long = 0,
        categoryId: Long? = null,
        actions: String? = null,
        status: com.panomc.plugins.market.util.MarketStatus = com.panomc.plugins.market.util.MarketStatus.ACTIVE,
        columns: Map<String, Any?> = emptyMap()
    ): MarketProduct {
        val id = w.products.add(
            MarketProduct(
                slug = slug, name = name, price = price, stock = stock, creditPrice = creditPrice, categoryId = categoryId,
                actions = actions, status = status, createdAt = w.clock.now(), updatedAt = w.clock.now()
            ),
            pool
        )
        if (columns.isNotEmpty()) setColumns(pool, "market_product", id, columns)
        return w.products.getById(id, pool)!!
    }

    suspend fun variant(product: MarketProduct, name: String = "Variant ${next()}", price: Long? = null, stock: Int? = null, position: Int = 0, optionValues: String? = null): MarketProductVariant {
        val id = w.variants.add(MarketProductVariant(productId = product.id, name = name, price = price, stock = stock, position = position, optionValues = optionValues), pool)
        setColumns(pool, "market_product", product.id, mapOf("hasVariants" to true))
        return w.variants.getById(id, pool)!!
    }

    suspend fun field(
        product: MarketProduct,
        key: String = "field${next()}",
        type: ProductFieldType = ProductFieldType.TEXT,
        required: Boolean = false
    ): MarketProductField {
        val id = w.fields.add(MarketProductField(productId = product.id, fieldKey = key, label = key, type = type, required = required), pool)!!
        return w.fields.getById(id, pool)!!
    }

    /** A `BUNDLE` product at [price] whose children are `product to quantity`. */
    suspend fun bundle(vararg children: Pair<MarketProduct, Int>, slug: String = "bundle-${next()}", price: Long = 1200): MarketProduct {
        val bundle = product(slug = slug, price = price, columns = mapOf("kind" to "BUNDLE"))
        children.forEachIndexed { index, (child, quantity) ->
            w.bundleItems.add(MarketBundleItem(bundleProductId = bundle.id, productId = child.id, quantity = quantity, position = index), pool)
        }
        return bundle
    }

    suspend fun discount(name: String = "Discount ${next()}", value: Long = 1000, unit: DiscountUnit = DiscountUnit.PERCENT, scope: DiscountScope = DiscountScope.ALL, usageLimit: Int? = null): MarketDiscount {
        val id = w.discounts.add(MarketDiscount(name = name, value = value, unit = unit, scope = scope, usageLimit = usageLimit), pool)
        return w.discounts.getById(id, pool)!!
    }

    suspend fun coupon(
        code: String = "CODE${next()}",
        unit: DiscountUnit = DiscountUnit.PERCENT,
        discount: Long = 1000,
        redeemLimit: Int? = null,
        customerRedeemLimit: Int? = null,
        minPaymentAmount: Long? = null
    ): MarketCoupon {
        val id = w.coupons.add(
            MarketCoupon(name = code, code = code, scope = CouponScope.ALL, discount = discount, unit = unit, redeemLimit = redeemLimit, customerRedeemLimit = customerRedeemLimit, minPaymentAmount = minPaymentAmount),
            pool
        )
        return w.coupons.getById(id, pool)!!
    }

    suspend fun creatorCode(code: String = "CREATOR${next()}", creator: String = "streamer", discount: Long = 500, commissionPercent: Long = 1000): MarketCreatorCode {
        val id = w.creatorCodes.add(MarketCreatorCode(creator = creator, code = code, discount = discount, unit = DiscountUnit.PERCENT, commissionPercent = commissionPercent), pool)
        return w.creatorCodes.getById(id, pool)!!
    }

    /** A gift code; `redeemLimit` / `customerRedeemLimit` are the scheme version 4 columns (`null` = unlimited). */
    suspend fun gift(code: String = "GIFT${next()}", type: GiftType = GiftType.PRODUCT, productId: Long? = null, creditAmount: Long? = null, redeemLimit: Int? = 1): MarketGift {
        val id = w.gifts.add(MarketGift(code = code, type = type, productId = productId, creditAmount = creditAmount), pool)
        setColumns(pool, "market_gift", id, mapOf("redeemLimit" to redeemLimit))
        return w.gifts.getById(id, pool)!!
    }

    /** A user of the in-memory directory with a credit account at balance 0. */
    suspend fun user(name: String = "user${next()}"): TestUser {
        val id = w.users.create(name)
        w.creditAccounts.insertUserAccountIgnore(id, pool)
        val account = w.creditAccounts.getByUserId(id, pool)!!
        return TestUser(id, name, account.id)
    }

    /**
     * A `GRANT` ledger transaction of [amount] credits (x100): `ISSUANCE` -> the user's account, both legs written and
     * both balances moved, so I1 and I2 hold. The ledger service of the credits slice replaces this plumbing; the rows
     * it leaves are the same. Returns the transaction id.
     */
    suspend fun credit(user: TestUser, amount: Long): Long {
        require(amount > 0) { "amount must be positive" }
        val now = w.clock.now()
        val issuance = w.creditAccounts.getBySystemKey(CreditSystemKey.ISSUANCE, pool)!!
        val txId = w.creditTxs.add(
            MarketCreditTx(type = CreditTxType.GRANT, idempotencyKey = "fixture:grant:${next()}", userId = user.id, amount = amount, createdAt = now, updatedAt = now),
            pool
        )!!
        w.creditAccounts.addToBalance(issuance.id, -amount, false, pool)
        w.creditEntries.add(MarketCreditEntry(txId = txId, accountId = issuance.id, amount = -amount, balanceAfter = w.creditAccounts.getById(issuance.id, pool)!!.balance, createdAt = now, updatedAt = now), pool)
        w.creditAccounts.addToBalance(user.accountId, amount, false, pool)
        w.creditEntries.add(MarketCreditEntry(txId = txId, accountId = user.accountId, amount = amount, balanceAfter = w.creditAccounts.getById(user.accountId, pool)!!.balance, createdAt = now, updatedAt = now), pool)
        return txId
    }

    suspend fun creditBalance(user: TestUser): Long = w.creditAccounts.getById(user.accountId, pool)!!.balance

    suspend fun paymentMethod(
        providerId: String = "fake",
        enabled: Boolean = true,
        feeMode: PaymentFeeMode = PaymentFeeMode.NONE,
        feePercent: Long = 0,
        feeFixed: Long = 0,
        minAmount: Long? = null,
        maxAmount: Long? = null,
        settings: JsonObject = JsonObject()
    ): MarketPaymentMethod {
        w.paymentMethods.upsertByMethodId(providerId, enabled, settings.toString(), pool)
        setColumns(
            pool, "market_payment_method", w.paymentMethods.getByMethodId(providerId, pool)!!.id,
            mapOf("feeMode" to feeMode.name, "feePercent" to feePercent, "feeFixed" to feeFixed, "minAmount" to minAmount, "maxAmount" to maxAmount)
        )
        return w.paymentMethods.getByMethodId(providerId, pool)!!
    }

    suspend fun shippingZone(name: String = "Everywhere", countries: String = "[\"*\"]", position: Int = 0): MarketShippingZone {
        val id = w.shippingZones.add(MarketShippingZone(name = name, countries = countries, position = position, createdAt = w.clock.now(), updatedAt = w.clock.now()), pool)
        return w.shippingZones.getById(id, pool)!!
    }

    suspend fun shippingMethod(name: String = "Standard", providerId: String = "manual", handlingFee: Long = 0, freeShippingThreshold: Long? = null): MarketShippingMethod {
        val id = w.shippingMethods.add(
            MarketShippingMethod(name = name, providerId = providerId, handlingFee = handlingFee, freeShippingThreshold = freeShippingThreshold, createdAt = w.clock.now(), updatedAt = w.clock.now()),
            pool
        )
        return w.shippingMethods.getById(id, pool)!!
    }

    /**
     * An enabled endpoint of core's webhook system. [secret] and [headers] are the **stored** (already encrypted) texts; [events] is the JSON list in the market's own
     * spelling (`["order.paid"]`), stored with the source core adds (`["market.order.paid"]`); `["*"]` and a name that already has a source stay as they are.
     */
    suspend fun webhookEndpoint(
        url: String = "https://hooks.invalid/market", signing: WebhookSigning = WebhookSigning.NONE, secret: String? = null, events: String = "[\"*\"]",
        maxAttempts: Int = 8, format: WebhookFormat = WebhookFormat.JSON, headers: String? = null, enabled: Boolean = true, name: String = "Endpoint ${next()}"
    ): WebhookEndpoint {
        val id = w.webhookEndpoints.add(
            WebhookEndpoint(
                name = name, url = url, events = WebhookTestSupport.subscribe(events), format = format, signing = signing, secret = secret, headers = headers, enabled = enabled, maxAttempts = maxAttempts,
                createdAt = w.clock.now(), updatedAt = w.clock.now()
            ),
            pool
        )
        return w.webhookEndpoints.getById(id, pool)!!
    }

    companion object {
        /**
         * Inserts one row into `pano_<table>` (table without the prefix) with the given [values]; every other NOT NULL
         * column without a default is filled with the zero value of its type (`''`, `0`), `createdAt` / `updatedAt` with 0
         * unless given. For tests that need a row the DAOs cannot express, such as a deliberately inconsistent one.
         * Returns the generated id (or 0 when the table has none).
         */
        suspend fun insertRaw(pool: Pool, table: String, values: Map<String, Any?> = emptyMap()): Long {
            val physical = MarketTestDb.TABLE_PREFIX + table
            val columns = MarketTestDb.sql(
                pool,
                "SELECT COLUMN_NAME AS n, DATA_TYPE AS t, IS_NULLABLE AS nullable, COLUMN_DEFAULT AS d, EXTRA AS extra FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                physical
            )
            require(columns.isNotEmpty()) { "table $physical does not exist" }
            val known = columns.map { it.getString("n") }.toSet()
            val unknown = values.keys - known
            require(unknown.isEmpty()) { "unknown column(s) of $physical: $unknown" }
            val all = LinkedHashMap<String, Any?>(values)
            for (c in columns) {
                val name = c.getString("n")
                if (name in all) continue
                val required = c.getString("nullable") == "NO" && c.getString("d") == null && !c.getString("extra").contains("auto_increment")
                if (!required) continue
                all[name] = when (c.getString("t").lowercase()) {
                    "varchar", "char", "text", "mediumtext", "longtext", "tinytext", "enum" -> ""
                    else -> 0
                }
            }
            val names = all.keys.joinToString(", ") { "`$it`" }
            val marks = all.keys.joinToString(", ") { "?" }
            val result = pool.preparedQuery("INSERT INTO `$physical` ($names) VALUES ($marks)").execute(Tuple.from(all.values.toList())).coAwait()
            return result.property(MySQLClient.LAST_INSERTED_ID) ?: 0L
        }

        /** `UPDATE pano_<table> SET ... WHERE id = ?` for the given [values]. */
        suspend fun setColumns(pool: Pool, table: String, id: Long, values: Map<String, Any?>) {
            if (values.isEmpty()) return
            val set = values.keys.joinToString(", ") { "`$it` = ?" }
            pool.preparedQuery("UPDATE `${MarketTestDb.TABLE_PREFIX}$table` SET $set WHERE `id` = ?")
                .execute(Tuple.from(values.values.toList() + id)).coAwait()
        }
    }
}
