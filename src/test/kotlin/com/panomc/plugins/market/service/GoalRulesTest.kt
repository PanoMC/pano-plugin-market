package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.model.GoalMetric
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The pure rules of MK-051: goal targets, comparison limits and the clone suffix. No database. */
class GoalRulesTest {
    @Test
    fun `revenue targets are exact x100 and refuse a third decimal place`() {
        assertEquals(10000L, GoalRules.parseTarget("100", GoalMetric.REVENUE))
        assertEquals(123450L, GoalRules.parseTarget("1234.5", GoalMetric.REVENUE))
        assertEquals(1L, GoalRules.parseTarget("0.01", GoalMetric.REVENUE))
        assertEquals(10000L, GoalRules.parseTarget(100, GoalMetric.REVENUE))
        assertEquals(1999L, GoalRules.parseTarget(19.99, GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget("0.001", GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget("0", GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget("-5", GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget("abc", GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget(null, GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget(Double.NaN, GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget("100000000001", GoalMetric.REVENUE))
        assertEquals(10_000_000_000_000L, GoalRules.parseTarget("100000000000", GoalMetric.REVENUE))
        assertNull(GoalRules.parseTarget("99999999999999999999999", GoalMetric.REVENUE))
    }

    @Test
    fun `count targets are whole numbers within the cap`() {
        assertEquals(5L, GoalRules.parseTarget("5", GoalMetric.ORDERS))
        assertEquals(5L, GoalRules.parseTarget(5, GoalMetric.PRODUCT_SALES))
        assertEquals(5L, GoalRules.parseTarget("5.0", GoalMetric.ORDERS))
        assertNull(GoalRules.parseTarget("5.5", GoalMetric.ORDERS))
        assertNull(GoalRules.parseTarget("0", GoalMetric.ORDERS))
        assertNull(GoalRules.parseTarget(1_000_000_001L, GoalMetric.ORDERS))
        assertEquals(1_000_000_000L, GoalRules.parseTarget(1_000_000_000L, GoalMetric.ORDERS))
    }

    @Test
    fun `comparison limits are 12 products and 50 features`() {
        fun data(products: Int, features: Int) = JsonObject()
            .put("name", "x")
            .put("selectedProducts", io.vertx.core.json.JsonArray((1..products).map { it.toLong() }))
            .put("features", io.vertx.core.json.JsonArray((1..features).map { JsonObject().put("id", "f$it") }))

        assertEquals(emptyMap<String, String>(), ComparisonRules.validate(data(12, 50)))
        assertEquals("TOO_MANY", ComparisonRules.validate(data(13, 0))["selectedProducts"])
        assertEquals("TOO_MANY", ComparisonRules.validate(data(0, 51))["features"])
        assertEquals(emptyMap<String, String>(), ComparisonRules.validate(JsonObject().put("name", "x")))
        assertEquals("REQUIRED", ComparisonRules.validate(JsonObject())["name"])
        assertEquals("TOO_LONG", ComparisonRules.validate(JsonObject().put("name", "x".repeat(256)))["name"])
        assertEquals(
            emptyMap<String, String>(),
            ComparisonRules.validate(JsonObject().put("name", "x").put("selectedProducts", io.vertx.core.json.JsonArray().addNull().add(3)))
        )
    }

    @Test
    fun `clone suffix follows the first known language of Accept-Language`() {
        assertEquals(" (Kopya)", CloneSuffix.of("tr-TR,tr;q=0.9,en;q=0.8"))
        assertEquals(" (Copy)", CloneSuffix.of("en-US"))
        assertEquals(" (Копия)", CloneSuffix.of("ru"))
        assertEquals(" (Copy)", CloneSuffix.of(null))
        assertEquals(" (Copy)", CloneSuffix.of("de-DE"))
        assertEquals(" (Kopya)", CloneSuffix.of("de-DE, tr;q=0.5"))
    }
}
