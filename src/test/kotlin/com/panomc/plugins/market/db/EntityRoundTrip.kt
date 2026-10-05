package com.panomc.plugins.market.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import java.math.BigDecimal

/**
 * Reflection check shared by the order DAO tests: every declared field of an entity, `id` aside, is compared between
 * the entity that was written and the one that was read back, so a column the DAO forgets to insert or to select
 * fails the test by name. [differsFromDefaults] makes the written entity prove it sets every field away from the
 * constructor default (otherwise "unchanged" could mean "never written").
 */
internal object EntityRoundTrip {
    private fun fields(type: Class<*>) = type.declaredFields.filter { !it.isSynthetic && it.name != "Companion" }.onEach { it.isAccessible = true }

    fun assertSame(expected: Any, actual: Any) {
        for (field in fields(expected.javaClass)) {
            if (field.name == "id") continue
            val e = field.get(expected)
            val a = field.get(actual)
            if (e is BigDecimal && a is BigDecimal) {
                assertEquals(0, e.compareTo(a), "${field.name}: expected $e, read $a")
            } else {
                assertEquals(e, a, field.name)
            }
        }
    }

    fun differsFromDefaults(written: Any, defaults: Any) {
        for (field in fields(written.javaClass)) {
            if (field.name == "id" || field.name == "createdAt" || field.name == "updatedAt") continue
            assertNotEquals(field.get(defaults), field.get(written), "the test must set ${field.name} away from its default")
        }
    }
}
