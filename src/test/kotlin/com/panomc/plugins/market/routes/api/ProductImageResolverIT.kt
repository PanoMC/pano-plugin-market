package com.panomc.plugins.market.routes.api

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.MarketStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which stored file the public product image route serves for a URL name (04 section 3, `images`; MK-064), on a real
 * MariaDB: a product image, a variant image, and 404 (`null`) for unknown names and for images of INACTIVE, ARCHIVED or
 * soft deleted products and soft deleted variants.
 */
class ProductImageResolverIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val fx by lazy { Fixtures(w) }
    private val resolver by lazy { ProductImageResolver(w.products, w.variants) }

    override suspend fun assertInvariants() {}

    private suspend fun resolve(name: String) = resolver.resolve(name, pool)

    private suspend fun productWithImage(image: String, status: MarketStatus = MarketStatus.ACTIVE, deletedAt: Long? = null) =
        fx.product(status = status, columns = buildMap {
            put("imageFileName", image)
            if (deletedAt != null) put("deletedAt", deletedAt)
        })

    private suspend fun variantWithImage(product: com.panomc.plugins.market.db.model.MarketProduct, image: String, deletedAt: Long? = null) {
        val variant = fx.variant(product)
        Fixtures.setColumns(pool, "market_product_variant", variant.id, buildMap {
            put("imageFileName", image)
            if (deletedAt != null) put("deletedAt", deletedAt)
        })
    }

    @Test
    fun `a product image resolves to its stored name`() = runBlocking {
        productWithImage("p-image.png")

        assertEquals("p-image.png", resolve("p-image.png"))
    }

    @Test
    fun `a variant image resolves to the stored name of the variant`() = runBlocking {
        val product = fx.product()
        variantWithImage(product, "v-image.png")

        assertEquals("v-image.png", resolve("v-image.png"))
    }

    @Test
    fun `an unknown name is null`() = runBlocking {
        productWithImage("known.png")

        assertNull(resolve("unknown.png"))
        assertNull(resolve("../known.png"))
    }

    @Test
    fun `an image of a hidden product is null for every non visible state`() = runBlocking {
        productWithImage("inactive.png", status = MarketStatus.INACTIVE)
        productWithImage("archived.png", status = MarketStatus.ARCHIVED)
        productWithImage("deleted.png", deletedAt = w.clock.now())

        assertNull(resolve("inactive.png"))
        assertNull(resolve("archived.png"))
        assertNull(resolve("deleted.png"))
    }

    @Test
    fun `a variant image of an INACTIVE ARCHIVED or soft deleted product is null`() = runBlocking {
        variantWithImage(fx.product(status = MarketStatus.INACTIVE), "v-inactive.png")
        variantWithImage(fx.product(status = MarketStatus.ARCHIVED), "v-archived.png")
        variantWithImage(fx.product(columns = mapOf("deletedAt" to w.clock.now())), "v-deleted-product.png")

        assertNull(resolve("v-inactive.png"))
        assertNull(resolve("v-archived.png"))
        assertNull(resolve("v-deleted-product.png"))
    }

    @Test
    fun `a soft deleted variant image is null while the live variant of the same product still resolves`() = runBlocking {
        val product = fx.product()
        variantWithImage(product, "v-gone.png", deletedAt = w.clock.now())
        variantWithImage(product, "v-live.png")

        assertNull(resolve("v-gone.png"))
        assertEquals("v-live.png", resolve("v-live.png"))
    }
}
