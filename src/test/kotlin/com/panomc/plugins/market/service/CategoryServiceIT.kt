package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.catalog.ImageChange
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.error.CategoryInUse
import com.panomc.plugins.market.error.InvalidCategoryMove
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The category part of `CatalogService` on a real MariaDB (MK-051): create with `tiered` / `upgradeMode`, a PUT that is a
 * partial update (the old route reset `parentId`), the parent rules, the tier lock (`CATEGORY_IN_USE`) and delete.
 */
class CategoryServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val service by lazy {
        CatalogService(w.db, { w.config }, w.clock, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.providerMeta, w.categories, w.comparisons)
    }

    private fun json(vararg pairs: Pair<String, Any?>) = JsonObject(mapOf(*pairs))

    private suspend fun fieldErrors(block: suspend () -> Unit): Map<String, String> {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue(e is BadRequest, "expected BAD_REQUEST, got $e")
        return JsonObject((e as BadRequest).encode(emptyMap())).getJsonObject("fieldErrors").map.mapValues { it.value as String }
    }

    private suspend fun entitlement(categoryId: Long, status: EntitlementStatus, order: Long) {
        val product = w.fixtures.product()

        w.entitlements.add(
            MarketEntitlement(
                playerUsername = "steve", ownerKey = "u:1", productId = product.id, orderId = order, orderItemId = order,
                status = status, tierCategoryId = categoryId, tierRank = 1, pricePaid = 1000
            ),
            pool
        )
    }

    @Test
    fun `create stores defaults tiered and upgrade mode and appends at the end`(): Unit = runBlocking {
        val first = service.createCategory(json("name" to "  Ranks "))
        val second = service.createCategory(json("name" to "Tiers", "tiered" to "true", "upgradeMode" to "FULL", "color" to "#fff", "status" to "INACTIVE"))

        val a = w.categories.getById(first.id, pool)!!
        val b = w.categories.getById(second.id, pool)!!

        assertEquals("Ranks", a.name)
        assertEquals("fa-folder", a.icon)
        assertEquals("#0d6efd", a.color)
        assertEquals(MarketStatus.ACTIVE, a.status)
        assertFalse(a.tiered)
        assertEquals(UpgradeMode.DIFFERENCE, a.upgradeMode)
        assertEquals(0, a.position)

        assertTrue(b.tiered)
        assertEquals(UpgradeMode.FULL, b.upgradeMode)
        assertEquals("#fff", b.color)
        assertEquals(MarketStatus.INACTIVE, b.status)
        assertEquals(1, b.position)
    }

    @Test
    fun `create refuses bad values with a path and a missing parent`(): Unit = runBlocking {
        val errors = fieldErrors {
            service.createCategory(json("name" to "", "color" to "red;x", "status" to "ARCHIVED", "tiered" to "maybe", "upgradeMode" to "HALF", "position" to "-1", "icon" to "x".repeat(65)))
        }

        assertEquals("REQUIRED", errors["name"])
        assertEquals("INVALID", errors["color"])
        assertEquals("INVALID", errors["status"])
        assertEquals("INVALID", errors["tiered"])
        assertEquals("INVALID", errors["upgradeMode"])
        assertEquals("INVALID", errors["position"])
        assertEquals("INVALID", errors["icon"])
        assertEquals("REQUIRED", fieldErrors { service.createCategory(json("color" to "#fff")) }["name"])
        assertEquals(0L, count("market_category"))

        assertThrows(InvalidCategoryMove::class.java) { runBlocking { service.createCategory(json("name" to "X", "parentId" to 99999)) } }
        assertEquals(0L, count("market_category"))
    }

    @Test
    fun `update is a partial update and keeps parent icon color status and description`(): Unit = runBlocking {
        val parent = service.createCategory(json("name" to "Parent")).id
        val id = service.createCategory(
            json("name" to "Child", "parentId" to parent, "icon" to "fa-gem", "color" to "#112233", "status" to "INACTIVE", "description" to "d", "position" to 7)
        ).id

        val result = service.updateCategory(id, json("name" to "Renamed"))

        val row = w.categories.getById(id, pool)!!
        assertEquals("Renamed", row.name)
        assertEquals(parent, row.parentId)
        assertEquals("fa-gem", row.icon)
        assertEquals("#112233", row.color)
        assertEquals(MarketStatus.INACTIVE, row.status)
        assertEquals("d", row.description)
        assertEquals(7, row.position)
        assertEquals(mapOf<String, Any?>("name" to "Renamed"), result.changes)

        service.updateCategory(id, json())
        assertEquals("Renamed", w.categories.getById(id, pool)!!.name)
    }

    @Test
    fun `an explicit null parent moves the category to the root and a blank description clears it`(): Unit = runBlocking {
        val parent = service.createCategory(json("name" to "Parent")).id
        val id = service.createCategory(json("name" to "Child", "parentId" to parent, "description" to "x")).id

        service.updateCategory(id, JsonObject().putNull("parentId").put("description", ""))

        val row = w.categories.getById(id, pool)!!
        assertNull(row.parentId)
        assertNull(row.description)
    }

    @Test
    fun `reparenting onto itself or its own subtree or a missing category is refused`(): Unit = runBlocking {
        val a = service.createCategory(json("name" to "A")).id
        val b = service.createCategory(json("name" to "B", "parentId" to a)).id
        val c = service.createCategory(json("name" to "C", "parentId" to b)).id

        assertThrows(InvalidCategoryMove::class.java) { runBlocking { service.updateCategory(a, json("parentId" to a)) } }
        assertThrows(InvalidCategoryMove::class.java) { runBlocking { service.updateCategory(a, json("parentId" to c)) } }
        assertThrows(InvalidCategoryMove::class.java) { runBlocking { service.updateCategory(c, json("parentId" to 99999)) } }
        assertEquals(null, w.categories.getById(a, pool)!!.parentId)

        service.updateCategory(c, json("parentId" to a))
        assertEquals(a, w.categories.getById(c, pool)!!.parentId)

        assertThrows(NotFound::class.java) { runBlocking { service.updateCategory(99999, json("name" to "x")) } }
    }

    @Test
    fun `update refuses an empty name a bad value and the archived status`(): Unit = runBlocking {
        val id = service.createCategory(json("name" to "A")).id

        assertEquals("REQUIRED", fieldErrors { service.updateCategory(id, json("name" to "  ")) }["name"])
        assertEquals("INVALID", fieldErrors { service.updateCategory(id, json("status" to "ARCHIVED")) }["status"])
        assertEquals("INVALID", fieldErrors { service.updateCategory(id, json("color" to "blue")) }["color"])
        assertEquals("A", w.categories.getById(id, pool)!!.name)
    }

    @Test
    fun `a tiered category with an active entitlement cannot be untiered or deleted`(): Unit = runBlocking {
        val id = service.createCategory(json("name" to "Ranks", "tiered" to true)).id
        entitlement(id, EntitlementStatus.ACTIVE, 1)

        assertThrows(CategoryInUse::class.java) { runBlocking { service.updateCategory(id, json("tiered" to false)) } }
        assertTrue(w.categories.getById(id, pool)!!.tiered)

        assertThrows(CategoryInUse::class.java) { runBlocking { service.deleteCategory(id) } }
        assertNotNull(w.categories.getById(id, pool))

        // other changes are fine while the lock holds, also switching the upgrade mode
        service.updateCategory(id, json("name" to "Ranks 2", "upgradeMode" to "FULL"))
        assertEquals(UpgradeMode.FULL, w.categories.getById(id, pool)!!.upgradeMode)
    }

    @Test
    fun `a tiered category whose entitlements ended can be untiered and deleted`(): Unit = runBlocking {
        val untier = service.createCategory(json("name" to "A", "tiered" to true)).id
        val delete = service.createCategory(json("name" to "B", "tiered" to true)).id
        listOf(EntitlementStatus.EXPIRED, EntitlementStatus.REVOKED, EntitlementStatus.UPGRADED).forEachIndexed { i, status ->
            entitlement(untier, status, 10L + i)
            entitlement(delete, status, 20L + i)
        }

        service.updateCategory(untier, json("tiered" to false))
        assertFalse(w.categories.getById(untier, pool)!!.tiered)

        service.deleteCategory(delete)
        assertNull(w.categories.getById(delete, pool))
    }

    @Test
    fun `an active entitlement of another category does not lock this one`(): Unit = runBlocking {
        val a = service.createCategory(json("name" to "A", "tiered" to true)).id
        val b = service.createCategory(json("name" to "B", "tiered" to true)).id
        entitlement(a, EntitlementStatus.ACTIVE, 1)

        service.updateCategory(b, json("tiered" to false))
        service.deleteCategory(b)

        assertNull(w.categories.getById(b, pool))
        assertNotNull(w.categories.getById(a, pool))
    }

    @Test
    fun `an untiered category with an old entitlement row can still be deleted`(): Unit = runBlocking {
        val id = service.createCategory(json("name" to "Plain")).id
        entitlement(id, EntitlementStatus.ACTIVE, 1)

        service.deleteCategory(id)

        assertNull(w.categories.getById(id, pool))
    }

    @Test
    fun `delete moves children up detaches products and returns the image`(): Unit = runBlocking {
        val root = service.createCategory(json("name" to "Root")).id
        val mid = service.createCategory(json("name" to "Mid", "parentId" to root), ImageChange.Set("cat.png")).id
        val leaf = service.createCategory(json("name" to "Leaf", "parentId" to mid)).id
        val product = w.fixtures.product(categoryId = mid)

        val result = service.deleteCategory(mid)

        assertEquals("Mid", result.name)
        assertEquals(listOf("cat.png"), result.orphanedFiles)
        assertEquals(root, w.categories.getById(leaf, pool)!!.parentId)
        assertNull(w.products.getById(product.id, pool)!!.categoryId)
        assertThrows(NotFound::class.java) { runBlocking { service.deleteCategory(mid) } }
    }

    @Test
    fun `image changes report the replaced file as an orphan`(): Unit = runBlocking {
        val id = service.createCategory(json("name" to "Img"), ImageChange.Set("one.png")).id
        assertEquals("one.png", w.categories.getById(id, pool)!!.imageFileName)

        assertEquals(emptyList<String>(), service.updateCategory(id, json("name" to "Img2")).orphanedFiles)
        assertEquals("one.png", w.categories.getById(id, pool)!!.imageFileName)

        assertEquals(listOf("one.png"), service.updateCategory(id, json(), ImageChange.Set("two.png")).orphanedFiles)
        assertEquals("two.png", w.categories.getById(id, pool)!!.imageFileName)

        assertEquals(listOf("two.png"), service.updateCategory(id, json(), ImageChange.Remove).orphanedFiles)
        assertNull(w.categories.getById(id, pool)!!.imageFileName)
    }

    @Test
    fun `concurrent partial updates of different fields both persist`(): Unit = runBlocking {
        val id = service.createCategory(json("name" to "Base")).id

        repeat(5) { round ->
            val results = Race.run(2) { i ->
                if (i == 0) service.updateCategory(id, json("name" to "Name $round")) else service.updateCategory(id, json("color" to "#00000$round"))
            }

            assertTrue(results.all { it.isSuccess }, results.toString())

            val row = w.categories.getById(id, pool)!!
            assertEquals("Name $round", row.name)
            assertEquals("#00000$round", row.color)
        }
    }

    @Test
    fun `untiering racing a new entitlement never leaves an untiered category with a live ladder entry check blind`(): Unit = runBlocking {
        val id = service.createCategory(json("name" to "Ranks", "tiered" to true)).id
        entitlement(id, EntitlementStatus.ACTIVE, 1)

        val results = Race.run(4) { service.updateCategory(id, json("tiered" to false)) }

        assertTrue(results.all { it.exceptionOrNull() is CategoryInUse })
        assertTrue(w.categories.getById(id, pool)!!.tiered)
    }
}
