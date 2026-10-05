package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.catalog.BundleItemDraft
import com.panomc.plugins.market.core.catalog.FieldDraft
import com.panomc.plugins.market.core.catalog.ImageChange
import com.panomc.plugins.market.core.catalog.PriceDraft
import com.panomc.plugins.market.core.catalog.ProductInput
import com.panomc.plugins.market.core.catalog.ProductRules
import com.panomc.plugins.market.core.catalog.SlugRules
import com.panomc.plugins.market.core.catalog.StockMode
import com.panomc.plugins.market.core.catalog.VariantDraft
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductProviderMetaDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketBundleItem
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductField
import com.panomc.plugins.market.db.model.MarketProductPrice
import com.panomc.plugins.market.db.model.MarketProductProviderMeta
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InvalidProduct
import com.panomc.plugins.market.error.ReservedSlug
import com.panomc.plugins.market.error.SlugAlreadyExists
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

/**
 * The catalogue write model of the panel (04 section 5, MK-050): product save with its set parts (variants, fields,
 * bundle rows, per-currency prices, provider meta), soft / hard delete (01 section 13) and the atomic stock endpoint.
 * One database transaction per call; files are never touched here: a save returns the file names that became orphans
 * and the route deletes them after the commit (an unreferenced file is harmless, a deleted file of a rolled-back save
 * would not be).
 *
 * Rules of a save: the request is a [ProductInput]; the scalars present are laid over the stored row (partial update),
 * `stock` is honoured on create only, set parts replace the stored set when present and leave it when absent, rows are
 * matched by `id` (prices by `(variantId, currency)`, bundle rows by `(productId, variantId)`), stored rows missing from
 * a list are deleted (a variant that anything references is soft deleted instead).
 */
