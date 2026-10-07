package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.ActionGuard
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
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductProviderMetaDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketBundleItem
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.db.model.MarketComparison
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductField
import com.panomc.plugins.market.db.model.MarketProductPrice
import com.panomc.plugins.market.db.model.MarketProductProviderMeta
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.CategoryInUse
import com.panomc.plugins.market.error.InvalidCategoryMove
import com.panomc.plugins.market.error.InvalidProduct
import com.panomc.plugins.market.error.ReservedSlug
import com.panomc.plugins.market.error.SlugAlreadyExists
import com.panomc.plugins.market.routes.panel.product.ProductActionCheck
import com.panomc.plugins.market.routes.panel.product.ProductActionRules
import com.panomc.plugins.market.util.HtmlSanitizer
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.SlugUtil
import io.vertx.core.json.JsonArray
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
    private val providerMeta: MarketProductProviderMetaDao,
    private val categories: MarketCategoryDao,
    private val comparisons: MarketComparisonDao,
    /** Action validation and the privilege rule of a product save (MK-104). The default is the fail-closed `ProductActionRules()`: no server, no webhook secret, no caller. */
    private val actionCheck: ProductActionCheck = ProductActionRules()
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
        val changes: Map<String, Any?> = emptyMap(),
        /** Webhook secrets created by this save (`actionId -> secret`): shown once, never readable again (11 section 8.2). */
        val generatedSecrets: Map<String, String> = emptyMap()
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

    suspend fun create(input: ProductInput, actionCaller: ActionGuard.Caller? = null): SaveResult = db.tx { conn -> save(conn, null, input, actionCaller) }

    // ----- update ------------------------------------------------------------------------------------------------

    suspend fun update(id: Long, input: ProductInput, actionCaller: ActionGuard.Caller? = null): SaveResult = db.tx { conn -> save(conn, id, input, actionCaller) }

    private suspend fun save(conn: SqlClient, id: Long?, input: ProductInput, actionCaller: ActionGuard.Caller?): SaveResult {
        val now = clock.now()
        val create = id == null
        // The row lock is the first statement: saves, deletes and stock changes of one product serialise, so a partial
        // update is always laid over the latest committed row and never over a stale read.
        val base = if (id == null) null else products.getByIdForUpdate(id, conn)?.takeIf { it.deletedAt == null } ?: throw NotFound()

        val baseCurrency = config().currency
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
        checkBundleMembership(conn, base, product, errors)

        if (errors.isNotEmpty()) throw InvalidProduct(errors)

        // 08 section 2.2 and 11 section 14.4: the strict action rules, then the privilege rule, against the stored row that the lock above holds
        suspend fun fieldRules(): Map<String, Boolean> =
            input.fields?.associate { it.fieldKey to it.usableInCommands } ?: base?.let { b -> fields.getByProductId(b.id, conn).associate { it.fieldKey to it.usableInCommands } }.orEmpty()

        val checked = input.actions?.let { submitted ->
            actionCheck.onSave(
                conn,
                ProductActionCheck.Request(
                    base?.actions, base?.serverChoices, submitted, product.billingMode, product.maxQuantityPerOrder, product.serverChoices, fieldRules(), actionCaller
                )
            )
        }

        // a partial update that leaves `actions` alone but moves what they depend on is checked against the stored actions: otherwise widening
        // `serverChoices` (or switching the billing mode) would be a way around the privilege rule and the strict rules
        if (input.actions == null && base != null && !base.actions.isNullOrBlank() && actionInputsChanged(base, product, input)) {
            actionCheck.onUnchanged(
                conn,
                ProductActionCheck.Unchanged(
                    base.actions, base.serverChoices, product.billingMode, product.maxQuantityPerOrder, product.serverChoices, fieldRules(), actionCaller
                )
            )
        }

        val owner = products.getBySlug(slug, conn)
        if (owner != null && owner.id != base?.id) throw SlugAlreadyExists()

        val orphans = mutableListOf<String>()

        val productImage = when (val change = input.image) {
            is ImageChange.Keep -> base?.imageFileName
            is ImageChange.Remove -> null
            is ImageChange.Set -> change.fileName
        }
        if (base?.imageFileName != null && productImage != base.imageFileName) orphans.add(base.imageFileName)

        val toStore = withImage(product, productImage, checked?.json ?: product.actions)

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

        return SaveResult(productId, slug, product.name, warnings, orphans, if (base == null) emptyMap() else changes(base, toStore), checked?.generatedSecrets.orEmpty())
    }

    /** `true` when the save moves something the stored actions are validated against (08 section 2.2): choices, billing mode, quantity limit, command fields. */
    private fun actionInputsChanged(before: MarketProduct, after: MarketProduct, input: ProductInput): Boolean =
        before.billingMode != after.billingMode ||
            before.maxQuantityPerOrder != after.maxQuantityPerOrder ||
            ProductActionRules.idList(before.serverChoices).toSet() != ProductActionRules.idList(after.serverChoices).toSet() ||
            input.fields != null

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

    private fun withImage(p: MarketProduct, imageFileName: String?, actions: String? = p.actions): MarketProduct = MarketProduct(
        id = p.id, slug = p.slug, name = p.name, description = p.description, categoryId = p.categoryId, price = p.price,
        creditPrice = p.creditPrice, stock = p.stock, requiredProducts = p.requiredProducts, requireOnlyOne = p.requireOnlyOne,
        requiredPermission = p.requiredPermission, status = p.status, featured = p.featured, durationType = p.durationType,
        durationStart = p.durationStart, durationExpiry = p.durationExpiry, priority = p.priority, icon = p.icon,
        imageFileName = imageFileName, actions = actions, kind = p.kind, shortDescription = p.shortDescription,
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

            val child = live[item.productId]

            if (selfId != null && item.productId == selfId) {
                errors["$path.productId"] = "SELF"
            } else if (child == null) {
                errors["$path.productId"] = "NOT_FOUND"
            } else if (child.kind == ProductKind.BUNDLE) {
                // 01 section 2.6: a bundle never contains a bundle.
                errors["$path.productId"] = "NESTED_BUNDLE"
            } else if (child.kind != ProductKind.STANDARD) {
                errors["$path.productId"] = "INVALID_CHILD"
            } else if (child.billingMode == BillingMode.SUBSCRIPTION) {
                errors["$path.productId"] = "SUBSCRIPTION_CHILD"
            } else if (child.physical) {
                // A bundle is never shipped (its own `physical` is forced to 0), so it cannot hold a physical child.
                errors["$path.productId"] = "PHYSICAL_CHILD"
            } else if (item.variantId > 0) {
                val variant = variants.getById(item.variantId, conn)

                if (variant == null || variant.productId != item.productId || variant.deletedAt != null) errors["$path.variantId"] = "NOT_FOUND"
            }
        }
    }

    /**
     * The other direction of [checkBundleChildren]: a product that is the child of a live bundle cannot become a bundle,
     * another kind, physical or a subscription (the bundle rows would break the rules above).
     */
    private suspend fun checkBundleMembership(conn: SqlClient, base: MarketProduct?, product: MarketProduct, errors: MutableMap<String, String>) {
        if (base == null) return

        val breaksChildRules = product.kind != ProductKind.STANDARD || product.physical || product.billingMode == BillingMode.SUBSCRIPTION

        if (!breaksChildRules) return

        val parentIds = bundleItems.getByChildProductId(base.id, conn).map { it.bundleProductId }.distinct()

        if (parentIds.isEmpty() || products.getByIds(parentIds, conn).none { it.deletedAt == null }) return

        if (product.kind == ProductKind.BUNDLE) {
            errors["kind"] = "NESTED_BUNDLE"
        } else if (product.kind != ProductKind.STANDARD) {
            errors["kind"] = "IN_BUNDLE"
        }

        if (product.physical) errors["physical"] = "IN_BUNDLE"
        if (product.billingMode == BillingMode.SUBSCRIPTION) errors["billingMode"] = "IN_BUNDLE"
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

    // ----- clone -------------------------------------------------------------------------------------------------

    class CloneResult(val id: Long, val slug: String, val name: String)

    /**
     * `POST /products/:id/clone` (04 section 5): a copy that is `INACTIVE`, with a free `<slug>-copy[-n]` slug, the name plus
     * [nameSuffix] (localised by the route), the stored product image copied through [copyImage], and every set part:
     * live variants (new ids, stock and images copied), fields, per-currency prices (variant rows follow the new variant
     * ids), bundle rows and provider meta. Actions get fresh ids (`a1`, `a2`, ...), so the copy counts as new for
     * `ActionGuard`. Counters (`soldCount`) start at 0. One transaction.
     */
    suspend fun clone(id: Long, nameSuffix: String, actionCaller: ActionGuard.Caller? = null, copyImage: (String) -> String? = { null }): CloneResult = db.tx { conn ->
        val original = products.getByIdForUpdate(id, conn)?.takeIf { it.deletedAt == null } ?: throw NotFound()

        // the copy counts as new: whoever clones needs the privilege for every action of the source (11 section 14.4)
        actionCheck.onClone(conn, original.actions, original.serverChoices, actionCaller)
        val now = clock.now()

        var copyNumber = 1
        var slug = SlugUtil.copySlug(original.slug, copyNumber)

        while (products.getBySlug(slug, conn) != null) {
            copyNumber++
            slug = SlugUtil.copySlug(original.slug, copyNumber)
        }

        val name = (original.name + nameSuffix).take(255)

        val product = MarketProduct(
            slug = slug, name = name, description = HtmlSanitizer.sanitizeOrNull(original.description), categoryId = original.categoryId,
            price = original.price, creditPrice = original.creditPrice, stock = original.stock, requiredProducts = original.requiredProducts,
            requireOnlyOne = original.requireOnlyOne, requiredPermission = original.requiredPermission, status = MarketStatus.INACTIVE,
            featured = original.featured, durationType = original.durationType, durationStart = original.durationStart,
            durationExpiry = original.durationExpiry, priority = original.priority, icon = original.icon,
            imageFileName = original.imageFileName?.let(copyImage), actions = withFreshActionIds(original.actions), kind = original.kind,
            shortDescription = original.shortDescription, compareAtPrice = original.compareAtPrice, vatPercent = original.vatPercent,
            physical = original.physical, sku = original.sku, weightGrams = original.weightGrams, lengthMm = original.lengthMm,
            widthMm = original.widthMm, heightMm = original.heightMm, hsCode = original.hsCode, originCountry = original.originCountry,
            billingMode = original.billingMode, periodUnit = original.periodUnit, periodCount = original.periodCount,
            subscriptionMaxCycles = original.subscriptionMaxCycles, limitPerPlayer = original.limitPerPlayer,
            maxQuantityPerOrder = original.maxQuantityPerOrder, cooldownSeconds = original.cooldownSeconds, tierRank = original.tierRank,
            creditAmount = original.creditAmount, allowGift = original.allowGift, serverChoices = original.serverChoices,
            hasVariants = original.hasVariants, variantOptions = original.variantOptions, metaTitle = original.metaTitle,
            metaDescription = original.metaDescription, createdAt = now, updatedAt = now
        )

        val newId = try {
            products.add(product, conn)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) throw SlugAlreadyExists()
            throw e
        }

        val variantMap = linkedMapOf<Long, Long>()

        variants.getByProductId(id, false, conn).forEach { v ->
            variantMap[v.id] = variants.add(
                MarketProductVariant(
                    productId = newId, name = v.name, sku = v.sku, optionValues = v.optionValues, attributes = v.attributes, price = v.price,
                    creditPrice = v.creditPrice, compareAtPrice = v.compareAtPrice, stock = v.stock, weightGrams = v.weightGrams,
                    periodCount = v.periodCount, imageFileName = v.imageFileName?.let(copyImage), position = v.position, status = v.status,
                    createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        prices.getByProductId(id, conn).forEach { row ->
            val variantId = if (row.variantId == 0L) 0L else variantMap[row.variantId] ?: return@forEach

            prices.add(
                MarketProductPrice(
                    productId = newId, variantId = variantId, currency = row.currency, price = row.price,
                    compareAtPrice = row.compareAtPrice, createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        fields.getByProductId(id, conn).forEach { row ->
            fields.add(withProduct(row, newId, now), conn)
        }

        bundleItems.getByBundleProductId(id, conn).forEach { row ->
            bundleItems.add(
                MarketBundleItem(
                    bundleProductId = newId, productId = row.productId, variantId = row.variantId, quantity = row.quantity,
                    position = row.position, createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        providerMeta.getByProductId(id, conn).forEach { row ->
            val variantId = if (row.variantId == 0L) 0L else variantMap[row.variantId] ?: return@forEach

            providerMeta.upsert(
                MarketProductProviderMeta(productId = newId, variantId = variantId, providerId = row.providerId, meta = row.meta, createdAt = now, updatedAt = now),
                conn
            )
        }

        CloneResult(newId, slug, name)
    }

    private fun withProduct(row: MarketProductField, productId: Long, now: Long) = MarketProductField(
        productId = productId, fieldKey = row.fieldKey, label = row.label, helpText = row.helpText, type = row.type,
        required = row.required, options = row.options, pattern = row.pattern, minLength = row.minLength, maxLength = row.maxLength,
        minValue = row.minValue, maxValue = row.maxValue, placeholder = row.placeholder, defaultValue = row.defaultValue,
        usableInCommands = row.usableInCommands, position = row.position, createdAt = now, updatedAt = now
    )

    /** Every action of the stored JSON gets the id `a<position + 1>`: the copy shares no action id with the original. */
    private fun withFreshActionIds(stored: String?): String? {
        if (stored == null) return null

        val array = try {
            JsonArray(stored)
        } catch (e: Exception) {
            return stored
        }

        val out = JsonArray()
        var n = 0

        array.forEach { raw -> if (raw is JsonObject) out.add(raw.copy().put("id", "a${++n}")) }

        return out.encode()
    }

    // ----- categories --------------------------------------------------------------------------------------------

    class CategorySaveResult(
        val id: Long,
        val name: String,
        /** Image file names no row points at any more; delete them after the commit. */
        val orphanedFiles: List<String>,
        /** Changed columns of an update for the activity log; empty on create. */
        val changes: Map<String, Any?> = emptyMap()
    )

    class CategoryDeleteResult(val name: String, val orphanedFiles: List<String>)

    /**
     * `POST /categories` (04 section 5). [data] holds only the keys the request sent (typed: numbers, booleans, strings;
     * a `parentId` of `null` means the root). The parent must exist. A bad value is `400 BAD_REQUEST {fieldErrors}`.
     */
    suspend fun createCategory(data: JsonObject, image: ImageChange = ImageChange.Keep): CategorySaveResult = db.tx { conn ->
        val now = clock.now()
        val errors = linkedMapOf<String, String>()
        val parsed = CategoryRules.parse(data, null, errors)

        if (data.getValue("name") == null || parsed.name.isBlank()) errors["name"] = "REQUIRED"
        if (errors.isNotEmpty()) throw fieldErrors(errors)

        if (parsed.parentId != null && categories.getById(parsed.parentId, conn) == null) throw InvalidCategoryMove()

        val position = if (data.containsKey("position")) parsed.position else categories.getMaxPosition(parsed.parentId, conn) + 1

        val fileName = (image as? ImageChange.Set)?.fileName

        val id = categories.add(
            MarketCategory(
                name = parsed.name, description = parsed.description, icon = parsed.icon, color = parsed.color, status = parsed.status,
                parentId = parsed.parentId, position = position, imageFileName = fileName, tiered = parsed.tiered,
                upgradeMode = parsed.upgradeMode, createdAt = now, updatedAt = now
            ),
            conn
        )

        CategorySaveResult(id, parsed.name, emptyList())
    }

    /**
     * `PUT /categories/:id`: a partial update, a key that was not sent keeps its stored value (the old route reset
     * `parentId`, `icon`, `color` and `status` on every save). Un-tiering a category while an ACTIVE entitlement belongs to
     * its ladder is `409 CATEGORY_IN_USE`. The row is locked first, so concurrent saves and deletes serialise.
     */
    suspend fun updateCategory(id: Long, data: JsonObject, image: ImageChange = ImageChange.Keep): CategorySaveResult = db.tx { conn ->
        val now = clock.now()
        val base = categories.getByIdForUpdate(id, conn) ?: throw NotFound()
        val errors = linkedMapOf<String, String>()
        val parsed = CategoryRules.parse(data, base, errors)

        if (data.containsKey("name") && parsed.name.isBlank()) errors["name"] = "REQUIRED"
        if (errors.isNotEmpty()) throw fieldErrors(errors)

        if (parsed.parentId != base.parentId && parsed.parentId != null) {
            val all = categories.getAll(null, conn)

            if (all.none { it.id == parsed.parentId } || parsed.parentId in CategoryRules.subtreeIds(id, all)) throw InvalidCategoryMove()
        }

        if (base.tiered && !parsed.tiered && categories.hasActiveTierEntitlements(id, conn)) throw CategoryInUse()

        val orphans = mutableListOf<String>()
        val imageFileName = when (image) {
            is ImageChange.Keep -> base.imageFileName
            is ImageChange.Remove -> null
            is ImageChange.Set -> image.fileName
        }
        if (base.imageFileName != null && imageFileName != base.imageFileName) orphans.add(base.imageFileName)

        categories.update(
            MarketCategory(
                id = id, name = parsed.name, description = parsed.description, icon = parsed.icon, color = parsed.color,
                status = parsed.status, parentId = parsed.parentId, position = parsed.position, imageFileName = imageFileName,
                tiered = parsed.tiered, upgradeMode = parsed.upgradeMode, createdAt = base.createdAt, updatedAt = now
            ),
            conn
        )

        val changes = linkedMapOf<String, Any?>()
        if (base.name != parsed.name) changes["name"] = parsed.name
        if (base.description != parsed.description) changes["description"] = parsed.description
        if (base.icon != parsed.icon) changes["icon"] = parsed.icon
        if (base.color != parsed.color) changes["color"] = parsed.color
        if (base.status != parsed.status) changes["status"] = parsed.status.name
        if (base.parentId != parsed.parentId) changes["parentId"] = parsed.parentId
        if (base.position != parsed.position) changes["position"] = parsed.position
        if (base.imageFileName != imageFileName) changes["image"] = imageFileName != null
        if (base.tiered != parsed.tiered) changes["tiered"] = parsed.tiered
        if (base.upgradeMode != parsed.upgradeMode) changes["upgradeMode"] = parsed.upgradeMode.name

        CategorySaveResult(id, parsed.name, orphans, changes)
    }

    /**
     * `DELETE /categories/:id` (01 section 13): children are re-parented to the deleted node's parent, its products are
     * detached; a tiered category with an ACTIVE entitlement cannot be deleted (`409 CATEGORY_IN_USE`).
     */
    suspend fun deleteCategory(id: Long): CategoryDeleteResult = db.tx { conn ->
        val category = categories.getByIdForUpdate(id, conn) ?: throw NotFound()

        if (category.tiered && categories.hasActiveTierEntitlements(id, conn)) throw CategoryInUse()

        categories.reparentChildren(id, category.parentId, conn)
        categories.clearProductsCategory(id, conn)
        categories.deleteById(id, conn)

        CategoryDeleteResult(category.name, listOfNotNull(category.imageFileName))
    }

    // ----- comparisons -------------------------------------------------------------------------------------------

    /** `POST /comparisons` and `PUT /comparisons/:id`: shape and size validation (04 section 5), product ids must exist. */
    suspend fun saveComparison(id: Long?, data: JsonObject): Long = db.tx { conn ->
        val errors = ComparisonRules.validate(data).toMutableMap()
        val base = if (id == null) null else comparisons.getById(id, conn) ?: throw NotFound()

        if (errors.isEmpty()) {
            val ids = ComparisonRules.productIds(data)
            val live = products.getByIds(ids.filterNotNull().distinct(), conn).filter { it.deletedAt == null }.map { it.id }.toSet()

            ids.forEachIndexed { index, pid -> if (pid != null && pid !in live) errors["selectedProducts.$index"] = "NOT_FOUND" }
        }

        if (errors.isNotEmpty()) throw fieldErrors(errors)

        val comparison = MarketComparison(
            id = base?.id ?: -1,
            name = data.getString("name").trim(),
            status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE,
            priority = data.getInteger("priority") ?: 0,
            productIds = (data.getJsonArray("selectedProducts") ?: JsonArray()).encode(),
            features = (data.getJsonArray("features") ?: JsonArray()).encode(),
            cellValues = (data.getJsonObject("cellValues") ?: JsonObject()).encode()
        )

        if (base == null) comparisons.add(comparison, conn) else {
            comparisons.update(comparison, conn)
            base.id
        }
    }

    private fun fieldErrors(errors: Map<String, String>) = BadRequest(extras = mapOf("fieldErrors" to errors))

    class Page(val products: List<MarketProduct>, val count: Long)

    suspend fun list(page: Long, pageSize: Int, search: String?, status: String?, kind: ProductKind?, categoryId: Long?): Page =
        db.tx { conn ->
            Page(
                products.getAllPaged(page, pageSize, search, status, kind, categoryId, conn),
                products.count(search, status, kind, categoryId, conn)
            )
        }
}

/** Validation of a category request (04 section 5): pure, no database. */
object CategoryRules {
    private val HEX_COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    class Parsed(
        val name: String,
        val description: String?,
        val icon: String,
        val color: String,
        val status: MarketStatus,
        val parentId: Long?,
        val position: Int,
        val tiered: Boolean,
        val upgradeMode: UpgradeMode
    )

    /**
     * Lays the keys present in [data] over [base] (or the defaults for a create). A value of the wrong type or range goes to
     * [errors] under its key; the stored value is used in its place so the result is always complete.
     */
    fun parse(data: JsonObject, base: MarketCategory?, errors: MutableMap<String, String>): Parsed {
        fun has(key: String) = data.containsKey(key)

        var name = base?.name ?: ""
        if (has("name")) {
            val v = data.getValue("name")

            if (v is String) {
                if (v.trim().length <= 255) name = v.trim() else errors["name"] = "TOO_LONG"
            } else {
                errors["name"] = "INVALID"
            }
        }

        var description = base?.description
        if (has("description")) {
            val v = data.getValue("description")
            if (v == null) description = null
            else if (v is String && v.length <= 5000) description = v.ifBlank { null }
            else errors["description"] = "INVALID"
        }

        var icon = base?.icon ?: "fa-folder"
        if (has("icon")) {
            val v = data.getValue("icon")
            if (v == null || (v is String && v.isBlank())) icon = "fa-folder"
            else if (v is String && v.length <= 64) icon = v
            else errors["icon"] = "INVALID"
        }

        // The color is interpolated into an inline style on the storefront: only a strict hex color is accepted.
        var color = base?.color ?: "#0d6efd"
        if (has("color")) {
            val v = data.getValue("color")
            if (v == null || (v is String && v.isBlank())) color = "#0d6efd"
            else if (v is String && v.matches(HEX_COLOR)) color = v
            else errors["color"] = "INVALID"
        }

        var status = base?.status ?: MarketStatus.ACTIVE
        if (has("status")) {
            val v = (data.getValue("status") as? String)?.let { s -> MarketStatus.entries.firstOrNull { it.name == s } }
            // ARCHIVED is the soft-delete state of a product, not of a category.
            if (v != null && v != MarketStatus.ARCHIVED) status = v else errors["status"] = "INVALID"
        }

        var parentId = base?.parentId
        if (has("parentId")) {
            val v = data.getValue("parentId")
            if (v == null || (v is String && v.isBlank())) parentId = null
            else {
                val n = asLong(v)
                if (n != null && n > 0) parentId = n else errors["parentId"] = "INVALID"
            }
        }

        var position = base?.position ?: 0
        if (has("position")) {
            val n = asLong(data.getValue("position"))
            if (n != null && n in 0..1_000_000) position = n.toInt() else errors["position"] = "INVALID"
        }

        var tiered = base?.tiered ?: false
        if (has("tiered")) {
            val b = asBoolean(data.getValue("tiered"))
            if (b != null) tiered = b else errors["tiered"] = "INVALID"
        }

        var upgradeMode = base?.upgradeMode ?: UpgradeMode.DIFFERENCE
        if (has("upgradeMode")) {
            val v = (data.getValue("upgradeMode") as? String)?.let { s -> UpgradeMode.entries.firstOrNull { it.name == s } }
            if (v != null) upgradeMode = v else errors["upgradeMode"] = "INVALID"
        }

        return Parsed(name, description, icon, color, status, parentId, position, tiered, upgradeMode)
    }

    /** [rootId] and every category below it (a cycle guard for re-parenting). */
    fun subtreeIds(rootId: Long, categories: List<MarketCategory>): Set<Long> {
        val childrenByParent = categories.groupBy { it.parentId }
        val result = mutableSetOf(rootId)
        val queue = ArrayDeque<Long>()
        queue.add(rootId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            childrenByParent[current].orEmpty().forEach { child -> if (result.add(child.id)) queue.add(child.id) }
        }

        return result
    }

    internal fun asLong(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    internal fun asBoolean(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
        is Int -> if (v == 0 || v == 1) v == 1 else null
        else -> null
    }
}

/** Size and shape limits of a comparison (04 section 5): at most 12 products and 50 features. Pure. */
object ComparisonRules {
    const val MAX_PRODUCTS = 12
    const val MAX_FEATURES = 50
    const val MAX_CELL_LENGTH = 500

    /** The product ids of `selectedProducts` (`null` slots are kept: the stored JSON may hold empty columns). */
    fun productIds(data: JsonObject): List<Long?> =
        (data.getJsonArray("selectedProducts") ?: JsonArray()).map { (it as? Number)?.toLong() }

    /** `{dotted.path: CODE}`; empty when the request is acceptable. */
    fun validate(data: JsonObject): Map<String, String> {
        val errors = linkedMapOf<String, String>()

        val name = data.getValue("name")
        if (name !is String || name.isBlank()) errors["name"] = "REQUIRED" else if (name.trim().length > 255) errors["name"] = "TOO_LONG"

        val priority = data.getValue("priority")
        if (priority != null && (priority !is Int || priority !in -1_000_000..1_000_000)) errors["priority"] = "INVALID"

        val products = data.getValue("selectedProducts")
        if (products != null && products !is JsonArray) {
            errors["selectedProducts"] = "INVALID"
        } else if (products is JsonArray) {
            if (products.size() > MAX_PRODUCTS) errors["selectedProducts"] = "TOO_MANY"

            val seen = mutableSetOf<Long>()

            products.forEachIndexed { index, value ->
                when {
                    value == null -> {}
                    value !is Number || value.toLong() < 1 || value.toDouble() != value.toLong().toDouble() -> errors["selectedProducts.$index"] = "INVALID"
                    !seen.add(value.toLong()) -> errors["selectedProducts.$index"] = "DUPLICATE"
                }
            }
        }

        val features = data.getValue("features")
        if (features != null && features !is JsonArray) {
            errors["features"] = "INVALID"
        } else if (features is JsonArray) {
            if (features.size() > MAX_FEATURES) errors["features"] = "TOO_MANY"

            val ids = mutableSetOf<String>()

            features.forEachIndexed { index, value ->
                if (value !is JsonObject) {
                    errors["features.$index"] = "INVALID"
                    return@forEachIndexed
                }

                val id = value.getValue("id")
                if (id != null && (id !is String || id.isBlank() || id.length > 64)) errors["features.$index.id"] = "INVALID"
                else if (id is String && !ids.add(id)) errors["features.$index.id"] = "DUPLICATE"

                if (value.encode().length > 2000) errors["features.$index"] = "TOO_LONG"
            }
        }

        val cells = data.getValue("cellValues")
        if (cells != null && cells !is JsonObject) {
            errors["cellValues"] = "INVALID"
        } else if (cells is JsonObject) {
            if (cells.size() > MAX_PRODUCTS * MAX_FEATURES) errors["cellValues"] = "TOO_MANY"

            cells.forEach { (key, value) ->
                if (value !is String || value.length > MAX_CELL_LENGTH || key.length > 128) errors["cellValues.$key"] = "INVALID"
            }
        }

        return errors
    }
}

/** The suffix a clone's name gets, by the language of the panel request (04 section 5: "suffix is localised"). */
object CloneSuffix {
    private val SUFFIXES = mapOf("tr" to " (Kopya)", "en" to " (Copy)", "ru" to " (Копия)")

    /** [acceptLanguage] is an `Accept-Language` header value; English when no listed language is named. */
    fun of(acceptLanguage: String?): String {
        val tags = acceptLanguage.orEmpty().split(',').map { it.substringBefore(';').trim().lowercase().substringBefore('-') }

        return tags.firstNotNullOfOrNull { SUFFIXES[it] } ?: SUFFIXES.getValue("en")
    }
}
