package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketGoalDaoImpl
import com.panomc.plugins.market.db.model.*
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_goal` (01 section 12). */
class MarketGoalDaoIT : MarketDaoITBase() {
    private val goals = MarketGoalDaoImpl()

    private fun goal(name: String = "Server fund", position: Int = 0, status: String = "ACTIVE") = MarketGoal(
        name = name, description = "d", metric = GoalMetric.PRODUCT_SALES, productIds = "[1,2]", target = 5000, progress = 10, currency = "EUR",
        period = GoalPeriod.MONTHLY, periodStart = 1, startsAt = 2, endsAt = 3, status = status, showOnStore = false, completedAt = 4, position = position,
        createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a goal round-trips every column`(): Unit = runBlocking {
        val id = goals.add(goal(), pool)
        val r = goals.getById(id, pool)!!
        assertEquals(listOf<Any?>("Server fund", "d", GoalMetric.PRODUCT_SALES, "[1,2]", 5000L, 10L, "EUR", GoalPeriod.MONTHLY, 1L, 2L, 3L, "ACTIVE", 4L, 0, 10L, 20L),
            listOf(r.name, r.description, r.metric, r.productIds, r.target, r.progress, r.currency, r.period, r.periodStart, r.startsAt, r.endsAt, r.status, r.completedAt, r.position, r.createdAt, r.updatedAt))
        assertFalse(r.showOnStore)
        assertNull(goals.getById(9999, pool))
    }

    @Test
    fun `a minimal goal gets the column defaults`(): Unit = runBlocking {
        sql("INSERT INTO `pano_market_goal` (`name`, `metric`, `target`, `createdAt`, `updatedAt`) VALUES ('g', 'ORDERS', 3, 1, 1)")
        val r = goals.getAll(pool).single()
        assertEquals(listOf<Any?>(0L, GoalPeriod.ONE_TIME, "ACTIVE", 0), listOf(r.progress, r.period, r.status, r.position))
        assertTrue(r.showOnStore)
        assertNull(r.completedAt)
    }

    @Test
    fun `lists are ordered by position and active filters on status`(): Unit = runBlocking {
        val c = goals.add(goal("c", position = 2), pool)
        val a = goals.add(goal("a", position = 1), pool)
        val b = goals.add(goal("b", position = 1, status = "ENDED"), pool)
        assertEquals(listOf(a, b, c), goals.getAll(pool).map { it.id })
        assertEquals(listOf(a, c), goals.getActive(pool).map { it.id })
    }

    @Test
    fun `progress is an atomic add that never goes below zero`(): Unit = runBlocking {
        val id = goals.add(MarketGoal(name = "p", target = 100000), pool)
        val results = Race.run(20) { goals.addProgress(id, 5, 1, pool) }
        assertTrue(results.all { it.getOrThrow() })
        assertEquals(100L, goals.getById(id, pool)!!.progress)
        assertTrue(goals.addProgress(id, -30, 2, pool))
        assertEquals(70L, goals.getById(id, pool)!!.progress)
        assertTrue(goals.addProgress(id, -1000, 3, pool))
        assertEquals(0L, goals.getById(id, pool)!!.progress)
        assertFalse(goals.addProgress(9999, 1, 4, pool))
    }

    @Test
    fun `completion is set once and a new period resets it`(): Unit = runBlocking {
        val id = goals.add(MarketGoal(name = "c", target = 10, progress = 10), pool)
        val wins = Race.run(4) { goals.markCompleted(id, 50, pool) }.count { it.getOrThrow() }
        assertEquals(1, wins)
        assertEquals(50L, goals.getById(id, pool)!!.completedAt)
        assertFalse(goals.markCompleted(id, 60, pool))
        assertEquals(50L, goals.getById(id, pool)!!.completedAt)
        assertTrue(goals.resetPeriod(id, 700, 70, pool))
        val r = goals.getById(id, pool)!!
        assertEquals(listOf<Any?>(0L, 700L, null), listOf(r.progress, r.periodStart, r.completedAt))
        assertTrue(goals.markCompleted(id, 80, pool))
    }

    @Test
    fun `update writes the configuration but not progress, and delete removes the row`(): Unit = runBlocking {
        val id = goals.add(MarketGoal(name = "old", target = 1, progress = 42, completedAt = 9), pool)
        assertTrue(goals.update(MarketGoal(id = id, name = "new", description = "x", metric = GoalMetric.ORDERS, target = 77, status = "ENDED", position = 3, showOnStore = false, updatedAt = 99, progress = 1000000), pool))
        val r = goals.getById(id, pool)!!
        assertEquals(listOf<Any?>("new", "x", GoalMetric.ORDERS, 77L, "ENDED", 3, 99L), listOf(r.name, r.description, r.metric, r.target, r.status, r.position, r.updatedAt))
        assertFalse(r.showOnStore)
        assertEquals(42L, r.progress)
        assertEquals(9L, r.completedAt)
        assertFalse(goals.update(MarketGoal(id = 9999, name = "x"), pool))
        assertTrue(goals.delete(id, pool))
        assertFalse(goals.delete(id, pool))
    }
}
