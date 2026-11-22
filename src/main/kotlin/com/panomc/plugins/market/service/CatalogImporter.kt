package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.catalog.ImageChange
import com.panomc.plugins.market.core.catalog.ProductInput
import com.panomc.plugins.market.core.catalog.ProductRules
import com.panomc.plugins.market.core.catalog.SlugRules
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductProviderMetaDao
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.SlugAlreadyExists
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.ImportedCategory
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.util.HtmlSanitizer
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Writes the catalogue a payment provider returned from an import action ([ActionResult.CatalogImport], Tebex / PayNow).
 *
 * - Categories are matched by name under the same parent (case-insensitive) and created when missing.
 * - Products are matched by their product-level provider meta (the provider's package link); a match is updated (name,
 *   description, price, category), anything else is created with the provider meta set. Other settings an admin made
 *   (actions, status, other providers' meta) are never touched; an image is added only to a product that has none.
 * - A recurring package becomes a subscription product billed every `intervalCount` `intervalUnit`. On a match the
 *   interval is updated only when the product is a subscription already: an admin's choice of another billing mode
 *   stays. A recurring package without a usable interval (count 1 to [ProductRules.MAX_PERIOD_COUNT]) is skipped.
 * - Every product is saved on its own: one invalid package does not stop the rest.
 */
class CatalogImporter(
    private val db: MarketDb,
    private val config: () -> MarketConfig,
    private val catalog: CatalogService,
    private val categories: MarketCategoryDao,
    private val products: MarketProductDao,
    private val providerMeta: MarketProductProviderMetaDao,
    /** Stores a package's image; `null` = products are imported without images. */
    private val images: ProductImageFetcher? = null
) {
    class Result(val success: Boolean, val text: LocalizedText)

    private val logger = LoggerFactory.getLogger(CatalogImporter::class.java)

    suspend fun import(providerId: String, data: ActionResult.CatalogImport): Result {
        val storeCurrency = config().currency
        val foreign = data.products.firstOrNull { !it.price.currency.equals(storeCurrency, ignoreCase = true) }?.price?.currency

        if (foreign != null) return Result(false, currencyMismatch(storeCurrency, foreign))

        var categoriesCreated = 0
        val categoryIds = HashMap<String, Long>()
        val known = db.tx { conn -> categories.getAll(null, conn) }.toMutableList()

        for (category in parentsFirst(data.categories)) {
            val name = category.name.trim().take(255)
            if (name.isEmpty()) continue
            val parentId = category.parentExternalId?.let { categoryIds[it] }
            val match = known.firstOrNull { it.parentId == parentId && it.name.equals(name, ignoreCase = true) }

            if (match != null) {
                categoryIds[category.externalId] = match.id
                continue
            }

            val body = JsonObject().put("name", name)
            if (parentId != null) body.put("parentId", parentId)
            val saved = catalog.createCategory(body)
            categoryIds[category.externalId] = saved.id
            known.add(MarketCategory(id = saved.id, name = name, parentId = parentId))
            categoriesCreated++
        }

        val links = db.tx { conn -> providerMeta.getByProviderId(providerId, conn) }
            .filter { it.variantId == 0L }
            .mapNotNull { row -> runCatching { JsonObject(row.meta) }.getOrNull()?.let { it to row.productId } }

        var created = 0
        var updated = 0
        var skipped = 0
        var failed = 0

        for (product in data.products) {
            val unit = product.intervalUnit
            val count = product.intervalCount?.takeIf { it in 1..ProductRules.MAX_PERIOD_COUNT }
            if (unit != null && count == null) {
                skipped++
                continue
            }
            val interval = if (unit != null && count != null) periodUnitOf(unit) to count else null

            val name = product.name.trim().take(255)
            if (name.isEmpty()) {
                skipped++
                continue
            }

            val scalars = linkedMapOf<String, Any?>("name" to name, "price" to product.price.amount)
            product.categoryExternalId?.let { categoryIds[it] }?.let { scalars["categoryId"] = it }
            HtmlSanitizer.sanitizeOrNull(product.descriptionHtml)?.takeIf { it.length <= ProductRules.MAX_DESCRIPTION }?.let {
                scalars["description"] = it.ifBlank { null }
            }

            val linkedId = links.firstOrNull { it.first == product.meta }?.second
            val existing = linkedId?.let { id -> db.tx { conn -> products.getById(id, conn) } }?.takeIf { it.deletedAt == null }

            if (interval != null && (existing == null || existing.billingMode == BillingMode.SUBSCRIPTION)) {
                scalars["billingMode"] = BillingMode.SUBSCRIPTION
                scalars["periodUnit"] = interval.first
                scalars["periodCount"] = interval.second
            }

            val image = if (existing?.imageFileName == null) product.imageUrl?.let { url -> images?.fetch(url) } else null
            val imageChange = image?.let { ImageChange.Set(it) } ?: ImageChange.Keep

            try {
                if (existing != null) {
                    catalog.update(existing.id, ProductInput(scalars, image = imageChange))
                    updated++
                } else {
                    createWithFreeSlug(scalars, name, mapOf(providerId to product.meta.encode()), imageChange)
                    created++
                }
            } catch (e: Throwable) {
                if (e is Error || e is kotlinx.coroutines.CancellationException) throw e
                image?.let { images?.discard(it) }
                failed++
                logger.warn("Catalog import ($providerId): package ${product.externalId} not saved: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
        }

        return Result(failed == 0 || created + updated > 0, summary(created, updated, skipped, failed, categoriesCreated))
    }

    /** A new product; a slug another product holds gets `-2`, `-3` … (up to 50 tries). */
    private suspend fun createWithFreeSlug(scalars: Map<String, Any?>, name: String, meta: Map<String, String>, image: ImageChange) {
        val base = SlugRules.resolve(null, name)

        for (n in 1..MAX_SLUG_TRIES) {
            val withSlug = if (n == 1) scalars else scalars + ("slug" to "$base-$n")

            try {
                catalog.create(ProductInput(withSlug, providerMeta = meta, image = image))
                return
            } catch (e: SlugAlreadyExists) {
                if (n == MAX_SLUG_TRIES) throw e
            }
        }
    }

    companion object {
        const val MAX_SLUG_TRIES = 50

        fun periodUnitOf(unit: IntervalUnit): PeriodUnit = when (unit) {
            IntervalUnit.DAY -> PeriodUnit.DAY
            IntervalUnit.WEEK -> PeriodUnit.WEEK
            IntervalUnit.MONTH -> PeriodUnit.MONTH
            IntervalUnit.YEAR -> PeriodUnit.YEAR
        }

        /** [list] ordered so that every parent comes before its children; a cycle or a missing parent is placed at the top level. */
        fun parentsFirst(list: List<ImportedCategory>): List<ImportedCategory> {
            val ids = list.map { it.externalId }.toSet()
            val done = LinkedHashMap<String, ImportedCategory>()
            var remaining = list

            while (remaining.isNotEmpty()) {
                val ready = remaining.filter { c -> c.parentExternalId == null || c.parentExternalId !in ids || c.parentExternalId in done }
                val next = ready.ifEmpty { listOf(remaining.first()) }
                next.forEach { done.putIfAbsent(it.externalId, it) }
                remaining = remaining.filter { it.externalId !in done }
            }

            return done.values.toList()
        }

        fun currencyMismatch(store: String, gateway: String): LocalizedText {
            val s = store.uppercase()
            val g = gateway.uppercase()

            return LocalizedText.of(
                "Nothing was imported: your store currency is $s but the payment provider's account uses $g. Set the store currency to $g first.",
                "tr" to "Hiçbir şey içe aktarılmadı: mağaza para birimi $s, ödeme sağlayıcısının hesabı ise $g kullanıyor. Önce mağaza para birimini $g yapın.",
                "ru" to "Ничего не импортировано: валюта магазина $s, а аккаунт платёжного провайдера использует $g. Сначала установите валюту магазина $g."
            )
        }

        fun summary(created: Int, updated: Int, skipped: Int, failed: Int, categories: Int): LocalizedText = LocalizedText.of(
            "Import finished: $created products created, $updated updated, $categories categories created" +
                (if (skipped > 0) ", $skipped skipped (no name, or a subscription without a renewal period)" else "") +
                (if (failed > 0) ", $failed could not be saved (see the log)" else "") + ".",
            "tr" to "İçe aktarma bitti: $created ürün oluşturuldu, $updated güncellendi, $categories kategori oluşturuldu" +
                (if (skipped > 0) ", $skipped atlandı (adı yok veya yenilenme süresi olmayan bir abonelik)" else "") +
                (if (failed > 0) ", $failed kaydedilemedi (günlüğe bakın)" else "") + ".",
            "ru" to "Импорт завершён: создано товаров: $created, обновлено: $updated, создано категорий: $categories" +
                (if (skipped > 0) ", пропущено: $skipped (нет названия или подписка без периода продления)" else "") +
                (if (failed > 0) ", не удалось сохранить: $failed (см. журнал)" else "") + "."
        )
    }
}
