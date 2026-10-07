package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.platform.error.PageNotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.pricing.CatalogContext
import com.panomc.plugins.market.core.pricing.CatalogDiscount
import com.panomc.plugins.market.core.pricing.CatalogPrice
import com.panomc.plugins.market.core.pricing.CatalogPriceRow
import com.panomc.plugins.market.core.pricing.CatalogPricer
import com.panomc.plugins.market.core.pricing.CatalogProduct
import com.panomc.plugins.market.core.pricing.CatalogVariant
import com.panomc.plugins.market.core.pricing.DiscountInput
import com.panomc.plugins.market.core.pricing.LineKind
import com.panomc.plugins.market.core.pricing.OrderCurrencies
import com.panomc.plugins.market.core.pricing.OwnedTier
import com.panomc.plugins.market.core.pricing.PricingConfig
import com.panomc.plugins.market.core.pricing.PricingException
import com.panomc.plugins.market.core.pricing.SaleBadge
import com.panomc.plugins.market.core.pricing.TierInfo
import com.panomc.plugins.market.core.pricing.VariantPrice
import com.panomc.plugins.market.core.money.Currencies
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.util.HtmlSanitizer
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.Paging
import com.panomc.plugins.market.util.ProductDurationType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToLong

/** `pageSize` of the product listing is at most this many (04 section 3). */
const val MAX_STORE_PAGE_SIZE = 60

/** Who is asking: `userId` is `null` for a guest. Drives `owned`, `upgrade` and the `purchasable` advice only. */
class StoreViewer(val userId: Long?) {
    val loggedIn: Boolean get() = userId != null

    companion object {
        val GUEST = StoreViewer(null)
    }
}

/** `ProductDetail.serverChoices[]` of one server a buyer may pick. */
class ServerChoice(val id: Long, val name: String, val type: String)

/** `GET /store/products` `sort` values (04 section 3). */
enum class ProductSort(val wire: String) {
    PRIORITY("priority"), NEWEST("newest"), PRICE_ASC("price-asc"), PRICE_DESC("price-desc"), BESTSELLING("bestselling");

    companion object {
        fun of(wire: String): ProductSort? = entries.firstOrNull { it.wire == wire }
    }
}

/** The filters, order and window of one product listing; `page` / `pageSize` are already validated. */
class ProductListQuery(
    val category: Long? = null,
    val search: String? = null,
    val featured: Boolean? = null,
    val kind: ProductKind? = null,
    val sort: ProductSort = ProductSort.PRIORITY,
    val currency: String? = null,
    val page: Int = 1,
    val pageSize: Int = 24
)

/** `MarketConfig` as the pricing code wants it (money x100, basis points). `rates` = the positive `market_currency_rate` rows. */
fun marketPricingConfig(c: MarketConfig, rates: Map<String, BigDecimal>): PricingConfig =
    PricingConfig(
        baseCurrency = c.currency,
        currencyMode = c.currencyMode,
        additionalCurrencies = c.additionalCurrencies.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct(),
        multiCurrencyFallback = c.multiCurrencyFallback,
        rates = rates,
        vatBp = (c.vatPercent * 100).roundToLong(),
        pricesIncludeVat = c.showVatInPrice,
        removeCents = c.removeCents,
        minimumOrderAmount = MoneyUtil.toMinor(c.minimumOrderAmount),
        combineDiscountsAndCoupons = c.combineDiscountsAndCoupons,
        creditsEnabled = c.creditsEnabled,
        onlyAcceptCredits = c.onlyAcceptCredits,
        creditValue = maxOf(1L, MoneyUtil.toMinor(c.creditValue)),
        allowMixedCreditPayment = c.allowMixedCreditPayment,
        cashbackBp = (c.cashbackPercent * 100).roundToLong()
    )

