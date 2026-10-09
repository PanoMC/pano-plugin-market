package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.GoalMetric
import com.panomc.plugins.market.db.model.GoalPeriod
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.support.ErrorBodies

/** `GoalService` on a real MariaDB (MK-051): goal CRUD, validation, progress / percent, and what resets progress. */
class GoalServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val service by lazy { GoalService(w.db, { w.config }, w.clock, w.goals, w.products) }

    private fun json(vararg pairs: Pair<String, Any?>) = JsonObject(mapOf(*pairs))

    private fun goal(vararg pairs: Pair<String, Any?>) = json("name" to "Goal", "metric" to "REVENUE", "target" to "100", *pairs)

    private suspend fun fieldErrors(block: suspend () -> Unit): Map<String, String> {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue(e is BadRequest, "expected BAD_REQUEST, got $e")
        return ErrorBodies.details((e as BadRequest)).getJsonObject("fieldErrors").map.mapValues { it.value as String }
    }

    @Test
    fun `create stores a revenue goal with money x100 and the store currency`(): Unit = runBlocking {
        w.configure { it }

        val created = service.create(goal("target" to "1234.5", "description" to "d", "startsAt" to 1000, "endsAt" to 2000, "showOnStore" to false))

        assertEquals(123450L, created.target)
        assertEquals(GoalMetric.REVENUE, created.metric)
        assertEquals(w.config.currency, created.currency)
        assertEquals(0L, created.progress)
        assertEquals(GoalPeriod.ONE_TIME, created.period)
        assertNull(created.periodStart)
        assertEquals(1000L, created.startsAt)
        assertEquals(2000L, created.endsAt)
        assertFalse(created.showOnStore)
        assertEquals("ACTIVE", created.status)
        assertNull(created.productIds)
        assertEquals("d", created.description)
    }

    @Test
    fun `a count goal has no currency a whole number target and optional products`(): Unit = runBlocking {
        val a = w.fixtures.product()
        val b = w.fixtures.product()

        val created = service.create(json("name" to "Sales", "metric" to "PRODUCT_SALES", "target" to 50, "productIds" to JsonArray().add(a.id).add(b.id), "period" to "MONTHLY"))

        assertNull(created.currency)
        assertEquals(50L, created.target)
        assertEquals(listOf(a.id, b.id), JsonArray(created.productIds).map { (it as Number).toLong() })
        assertEquals(GoalPeriod.MONTHLY, created.period)
        assertNotNull(created.periodStart)

        val orders = service.create(json("name" to "Orders", "metric" to "ORDERS", "target" to "10", "productIds" to JsonArray()))
        assertNull(orders.productIds)
    }

    @Test
    fun `validation names every bad field and stores nothing`(): Unit = runBlocking {
        val errors = fieldErrors {
            service.create(
                json(
                    "name" to " ", "description" to "x".repeat(513), "metric" to "NOPE", "target" to "-1", "period" to "DAILY",
                    "startsAt" to 500, "endsAt" to 500, "status" to "DONE", "showOnStore" to "x", "position" to -3,
                    "productIds" to JsonArray().add(1).add(1)
                )
            )
        }

        assertEquals("REQUIRED", errors["name"])
        assertEquals("INVALID", errors["description"])
        assertEquals("INVALID", errors["metric"])
        assertEquals("INVALID", errors["target"])
        assertEquals("INVALID", errors["period"])
        assertEquals("BEFORE_START", errors["endsAt"])
        assertEquals("INVALID", errors["status"])
        assertEquals("INVALID", errors["showOnStore"])
        assertEquals("INVALID", errors["position"])
        assertEquals("INVALID", errors["productIds"])
        assertEquals(0L, count("market_goal"))

        val missing = fieldErrors { service.create(json()) }
        assertEquals("REQUIRED", missing["name"])
        assertEquals("REQUIRED", missing["metric"])
        assertEquals("REQUIRED", missing["target"])

        assertEquals("INVALID", fieldErrors { service.create(goal("target" to "1.234")) }["target"])
        assertEquals("INVALID", fieldErrors { service.create(json("name" to "x", "metric" to "ORDERS", "target" to "1.5")) }["target"])
        assertEquals("INVALID", fieldErrors { service.create(json("name" to "x", "metric" to "ORDERS", "target" to 2_000_000_000L)) }["target"])
        assertEquals("NOT_FOUND", fieldErrors { service.create(goal("productIds" to JsonArray().add(99999))) }["productIds.0"])
        assertEquals(0L, count("market_goal"))
    }

    @Test
    fun `a deleted product is not a valid goal product`(): Unit = runBlocking {
        val p = w.fixtures.product()
        com.panomc.plugins.market.support.Fixtures.insertRaw(pool, "market_order_item", mapOf("productId" to p.id))
        w.products.markDeleted(p.id, "gone--d${p.id}", 1, pool)

        assertEquals("NOT_FOUND", fieldErrors { service.create(goal("productIds" to JsonArray().add(p.id))) }["productIds.0"])
    }

    @Test
    fun `list gives progress and a percent capped at 100 and ordered by position`(): Unit = runBlocking {
        val a = service.create(goal("name" to "A", "target" to "200"))
        val b = service.create(json("name" to "B", "metric" to "ORDERS", "target" to 3))
        val c = service.create(json("name" to "C", "metric" to "ORDERS", "target" to 4, "position" to 0))

        w.goals.addProgress(a.id, 5000, 1, pool)
        w.goals.addProgress(b.id, 7, 1, pool)

        val views = service.list()

        assertEquals(listOf("A", "C", "B"), views.map { it.goal.name })
        assertEquals(25, views.first { it.goal.id == a.id }.percent)
        assertEquals(100, views.first { it.goal.id == b.id }.percent)
        assertEquals(0, views.first { it.goal.id == c.id }.percent)
    }

    @Test
    fun `update is partial and keeps progress when only configuration changes`(): Unit = runBlocking {
        val created = service.create(goal("target" to "100", "description" to "keep"))
        w.goals.addProgress(created.id, 4200, 1, pool)

        val updated = service.update(created.id, json("name" to "Renamed", "target" to "500", "showOnStore" to false, "status" to "INACTIVE"))

        assertEquals("Renamed", updated.name)
        assertEquals(50000L, updated.target)
        assertEquals("keep", updated.description)
        assertFalse(updated.showOnStore)
        assertEquals("INACTIVE", updated.status)
        assertEquals(4200L, updated.progress)

        assertNull(service.update(created.id, JsonObject().putNull("description")).description)
        assertEquals(4200L, service.get(created.id).goal.progress)
    }

    @Test
    fun `changing what is measured starts a new period`(): Unit = runBlocking {
        val p = w.fixtures.product()
        val created = service.create(goal())
        w.goals.addProgress(created.id, 999, 1, pool)

        assertEquals(999L, service.update(created.id, json("name" to "same", "target" to "200")).progress)

        val reset = service.update(created.id, json("productIds" to JsonArray().add(p.id)))
        assertEquals(0L, reset.progress)
        assertNotNull(reset.periodStart)

        w.goals.addProgress(created.id, 5, 1, pool)
        assertEquals(0L, service.update(created.id, json("period" to "WEEKLY")).progress)

        w.goals.addProgress(created.id, 5, 1, pool)
        val metric = service.update(created.id, json("metric" to "ORDERS", "target" to 10))
        assertEquals(0L, metric.progress)
        assertNull(metric.currency)
        assertEquals(10L, metric.target)
    }

    @Test
    fun `switching between money and count requires a new target`(): Unit = runBlocking {
        val created = service.create(goal())

        assertEquals("REQUIRED", fieldErrors { service.update(created.id, json("metric" to "ORDERS")) }["target"])
        assertEquals(GoalMetric.REVENUE, service.get(created.id).goal.metric)

        assertEquals("INVALID", fieldErrors { service.update(created.id, json("metric" to "BAD")) }["metric"])
    }

    @Test
    fun `update and delete of a missing goal are not found and delete removes the row`(): Unit = runBlocking {
        assertThrows(NotFound::class.java) { runBlocking { service.update(99999, json("name" to "x")) } }
        assertThrows(NotFound::class.java) { runBlocking { service.delete(99999) } }
        assertThrows(NotFound::class.java) { runBlocking { service.get(99999) } }

        val created = service.create(goal("name" to "Bye"))
        assertEquals("Bye", service.delete(created.id))
        assertEquals(0L, count("market_goal"))
    }
}