class CatalogService(
    private val db: MarketDb,
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val products: MarketProductDao,
    private val variants: MarketProductVariantDao,
    private val prices: MarketProductPriceDao,
    private val fields: MarketProductFieldDao,
    private val bundleItems: MarketBundleItemDao,
    private val providerMeta: MarketProductProviderMetaDao
) {
    class SaveResult(
        val id: Long,
        val slug: String,
        val name: String,
        /** Codes such as `NO_SHIPPING_METHOD`. */
        val warnings: List<String>,
        /** Image file names no row points at any more; delete them after the commit. */
        val orphanedFiles: List<String>,
        /** Changed scalar columns of an update for the activity log (money as decimals); empty on create. */
        val changes: Map<String, Any?> = emptyMap()
    )

    class DeleteResult(val soft: Boolean, val orphanedFiles: List<String>)

    class StockResult(val stock: Int?)

    /** A product with every set part (panel `GET /products/:id`). Soft-deleted variants are left out. */
    class ProductView(
        val product: MarketProduct,
        val variants: List<MarketProductVariant>,
        val fields: List<MarketProductField>,
        val bundleItems: List<MarketBundleItem>,
        val prices: List<MarketProductPrice>,
        /** providerId -> meta JSON text (product level). */
        val providerMeta: Map<String, String>
    )

    // ----- create ------------------------------------------------------------------------------------------------

    suspend fun create(input: ProductInput): SaveResult = db.tx { conn -> save(conn, null, input) }

    // ----- update ------------------------------------------------------------------------------------------------

    suspend fun update(id: Long, input: ProductInput): SaveResult = db.tx { conn -> save(conn, id, input) }

    private suspend fun save(conn: SqlClient, id: Long?, input: ProductInput): SaveResult {
        val now = clock.now()
        val create = id == null
        // The row lock is the first statement: saves, deletes and stock changes of one product serialise, so a partial
        // update is always laid over the latest committed row and never over a stale read.
        val base = if (id == null) null else products.getByIdForUpdate(id, conn)?.takeIf { it.deletedAt == null } ?: throw NotFound()

        val baseCurrency = config().currency.name
        val errors = linkedMapOf<String, String>()

        // slug: a request that names one (or asks to regenerate it) wins; otherwise a stored slug stays.
        val finalName = if (input.has("name")) input.scalars["name"] as? String else base?.name
        val slug = if (base == null || input.has("slug")) SlugRules.resolve(input.scalars["slug"] as? String, finalName) else base.slug

        if (slug.isNotBlank() && SlugRules.isReserved(slug)) throw ReservedSlug()

        val categoryId = if (input.has("categoryId")) input.scalars["categoryId"] as? Long else base?.categoryId
        var categoryTiered = false

        if (categoryId != null) {
            val tiered = products.isCategoryTiered(categoryId, conn)

            if (tiered == null) errors["categoryId"] = "NOT_FOUND" else categoryTiered = tiered
        }

        val existingVariants = if (base == null) emptyList() else variants.getByProductId(base.id, false, conn)
        val existingBundle = if (base == null) emptyList() else bundleItems.getByBundleProductId(base.id, conn)

        val product = input.applyTo(base, slug, now, categoryTiered)

        val liveVariantCount = input.variants?.size ?: existingVariants.size
        val bundleCount = input.bundleItems?.size ?: existingBundle.size

        errors.putAll(
            ProductRules.validate(
                product,
                input,
                ProductRules.Context(baseCurrency, create, categoryTiered, liveVariantCount, bundleCount)
            )
        )

        // references that need the database
        checkRequiredProducts(conn, product, errors)
        if (input.variants != null) checkVariantIds(input.variants, existingVariants, errors)
        if (input.prices != null) checkPriceVariants(input.prices, existingVariants, errors)
        if (input.fields != null && base != null) checkFieldIds(conn, base.id, input.fields, errors)
        if (input.bundleItems != null) checkBundleChildren(conn, base?.id, input.bundleItems, errors)

        if (errors.isNotEmpty()) throw InvalidProduct(errors)

        val owner = products.getBySlug(slug, conn)
        if (owner != null && owner.id != base?.id) throw SlugAlreadyExists()

        val orphans = mutableListOf<String>()

        val productImage = when (val change = input.image) {
            is ImageChange.Keep -> base?.imageFileName
            is ImageChange.Remove -> null
            is ImageChange.Set -> change.fileName
        }
        if (base?.imageFileName != null && productImage != base.imageFileName) orphans.add(base.imageFileName)

        val toStore = withImage(product, productImage)

        val productId: Long = if (base == null) {
            try {
                products.add(toStore, conn)
            } catch (e: Exception) {
                if (e.isDuplicateKey()) throw SlugAlreadyExists()
                throw e
            }
        } else {
            try {
                if (!products.update(toStore, conn)) throw NotFound()
            } catch (e: NotFound) {
                throw e
            } catch (e: Exception) {
                if (e.isDuplicateKey()) throw SlugAlreadyExists()
                throw e
            }
            base.id
        }

        val variantIds = if (input.variants != null) applyVariants(conn, productId, input.variants, existingVariants, now, orphans) else null

        if (input.prices != null || input.variants?.any { it.prices != null } == true) {
            applyPrices(conn, productId, input, variantIds ?: existingVariants.map { it.id }, now)
        }

        input.fields?.let { applyFields(conn, productId, it, now) }
        input.bundleItems?.let { applyBundle(conn, productId, it, existingBundle) }
        input.providerMeta?.let { applyProviderMeta(conn, productId, it, now) }

        val warnings = mutableListOf<String>()
        if (product.physical && !products.hasSellableShippingMethod(conn)) warnings.add("NO_SHIPPING_METHOD")

        return SaveResult(productId, slug, product.name, warnings, orphans, if (base == null) emptyMap() else changes(base, toStore))
    }

    private fun changes(before: MarketProduct, after: MarketProduct): Map<String, Any?> {
        val changes = linkedMapOf<String, Any?>()

        if (before.name != after.name) changes["name"] = after.name
        if (before.slug != after.slug) changes["slug"] = after.slug
        if (before.price != after.price) changes["price"] = after.price / 100.0
        if (before.creditPrice != after.creditPrice) changes["creditPrice"] = after.creditPrice / 100.0
        if (before.categoryId != after.categoryId) changes["categoryId"] = after.categoryId
        if (before.status != after.status) changes["status"] = after.status.name
        if (before.featured != after.featured) changes["featured"] = after.featured
        if (before.priority != after.priority) changes["priority"] = after.priority
        if (before.kind != after.kind) changes["kind"] = after.kind.name
        if (before.billingMode != after.billingMode) changes["billingMode"] = after.billingMode.name
        if (before.physical != after.physical) changes["physical"] = after.physical
        if (before.imageFileName != after.imageFileName) changes["image"] = after.imageFileName != null

        return changes
    }

    private fun withImage(p: MarketProduct, imageFileName: String?): MarketProduct = MarketProduct(
        id = p.id, slug = p.slug, name = p.name, description = p.description, categoryId = p.categoryId, price = p.price,
        creditPrice = p.creditPrice, stock = p.stock, requiredProducts = p.requiredProducts, requireOnlyOne = p.requireOnlyOne,
        requiredPermission = p.requiredPermission, status = p.status, featured = p.featured, durationType = p.durationType,
        durationStart = p.durationStart, durationExpiry = p.durationExpiry, priority = p.priority, icon = p.icon,
        imageFileName = imageFileName, actions = p.actions, kind = p.kind, shortDescription = p.shortDescription,
        compareAtPrice = p.compareAtPrice, vatPercent = p.vatPercent, physical = p.physical, sku = p.sku,
        weightGrams = p.weightGrams, lengthMm = p.lengthMm, widthMm = p.widthMm, heightMm = p.heightMm, hsCode = p.hsCode,
        originCountry = p.originCountry, billingMode = p.billingMode, periodUnit = p.periodUnit, periodCount = p.periodCount,
        subscriptionMaxCycles = p.subscriptionMaxCycles, limitPerPlayer = p.limitPerPlayer,
        maxQuantityPerOrder = p.maxQuantityPerOrder, cooldownSeconds = p.cooldownSeconds, tierRank = p.tierRank,
        creditAmount = p.creditAmount, allowGift = p.allowGift, serverChoices = p.serverChoices, hasVariants = p.hasVariants,
        variantOptions = p.variantOptions, metaTitle = p.metaTitle, metaDescription = p.metaDescription,
        soldCount = p.soldCount, deletedAt = p.deletedAt, createdAt = p.createdAt, updatedAt = p.updatedAt
    )

    // ----- reference checks (need the database) -----------------------------------------------------------------

    private suspend fun checkRequiredProducts(conn: SqlClient, product: MarketProduct, errors: MutableMap<String, String>) {
        if (product.requiredProducts.isEmpty()) return

        if (product.id > 0 && product.id in product.requiredProducts) {
            errors["requiredProducts"] = "SELF"
            return
        }

        val found = products.getByIds(product.requiredProducts, conn).filter { it.deletedAt == null }.map { it.id }.toSet()

        if (!found.containsAll(product.requiredProducts)) errors["requiredProducts"] = "NOT_FOUND"
    }

    private fun checkVariantIds(list: List<VariantDraft>, existing: List<MarketProductVariant>, errors: MutableMap<String, String>) {
        val known = existing.map { it.id }.toSet()

        list.forEachIndexed { index, draft -> if (draft.id != null && draft.id !in known) errors["variants.$index.id"] = "NOT_FOUND" }
    }

    private fun checkPriceVariants(list: List<PriceDraft>, existing: List<MarketProductVariant>, errors: MutableMap<String, String>) {
        val known = existing.map { it.id }.toSet()

        list.forEachIndexed { index, row ->
            val variantId = row.variantId ?: 0L

            if (variantId != 0L && variantId !in known) errors["prices.$index.variantId"] = "NOT_FOUND"
        }
    }

    private suspend fun checkFieldIds(conn: SqlClient, productId: Long, list: List<FieldDraft>, errors: MutableMap<String, String>) {
        val known = fields.getByProductId(productId, conn).map { it.id }.toSet()

        list.forEachIndexed { index, draft -> if (draft.id != null && draft.id !in known) errors["fields.$index.id"] = "NOT_FOUND" }
    }

    private suspend fun checkBundleChildren(conn: SqlClient, selfId: Long?, list: List<BundleItemDraft>, errors: MutableMap<String, String>) {
        val childIds = list.map { it.productId }.filter { it > 0 }.distinct()
        val live = products.getByIds(childIds, conn).filter { it.deletedAt == null }.associateBy { it.id }

        list.forEachIndexed { index, item ->
            val path = "bundleItems.$index"

            if (item.productId < 1) return@forEachIndexed

            if (selfId != null && item.productId == selfId) {
                errors["$path.productId"] = "SELF"
            } else if (item.productId !in live) {
                errors["$path.productId"] = "NOT_FOUND"
            } else if (item.variantId > 0) {
                val variant = variants.getById(item.variantId, conn)

                if (variant == null || variant.productId != item.productId || variant.deletedAt != null) errors["$path.variantId"] = "NOT_FOUND"
            }
        }
    }

    // ----- set parts -------------------------------------------------------------------------------------------

    /** Applies `variants[]`; returns the ids of the live variants after the save, in list order. */
    private suspend fun applyVariants(
        conn: SqlClient,
        productId: Long,
        list: List<VariantDraft>,
        existing: List<MarketProductVariant>,
        now: Long,
        orphans: MutableList<String>
    ): List<Long> {
        val byId = existing.associateBy { it.id }
        val keep = list.mapNotNull { it.id }.toSet()
        val resulting = mutableListOf<Long>()

        list.forEachIndexed { index, draft ->
            val stored = draft.id?.let { byId.getValue(it) }
            val image = when (val change = draft.image) {
                is ImageChange.Keep -> stored?.imageFileName
                is ImageChange.Remove -> null
                is ImageChange.Set -> change.fileName
            }

            if (stored?.imageFileName != null && image != stored.imageFileName) orphans.add(stored.imageFileName)

            val row = MarketProductVariant(
                id = stored?.id ?: -1,
                productId = productId,
                name = draft.name,
                sku = draft.sku,
                optionValues = draft.optionValues?.let { JsonObject(it as Map<String, Any?>).encode() },
                attributes = draft.attributes?.let { JsonObject(it as Map<String, Any?>).encode() },
                price = draft.price,
                creditPrice = draft.creditPrice,
                compareAtPrice = draft.compareAtPrice,
                // honoured for a new variant only; the stock endpoint changes an existing one
                stock = if (stored == null) draft.stock else stored.stock,
                weightGrams = draft.weightGrams,
                periodCount = draft.periodCount,
                imageFileName = image,
                position = draft.position ?: index,
                status = draft.status,
                createdAt = stored?.createdAt ?: now,
                updatedAt = now
            )

            if (stored == null) resulting.add(variants.add(row, conn))
            else {
                variants.update(row, conn)
                resulting.add(stored.id)
            }
        }

        existing.filter { it.id !in keep }.forEach { gone ->
            if (products.isVariantReferenced(gone.id, conn)) {
                variants.markDeleted(gone.id, now, conn)
                products.removeVariantFromCarts(gone.id, conn)
            } else {
                prices.deleteByVariantId(gone.id, conn)
                variants.deleteById(gone.id, conn)
                gone.imageFileName?.let { orphans.add(it) }
            }
        }

        return resulting
    }

    /**
     * Top-level `prices` replaces every stored price row of the product (product level and variants); a variant's own
     * `prices` replaces that variant's rows. [liveVariantIds] are the variants after the save in request order for
     * the variants that were sent, so nested rows of a new variant find their id.
     */
    private suspend fun applyPrices(conn: SqlClient, productId: Long, input: ProductInput, liveVariantIds: List<Long>, now: Long) {
        val desired = linkedMapOf<Pair<Long, String>, PriceDraft>()
        val scope = mutableSetOf<Long>()

        if (input.prices != null) {
            scope.add(0L)
            scope.addAll(liveVariantIds)
            input.prices.forEach { desired[(it.variantId ?: 0L) to it.currency] = it }
        }

        input.variants?.forEachIndexed { index, draft ->
            val nested = draft.prices ?: return@forEachIndexed
            val variantId = liveVariantIds[index]

            scope.add(variantId)
            nested.forEach { desired[variantId to it.currency] = it }
        }

        val stored = prices.getByProductId(productId, conn)

        stored.filter { it.variantId in scope && (it.variantId to it.currency) !in desired }.forEach { prices.deleteById(it.id, conn) }

        desired.forEach { (key, draft) ->
            prices.upsert(
                MarketProductPrice(
                    productId = productId,
                    variantId = key.first,
                    currency = key.second,
                    price = draft.price,
                    compareAtPrice = draft.compareAtPrice,
                    createdAt = now,
                    updatedAt = now
                ),
                conn
            )
        }
    }

    private suspend fun applyFields(conn: SqlClient, productId: Long, list: List<FieldDraft>, now: Long) {
        val stored = fields.getByProductId(productId, conn)
        val keep = list.mapNotNull { it.id }.toSet()

        stored.filter { it.id !in keep }.forEach { fields.deleteById(it.id, conn) }

        val byId = stored.filter { it.id in keep }.associateBy { it.id }

        // Keys may be swapped inside one save (a -> b and b -> a): park every renamed row on a key no request can carry first.
        list.forEach { draft ->
            val row = draft.id?.let { byId.getValue(it) } ?: return@forEach

            if (row.fieldKey != draft.fieldKey) fields.update(copyField(row, fieldKey = "~${row.id}", now = now), conn)
        }

        list.forEachIndexed { index, draft ->
            val row = draft.id?.let { byId.getValue(it) }
            val entity = MarketProductField(
                id = row?.id ?: -1,
                productId = productId,
                fieldKey = draft.fieldKey,
                label = draft.label,
                helpText = draft.helpText,
                type = draft.type,
                required = draft.required,
                options = draft.options?.takeIf { it.isNotEmpty() }?.let { options ->
                    io.vertx.core.json.JsonArray(options.map { JsonObject().put("value", it.first).put("label", it.second) }).encode()
                },
                pattern = draft.pattern,
                minLength = draft.minLength,
                maxLength = draft.maxLength,
                minValue = draft.minValue,
                maxValue = draft.maxValue,
                placeholder = draft.placeholder,
                defaultValue = draft.defaultValue,
                usableInCommands = draft.usableInCommands,
                position = draft.position ?: index,
                createdAt = row?.createdAt ?: now,
                updatedAt = now
            )

            if (row == null) {
                fields.add(entity, conn) ?: throw InvalidProduct(mapOf("fields.$index.fieldKey" to "DUPLICATE"))
            } else if (!fields.update(entity, conn)) {
                throw InvalidProduct(mapOf("fields.$index.fieldKey" to "DUPLICATE"))
            }
        }
    }

    private fun copyField(row: MarketProductField, fieldKey: String, now: Long) = MarketProductField(
        id = row.id, productId = row.productId, fieldKey = fieldKey, label = row.label, helpText = row.helpText, type = row.type,
        required = row.required, options = row.options, pattern = row.pattern, minLength = row.minLength, maxLength = row.maxLength,
        minValue = row.minValue, maxValue = row.maxValue, placeholder = row.placeholder, defaultValue = row.defaultValue,
        usableInCommands = row.usableInCommands, position = row.position, createdAt = row.createdAt, updatedAt = now
    )

    private suspend fun applyBundle(conn: SqlClient, productId: Long, list: List<BundleItemDraft>, existing: List<MarketBundleItem>) {
        val byKey = existing.associateBy { it.productId to it.variantId }
        val wanted = list.map { it.productId to it.variantId }.toSet()

        existing.filter { (it.productId to it.variantId) !in wanted }.forEach { bundleItems.deleteById(it.id, conn) }

        list.forEachIndexed { index, item ->
            val row = byKey[item.productId to item.variantId]
            val entity = MarketBundleItem(
                id = row?.id ?: -1,
                bundleProductId = productId,
                productId = item.productId,
                variantId = item.variantId,
                quantity = item.quantity,
                position = item.position ?: index
            )

            if (row == null) bundleItems.add(entity, conn) else bundleItems.update(entity, conn)
        }
    }

    private suspend fun applyProviderMeta(conn: SqlClient, productId: Long, wanted: Map<String, String>, now: Long) {
        providerMeta.getByProductId(productId, conn)
            .filter { it.variantId == 0L && it.providerId !in wanted }
            .forEach { providerMeta.deleteById(it.id, conn) }

        wanted.forEach { (providerId, meta) ->
            providerMeta.upsert(
                MarketProductProviderMeta(productId = productId, variantId = 0, providerId = providerId, meta = meta, createdAt = now, updatedAt = now),
                conn
            )
        }
    }

    // ----- delete ------------------------------------------------------------------------------------------------

    /**
     * 01 section 13: a product that anything references (order item, entitlement, subscription, cart line, bundle row)
     * is soft deleted: `deletedAt`, `status = ARCHIVED`, slug rewritten, removed from carts, order snapshots keep
     * working. An unreferenced product is deleted with its variants, prices, fields, bundle rows and provider meta.
     */
    suspend fun delete(id: Long): DeleteResult = db.tx { conn ->
        val product = products.getByIdForUpdate(id, conn)?.takeIf { it.deletedAt == null } ?: throw NotFound()
        val now = clock.now()

        if (products.isReferenced(id, conn)) {
            products.markDeleted(id, SlugRules.archivedSlug(product.slug, id), now, conn)
            products.removeFromCarts(id, conn)

            DeleteResult(true, emptyList())
        } else {
            val files = mutableListOf<String>()
            product.imageFileName?.let { files.add(it) }
            variants.getByProductId(id, true, conn).forEach { v -> v.imageFileName?.let { files.add(it) } }

            prices.deleteByProductId(id, conn)
            variants.deleteByProductId(id, conn)
            fields.deleteByProductId(id, conn)
            bundleItems.deleteByBundleProductId(id, conn)
            providerMeta.deleteByProductId(id, conn)
            products.deleteById(id, conn)

            DeleteResult(false, files)
        }
    }

    // ----- stock -------------------------------------------------------------------------------------------------

    /**
     * 04 section 5 `POST /products/:id/stock`. `SET` writes [value] (`null` = unlimited), `ADJUST` adds a signed delta
     * with the guard `stock IS NOT NULL AND 0 <= stock + delta <= MAX_STOCK` in one statement (no read-modify-write).
     * A product with variants is addressed through [variantId]. Refusals are `INVALID_PRODUCT` with the code under
     * `value` / `variantId` / `mode`. Returns the stock after the change.
     */
    suspend fun changeStock(productId: Long, variantId: Long?, mode: StockMode, value: Int?): StockResult = db.tx { conn ->
        val product = products.getByIdForUpdate(productId, conn)?.takeIf { it.deletedAt == null } ?: throw NotFound()
        val errors = linkedMapOf<String, String>()

        var variant: MarketProductVariant? = null

        if (product.hasVariants) {
            if (variantId == null) errors["variantId"] = "REQUIRED"
            else {
                variant = variants.getById(variantId, conn)?.takeIf { it.productId == productId && it.deletedAt == null }
                if (variant == null) errors["variantId"] = "NOT_FOUND"
            }
        } else if (variantId != null) {
            errors["variantId"] = "NOT_APPLICABLE"
        }

        when (mode) {
            StockMode.SET -> if (value != null && (value < 0 || value > ProductRules.MAX_STOCK)) errors["value"] = "OUT_OF_RANGE"
            StockMode.ADJUST -> when {
                value == null -> errors["value"] = "REQUIRED"
                value == 0 || value < -ProductRules.MAX_STOCK || value > ProductRules.MAX_STOCK -> errors["value"] = "OUT_OF_RANGE"
            }
        }

        if (errors.isNotEmpty()) throw InvalidProduct(errors)

        val target = variant?.id

        when (mode) {
            StockMode.SET -> if (target == null) products.setStock(productId, value, conn) else variants.setStock(target, value, conn)
            StockMode.ADJUST -> {
                val changed = if (target == null) products.adjustStock(productId, value!!, conn) else variants.adjustStock(target, value!!, conn)

                if (!changed) {
                    val current = currentStock(conn, productId, target)

                    throw InvalidProduct(mapOf("value" to if (current == null) "STOCK_UNLIMITED" else "STOCK_OUT_OF_RANGE"))
                }
            }
        }

        StockResult(currentStock(conn, productId, target))
    }

    private suspend fun currentStock(conn: SqlClient, productId: Long, variantId: Long?): Int? =
        if (variantId == null) products.getById(productId, conn)?.stock else variants.getById(variantId, conn)?.stock

    // ----- reads -------------------------------------------------------------------------------------------------

    suspend fun get(id: Long): ProductView = db.tx { conn ->
        val product = products.getById(id, conn)?.takeIf { it.deletedAt == null } ?: throw NotFound()
        val liveVariants = variants.getByProductId(id, false, conn)
        val liveIds = liveVariants.map { it.id }.toSet()

        ProductView(
            product = product,
            variants = liveVariants,
            fields = fields.getByProductId(id, conn),
            bundleItems = bundleItems.getByBundleProductId(id, conn),
            prices = prices.getByProductId(id, conn).filter { it.variantId == 0L || it.variantId in liveIds },
            providerMeta = providerMeta.getByProductId(id, conn).filter { it.variantId == 0L }.associate { it.providerId to it.meta }
        )
    }

    class Page(val products: List<MarketProduct>, val count: Long)

    suspend fun list(page: Long, pageSize: Int, search: String?, status: String?, kind: ProductKind?, categoryId: Long?): Page =
        db.tx { conn ->
            Page(
                products.getAllPaged(page, pageSize, search, status, kind, categoryId, conn),
                products.count(search, status, kind, categoryId, conn)
            )
        }
}