/**
 * The read side of the storefront (04 section 3 `GET /store`, `/store/products`, `/products/:slug`; MK-064): store settings,
 * the ACTIVE category tree, `ProductCard` pages and the `ProductDetail` of one product. Prices come from [CatalogPricer]
 * (05 section 13), so what a card shows is what a quote charges per unit; this class only loads rows, decides what is visible
 * (ACTIVE and not deleted product, inside its window, in a category whose whole chain is ACTIVE) and shapes the JSON.
 * Cards carry no description; the description of a page is sanitised on every read.
 *
 * A product that cannot be priced (the `HIDE` fallback without a price in the requested currency, a row the pricing code
 * refuses) is left out of listings and answers 404 on its page, never a 500 for the whole store.
 */
class StoreQueryService(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val categories: MarketCategoryDao,
    private val products: MarketProductDao,
    private val variants: MarketProductVariantDao,
    private val prices: MarketProductPriceDao,
    private val fields: MarketProductFieldDao,
    private val bundleItems: MarketBundleItemDao,
    private val discounts: MarketDiscountDao,
    private val currencyRates: MarketCurrencyRateDao,
    private val comparisons: MarketComparisonDao,
    private val orderItems: MarketOrderItemDao,
    private val entitlements: MarketEntitlementDao,
    /** The servers (by id) a buyer may choose; the route points this at the platform's server table. */
    private val serverChoices: suspend (List<Long>, SqlClient) -> List<ServerChoice> = { _, _ -> emptyList() }
) {
    private val logger = LoggerFactory.getLogger("Market:StoreQuery")

    companion object {
        const val BESTSELLER_LIMIT = 10
        const val FEATURED_LIMIT = 24
        const val MAX_PAGE_SIZE = MAX_STORE_PAGE_SIZE

        /** A card shows its stock only at or below this many units. */
        const val LOW_STOCK = 10
    }

    // ---------------------------------------------------------------------------------------------------- endpoints

    /** `GET /api/market/store`. */
    suspend fun store(currency: String?, viewer: StoreViewer, sqlClient: SqlClient): JsonObject {
        val c = config()
        val catalog = load(currency, viewer, sqlClient)
        val cards = catalog.listed.mapNotNull { catalog.card(it) }
        val byId = cards.associateBy { it.product.id }
        val size = c.storePageSize.coerceIn(1, MAX_PAGE_SIZE)
        val listed = sort(cards.toList(), ProductSort.PRIORITY)
        val firstPage = listed.take(size)

        val bestsellers = JsonArray()
        if (c.showBestsellers) {
            orderItems.topProductIds(BESTSELLER_LIMIT, sqlClient).mapNotNull { byId[it] }.forEach { bestsellers.add(catalog.cardJson(it)) }
        }

        val featured = JsonArray()
        if (c.showFeaturedProducts) {
            listed.filter { it.product.featured }.take(FEATURED_LIMIT).forEach { featured.add(catalog.cardJson(it)) }
        }

        val comparisonsJson = JsonArray()
        val comparisonProducts = JsonArray()
        if (c.showComparisons) {
            val seen = HashSet<Long>()

            comparisons.getAllByStatus(MarketStatus.ACTIVE, sqlClient).forEach { comparison ->
                comparisonsJson.add(
                    JsonObject()
                        .put("id", comparison.id)
                        .put("name", comparison.name)
                        .put("productIds", JsonArray(comparison.productIds))
                        .put("features", JsonArray(comparison.features))
                        .put("cellValues", JsonObject(comparison.cellValues))
                )

                // productIds may contain null slots (the comparison table keeps its columns)
                (jsonArray(comparison.productIds)?.mapNotNull { (it as? Number)?.toLong() }).orEmpty().forEach { id ->
                    val card = byId[id]

                    if (card != null && seen.add(id)) comparisonProducts.add(catalog.cardJson(card))
                }
            }
        }

        val counts = cards.mapNotNull { it.product.categoryId }.groupingBy { it }.eachCount()

        return JsonObject()
            .put("settings", settings(c, catalog))
            .put("categories", JsonArray(tree(catalog.categories, counts)))
            .put("products", JsonArray(firstPage.map { catalog.cardJson(it) }))
            .put("productCount", listed.size.toLong())
            .put("totalPage", Paging.totalPages(listed.size.toLong(), size))
            .put("featured", featured)
            .put("bestsellers", bestsellers)
            .put("comparisons", comparisonsJson)
            .put("comparisonProducts", comparisonProducts)
    }

    /** `GET /api/market/store/products`: [PageNotFound] beyond the last page, [NotFound] for a category that is not visible. */
    suspend fun products(query: ProductListQuery, viewer: StoreViewer, sqlClient: SqlClient): JsonObject {
        val catalog = load(query.currency, viewer, sqlClient)
        val subtree = query.category?.let {
            if (it !in catalog.categoryIds) throw NotFound()

            catalog.subtree(it)
        }
        val needle = query.search?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

        val matching = catalog.listed.filter { p ->
            (subtree == null || p.categoryId in subtree) &&
                (query.featured == null || p.featured == query.featured) &&
                (query.kind == null || p.kind == query.kind) &&
                (needle == null || p.name.lowercase(Locale.ROOT).contains(needle) ||
                    p.shortDescription.orEmpty().lowercase(Locale.ROOT).contains(needle))
        }
        val listed = sort(matching.mapNotNull { catalog.card(it) }, query.sort)
        val totalPage = Paging.totalPages(listed.size.toLong(), query.pageSize)

        if (Paging.isBeyondLast(query.page, totalPage)) throw PageNotFound()

        val from = ((query.page - 1).toLong() * query.pageSize).toInt()

        return JsonObject()
            .put("products", JsonArray(listed.drop(from).take(query.pageSize).map { catalog.cardJson(it) }))
            .put("productCount", listed.size.toLong())
            .put("totalPage", totalPage)
    }

    /**
     * `GET /api/market/products/:slug`: [NotFound] for an unknown slug, a product that is not ACTIVE (INACTIVE, HIDDEN,
     * ARCHIVED, soft deleted), outside its window, in a category that is not ACTIVE, or without a price in the currency.
     */
    suspend fun product(slug: String, currency: String?, viewer: StoreViewer, sqlClient: SqlClient): JsonObject {
        val found = products.getBySlug(slug, sqlClient) ?: throw NotFound()
        val catalog = load(currency, viewer, sqlClient)
        val product = catalog.visible.firstOrNull { it.id == found.id } ?: throw NotFound()
        val card = catalog.card(product) ?: throw NotFound()

        return catalog.detailJson(card, sqlClient)
    }

    // ------------------------------------------------------------------------------------------------------- loading

    /** One request's view of the catalogue. */
    private inner class Catalog(
        val cfg: MarketConfig,
        val pricing: PricingConfig,
        val now: Long,
        val requestedCurrency: String?,
        val viewer: StoreViewer,
        val categories: List<MarketCategory>,
        val visible: List<MarketProduct>,
        val variantsByProduct: Map<Long, List<MarketProductVariant>>,
        val pricesByProduct: Map<Long, List<CatalogPriceRow>>,
        val automatic: List<CatalogDiscount>,
        val owned: List<MarketEntitlement>,
        val bundleChildren: Map<Long, List<Pair<ChildStock, Int>>>,
        /** Products with a required custom field (the card then links to the page). */
        val requiredFieldProducts: Set<Long>
    ) {
        val categoryIds: Set<Long> = categories.map { it.id }.toSet()

        /** Credit packs are sold only while the credit system and its top-up are on (07 section 8 and 14.1); the others are always listed. */
        private val packsSellable = cfg.creditsEnabled && cfg.creditTopUpEnabled

        /** What the listings show: [visible] without the credit packs while they cannot be bought. A pack's own page stays reachable (`purchasable.ok = false`). */
        val listed: List<MarketProduct> = visible.filter { it.kind != ProductKind.CREDIT_PACK || packsSellable }
        private val categoryById = categories.associateBy { it.id }
        private val childrenOf = categories.groupBy { it.parentId }
        private val ownedProductIds: Set<Long> = owned.map { it.productId }.toSet()
        private val ownedTiers: List<OwnedTier> = owned.mapNotNull { e ->
            val category = e.tierCategoryId
            val rank = e.tierRank

            if (category == null || rank == null) null else OwnedTier(e.id, category, rank, e.pricePaid)
        }
        private val cache = HashMap<Long, StoreCard?>()
        private val context = CatalogContext(pricing, now, requestedCurrency, automatic, ownedTiers)

        /** The category and everything below it. */
        fun subtree(root: Long): Set<Long> {
            val result = LinkedHashSet<Long>()
            val queue = ArrayDeque(listOf(root))

            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()

                if (result.add(id)) childrenOf[id].orEmpty().forEach { queue.add(it.id) }
            }

            return result
        }

        /** The product's category first, then its ancestors (the scope of a category discount). */
        private fun path(categoryId: Long?): List<Long> {
            val result = ArrayList<Long>()
            var current = categoryId
            while (current != null && current !in result) {
                result += current
                current = categoryById[current]?.parentId
            }
            return result
        }

        fun card(product: MarketProduct): StoreCard? = cache.getOrPut(product.id) { build(product) }

        private fun build(product: MarketProduct): StoreCard? {
            val active = variantsByProduct[product.id].orEmpty()

            // A product with variants that has none left to sell cannot be bought.
            if (product.hasVariants && active.isEmpty()) return null

            val category = product.categoryId?.let { categoryById[it] }
            val tier = if (category != null && category.tiered && product.tierRank != null && product.kind == ProductKind.STANDARD) {
                TierInfo(category.id, product.tierRank, category.upgradeMode)
            } else null

            val catalogProduct = CatalogProduct(
                id = product.id,
                kind = when (product.kind) {
                    ProductKind.STANDARD -> LineKind.PRODUCT
                    ProductKind.BUNDLE -> LineKind.BUNDLE
                    ProductKind.CREDIT_PACK -> LineKind.CREDIT_PACK
                },
                basePrice = product.price,
                compareAtPrice = product.compareAtPrice,
                creditPrice = product.creditPrice,
                categoryPath = path(product.categoryId),
                subscription = product.billingMode == BillingMode.SUBSCRIPTION,
                tier = tier,
                prices = pricesByProduct[product.id].orEmpty(),
                variants = if (product.hasVariants) active.map { CatalogVariant(it.id, it.price, it.compareAtPrice, it.creditPrice) } else emptyList()
            )

            val price = try {
                CatalogPricer.card(catalogProduct, context)
            } catch (e: PricingException) {
                logger.warn("Product {} left out of the storefront: {}", product.id, e.message)

                null
            } ?: return null

            return StoreCard(product, price, active)
        }

        // ---- stock and purchasability

        private fun inStock(product: MarketProduct, active: List<MarketProductVariant>): Boolean {
            val own = if (product.hasVariants) active.any { it.stock == null || it.stock > 0 } else product.stock == null || product.stock > 0

            if (!own) return false

            if (product.kind == ProductKind.BUNDLE) {
                return bundleChildren[product.id].orEmpty().all { (child, quantity) -> child.available(quantity) }
            }

            return true
        }

        private fun purchasable(card: StoreCard): Pair<Boolean, String?> {
            val p = card.product

            if (p.kind == ProductKind.CREDIT_PACK && !packsSellable) return false to "PRODUCT_UNAVAILABLE"

            if (!inStock(p, card.variants)) return false to "OUT_OF_STOCK"

            val needsAccount = p.kind == ProductKind.CREDIT_PACK || p.billingMode == BillingMode.SUBSCRIPTION || !cfg.allowGuestCheckout

            if (!viewer.loggedIn && needsAccount) return false to "LOGIN_REQUIRED"

            if (viewer.loggedIn && p.requiredProducts.isNotEmpty()) {
                val satisfied = if (p.requireOnlyOne) p.requiredProducts.any { it in ownedProductIds } else p.requiredProducts.all { it in ownedProductIds }

                if (!satisfied) return false to "REQUIREMENT_NOT_MET"
            }

            return true to null
        }

        // ---- JSON

        fun cardJson(card: StoreCard): JsonObject {
            val p = card.product
            val price = card.price
            val inStock = inStock(p, card.variants)
            val lowStock = if (p.hasVariants) null else p.stock?.takeIf { it <= LOW_STOCK }

            return JsonObject()
                .put("id", p.id)
                .put("slug", p.slug)
                .put("name", p.name)
                .put("shortDescription", p.shortDescription)
                .put("categoryId", p.categoryId)
                .put("kind", p.kind.name)
                .put("price", dec(price.price))
                .put("compareAtPrice", price.compareAtPrice?.let { dec(it) })
                .put("creditPrice", dec(price.creditPrice))
                .put("currency", price.currency)
                .put("priceFrom", price.priceFrom)
                .put("inStock", inStock)
                .put("stock", lowStock)
                .put("featured", p.featured)
                .put("priority", p.priority)
                .put("icon", p.icon)
                .put("imageFileName", p.imageFileName)
                .put("physical", p.physical)
                .put("billingMode", p.billingMode.name)
                .put("period", period(p))
                .put("hasVariants", p.hasVariants)
                .put("tierRank", p.tierRank)
                .put("sale", sale(price.sale))
                .put("needsOptions", needsOptions(card))
                .put("owned", if (viewer.loggedIn) p.id in ownedProductIds else null)
        }

        private fun needsOptions(card: StoreCard): Boolean {
            val p = card.product

            return p.hasVariants || p.billingMode == BillingMode.SUBSCRIPTION || p.id in requiredFieldProducts || serverChoiceIds(p).size > 1
        }

        suspend fun detailJson(card: StoreCard, sqlClient: SqlClient): JsonObject {
            val p = card.product
            val price = card.price
            val (ok, reason) = purchasable(card)
            val category = p.categoryId?.let { categoryById[it] }
            val fieldRows = fields.getByProductId(p.id, sqlClient)
            val byVariant = price.variants.associateBy { it.variantId }

            val required = if (p.requiredProducts.isEmpty()) emptyList() else products.getByIds(p.requiredProducts, sqlClient).filter { it.deletedAt == null }
            val requiredById = required.associateBy { it.id }
            val children = bundleItems.getByBundleProductId(p.id, sqlClient)
            val childProducts = if (children.isEmpty()) emptyMap() else products.getByIds(children.map { it.productId }.distinct(), sqlClient).associateBy { it.id }
            val childVariants = children.map { it.variantId }.filter { it != 0L }.let { ids -> if (ids.isEmpty()) emptyMap() else variants.getByIds(ids, sqlClient).associateBy { it.id } }
            val servers = serverChoiceIds(p).let { ids -> if (ids.isEmpty()) emptyList() else serverChoices(ids, sqlClient).filter { it.id in ids } }

            val upgrade = price.upgrade?.let { offer ->
                val entitlement = owned.firstOrNull { it.id == offer.fromEntitlementId }
                val from = entitlement?.let { products.getById(it.productId, sqlClient) }

                JsonObject()
                    .put("fromProductId", entitlement?.productId)
                    .put("fromName", from?.name)
                    .put("mode", offer.mode.name)
                    .put("deduction", dec(offer.deduction))
            }

            return cardJson(card)
                // Sanitised on output too: rows saved before write-time sanitising may hold raw HTML.
                .put("description", HtmlSanitizer.sanitizeOrNull(p.description))
                .put("categoryName", category?.name)
                .put("metaTitle", p.metaTitle)
                .put("metaDescription", p.metaDescription)
                .put("variantOptions", jsonArray(p.variantOptions))
                .put(
                    "variants",
                    JsonArray(
                        card.variants.filter { it.id in byVariant }.map { v ->
                            val vp = byVariant.getValue(v.id)

                            JsonObject()
                                .put("id", v.id)
                                .put("name", v.name)
                                .put("optionValues", jsonObject(v.optionValues))
                                .put("price", dec(vp.price))
                                .put("compareAtPrice", vp.compareAtPrice?.let { dec(it) })
                                .put("creditPrice", dec(vp.creditPrice))
                                .put("inStock", v.stock == null || v.stock > 0)
                                .put("stock", v.stock?.takeIf { it <= LOW_STOCK })
                                .put("imageFileName", v.imageFileName)
                                .put("periodCount", v.periodCount)
                        }
                    )
                )
                .put(
                    "fields",
                    JsonArray(
                        fieldRows.map { f ->
                            JsonObject()
                                .put("fieldKey", f.fieldKey)
                                .put("label", f.label)
                                .put("helpText", f.helpText)
                                .put("type", f.type.name)
                                .put("required", f.required)
                                .put("options", jsonArray(f.options))
                                .put("pattern", f.pattern)
                                .put("minLength", f.minLength)
                                .put("maxLength", f.maxLength)
                                .put("minValue", f.minValue)
                                .put("maxValue", f.maxValue)
                                .put("placeholder", f.placeholder)
                                .put("defaultValue", f.defaultValue)
                        }
                    )
                )
                .put(
                    "bundleItems",
                    JsonArray(
                        children.map { b ->
                            val child = childProducts[b.productId]

                            JsonObject()
                                .put("productId", b.productId)
                                .put("variantId", b.variantId)
                                .put("name", child?.name)
                                .put("quantity", b.quantity)
                                .put("imageFileName", childVariants[b.variantId]?.imageFileName ?: child?.imageFileName)
                        }
                    )
                )
                .put(
                    "requiredProducts",
                    JsonArray(
                        p.requiredProducts.mapNotNull { id -> requiredById[id] }.map { r ->
                            JsonObject()
                                .put("id", r.id)
                                .put("name", r.name)
                                .put("slug", r.slug)
                                .put("owned", if (viewer.loggedIn) r.id in ownedProductIds else null)
                        }
                    )
                )
                .put("requireOnlyOne", p.requireOnlyOne)
                .put("limitPerPlayer", p.limitPerPlayer)
                .put("maxQuantityPerOrder", p.maxQuantityPerOrder)
                .put("cooldownSeconds", p.cooldownSeconds)
                .put("allowGift", p.allowGift)
                .put("serverChoices", JsonArray(servers.map { JsonObject().put("id", it.id).put("name", it.name).put("type", it.type) }))
                .put("vatPercent", p.vatPercent?.let { it / 100.0 } ?: cfg.vatPercent)
                .put("pricesIncludeVat", price.pricesIncludeVat)
                .put("weightGrams", p.weightGrams)
                .put("upgrade", upgrade)
                .put("purchasable", JsonObject().put("ok", ok).put("reason", reason))
                // kept from the pre-v2 product page
                .put("durationType", p.durationType.name)
                .put("durationStart", p.durationStart)
                .put("durationExpiry", p.durationExpiry)
                .put("createdAt", p.createdAt)
                .put("updatedAt", p.updatedAt)
        }
    }

    /** A product with its price and live variants. */
    private class StoreCard(val product: MarketProduct, val price: CatalogPrice, val variants: List<MarketProductVariant>)

    private class ChildStock(
        val exists: Boolean,
        val stock: Int?,
        val unlimited: Boolean
    ) {
        fun available(quantity: Int): Boolean = exists && (unlimited || (stock != null && stock >= quantity))
    }

    private suspend fun load(currency: String?, viewer: StoreViewer, sqlClient: SqlClient): Catalog {
        val c = config()
        val now = clock.now()
        val activeCategories = categories.getAll(null, sqlClient).filter { it.status == MarketStatus.ACTIVE }

        // The tree is built from ACTIVE roots, so a subtree under a non-ACTIVE (or missing) ancestor disappears entirely
        // rather than being promoted.
        val byParent = activeCategories.groupBy { it.parentId }
        val reachable = ArrayList<MarketCategory>()
        val queue = ArrayDeque(byParent[null].orEmpty())

        while (queue.isNotEmpty()) {
            val category = queue.removeFirst()

            reachable += category
            byParent[category.id]?.let { queue.addAll(it) }
        }

        val categoryIds = reachable.map { it.id }.toSet()
        val visible = products.getVisibleProducts(sqlClient)
            .filter { it.deletedAt == null && it.status == MarketStatus.ACTIVE }
            .filter { within(it, now) }
            .filter { it.categoryId == null || it.categoryId in categoryIds }

        val variantsByProduct = variants.getAllActive(sqlClient).groupBy { it.productId }
        val pricesByProduct = prices.getAll(sqlClient).groupBy({ it.productId }) { CatalogPriceRow(it.variantId, it.currency, it.price, it.compareAtPrice) }
        val automatic = discounts.getAutomatic(sqlClient).map { a ->
            val d = a.discount

            CatalogDiscount(
                DiscountInput(
                    id = d.id, value = d.value, unit = d.unit, scope = d.scope,
                    productIds = d.productIds.orEmpty().toSet(), categoryIds = d.categoryIds.orEmpty().toSet(),
                    minPaymentAmount = d.minPaymentAmount, startDate = d.startDate, expiryDate = d.expiryDate,
                    usageLimit = d.usageLimit, usedCount = d.usedCount
                ),
                a.showBadge
            )
        }
        val rates = currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate }
        val owned = viewer.userId?.let { entitlements.getActiveByOwner("u:$it", now, sqlClient) }.orEmpty()

        // Bundles are in stock only while every child is.
        val bundleChildren = HashMap<Long, List<Pair<ChildStock, Int>>>()
        val bundles = visible.filter { it.kind == ProductKind.BUNDLE }

        if (bundles.isNotEmpty()) {
            val allVariants = variantsByProduct.values.flatten().associateBy { it.id }

            for (bundle in bundles) {
                val rows = bundleItems.getByBundleProductId(bundle.id, sqlClient)
                val childProducts = products.getByIds(rows.map { it.productId }.distinct(), sqlClient).associateBy { it.id }

                bundleChildren[bundle.id] = rows.map { row ->
                    val child = childProducts[row.productId]
                    val usable = child != null && child.deletedAt == null && child.status == MarketStatus.ACTIVE
                    val variant = if (row.variantId != 0L) allVariants[row.variantId] else null
                    val state = when {
                        !usable -> ChildStock(false, null, false)
                        row.variantId != 0L && variant == null -> ChildStock(false, null, false)
                        variant != null -> ChildStock(true, variant.stock, variant.stock == null)
                        else -> ChildStock(true, child!!.stock, child.stock == null)
                    }

                    state to row.quantity
                }
            }
        }

        val requiredFieldIds = if (visible.isEmpty()) emptySet() else fields.getByProductIds(visible.map { it.id }, sqlClient).filter { it.required }.map { it.productId }.toSet()
        val catalog = Catalog(
            c, marketPricingConfig(c, rates), now, currency?.trim()?.takeIf { it.isNotEmpty() }, viewer, reachable, visible,
            variantsByProduct, pricesByProduct, automatic, owned, bundleChildren, requiredFieldIds
        )

        return catalog
    }

    // --------------------------------------------------------------------------------------------------- helpers

    private fun within(product: MarketProduct, now: Long): Boolean {
        if (product.durationType != ProductDurationType.TEMPORARY) return true
        if (product.durationStart != null && now < product.durationStart) return false
        if (product.durationExpiry != null && now >= product.durationExpiry) return false

        return true
    }

    private fun sort(cards: List<StoreCard>, sort: ProductSort): List<StoreCard> {
        val byName = compareBy<StoreCard> { it.product.name.lowercase(Locale.ROOT) }.thenBy { it.product.id }
        val priority = compareByDescending<StoreCard> { it.product.priority }.then(byName)

        return when (sort) {
            ProductSort.PRIORITY -> cards.sortedWith(priority)
            ProductSort.NEWEST -> cards.sortedWith(compareByDescending<StoreCard> { it.product.createdAt }.thenByDescending { it.product.id })
            ProductSort.PRICE_ASC -> cards.sortedWith(compareBy<StoreCard> { it.price.price }.then(priority))
            ProductSort.PRICE_DESC -> cards.sortedWith(compareByDescending<StoreCard> { it.price.price }.then(priority))
            ProductSort.BESTSELLING -> cards.sortedWith(compareByDescending<StoreCard> { it.product.soldCount }.then(priority))
        }
    }

    private fun settings(c: MarketConfig, catalog: Catalog): JsonObject {
        val base = c.currency
        val offered = OrderCurrencies.resolve(catalog.pricing, catalog.requestedCurrency)
        val others = if (c.currencyMode == com.panomc.plugins.market.config.CurrencyMode.SINGLE) emptyList()
        else catalog.pricing.additionalCurrencies.filter { Currencies.isSupported(it) && it != base && (catalog.pricing.rates[it]?.signum() ?: 0) > 0 }
        val codes = listOf(base) + others

        return JsonObject()
            .put("storeName", c.storeName)
            .put("storeDescription", c.storeDescription)
            .put("currency", base)
            .put("currencySymbol", Currencies.symbol(c.currency))
            .put("creditsEnabled", c.creditsEnabled)
            .put("creditName", c.creditName)
            .put("removeCents", c.removeCents)
            .put("showBestsellers", c.showBestsellers)
            .put("showFeaturedProducts", c.showFeaturedProducts)
            .put("showComparisons", c.showComparisons)
            .put("currencyMode", c.currencyMode.name)
            .put("currencies", JsonArray(codes))
            .put("currencySymbols", JsonObject(codes.associateWith { if (it == base) Currencies.symbol(c.currency) else Currencies.symbol(it) }))
            .put("displayCurrency", offered.displayCurrency)
            .put("pricesIncludeVat", c.showVatInPrice)
            .put("allowGuestCheckout", c.allowGuestCheckout)
            .put("allowGiftPurchase", c.allowGiftPurchase)
            .put("testMode", c.testMode)
            .put("onlyAcceptCredits", c.onlyAcceptCredits)
            .put("creditTopUpEnabled", c.creditsEnabled && c.creditTopUpEnabled)
            .put(
                "modules",
                JsonObject()
                    .put("recentBuyers", c.moduleRecentBuyers)
                    .put("topSupporters", c.moduleTopSupporters)
                    .put("goal", c.moduleGoal)
                    .put("saleBadges", c.moduleSaleBadges)
                    .put("saleCountdown", c.moduleSaleCountdown)
                    .put("stats", c.moduleStats)
            )
            .put("pageSize", c.storePageSize)
    }

    /** Roots are ACTIVE categories with no parent; recursion descends only into the reachable children. */
    private fun tree(categories: List<MarketCategory>, productsCountByCategory: Map<Long, Int>): List<JsonObject> {
        val childrenByParent = categories.groupBy { it.parentId }

        fun toNode(category: MarketCategory): JsonObject {
            val children = childrenByParent[category.id].orEmpty().sortedBy { it.position }.map { toNode(it) }

            return JsonObject()
                .put("id", category.id)
                .put("name", category.name)
                .put("description", category.description)
                .put("icon", category.icon)
                .put("color", category.color)
                .put("parentId", category.parentId)
                .put("position", category.position)
                .put("imageFileName", category.imageFileName)
                .put("tiered", category.tiered)
                .put("productsCount", (productsCountByCategory[category.id] ?: 0).toLong())
                .put("children", children)
        }

        return categories.filter { it.parentId == null }.sortedBy { it.position }.map { toNode(it) }
    }

    private fun sale(sale: SaleBadge?): JsonObject? =
        sale?.let { JsonObject().put("percent", it.percent?.toDouble()).put("amountOff", it.amountOff?.let { v -> dec(v) }).put("endsAt", it.endsAt) }

    private fun period(p: MarketProduct): JsonObject? {
        val unit = p.periodUnit
        val count = p.periodCount

        return if (p.billingMode != BillingMode.ONE_TIME && unit != null && count != null) JsonObject().put("unit", unit.name).put("count", count) else null
    }

    private fun serverChoiceIds(p: MarketProduct): List<Long> =
        jsonArray(p.serverChoices)?.mapNotNull { (it as? Number)?.toLong() }.orEmpty()

    private fun jsonArray(raw: String?): JsonArray? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JsonArray(it) }.getOrNull() }

    private fun jsonObject(raw: String?): JsonObject? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() }

    private fun dec(value: Long): Double = MoneyUtil.toDecimal(value)
}
