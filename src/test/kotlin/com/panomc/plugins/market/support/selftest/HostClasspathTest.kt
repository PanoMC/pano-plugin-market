package com.panomc.plugins.market.support.selftest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Proves the test classpath of T1/T2/T4 (17 section 3.1, T-INFRA items a and d): the host classes the later tiers
 * need are loadable, once through `project(":Pano")` (embedded) and once through the release fat jar (standalone).
 */
class HostClasspathTest {
    private val needed = listOf(
        "com.panomc.platform.db.Dao",
        "com.panomc.platform.db.DBEntity",
        "com.panomc.platform.api.PanoPlugin",
        "io.vertx.core.Vertx",
        "io.vertx.core.json.JsonObject",
        "io.vertx.sqlclient.Pool",
        "io.vertx.mysqlclient.MySQLBuilder",
        "io.vertx.ext.web.Router",
        "io.vertx.ext.web.client.WebClient",
        "io.vertx.kotlin.coroutines.VertxCoroutineKt",
        "kotlinx.coroutines.CoroutineScope",
        "org.springframework.context.annotation.AnnotationConfigApplicationContext",
        "com.google.gson.Gson",
        "org.pf4j.Plugin",
        "kotlin.Unit",
    )

    @Test
    fun `host classes needed by T1 T2 and T4 are on the test classpath`() {
        val missing = needed.filter {
            try {
                Class.forName(it, false, javaClass.classLoader)
                false
            } catch (e: Throwable) {
                true
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `every copy of vertx core on the classpath is byte identical`() {
        // Embedded builds see Vert.x twice (the Pano jar and the explicit test dependency, same version); that is
        // harmless only while the copies are identical. Standalone builds see exactly one copy.
        val copies = javaClass.classLoader.getResources("io/vertx/core/Vertx.class").toList()
            .map { it.openStream().use { s -> s.readBytes() }.contentHashCode() }
            .toSet()
        assertEquals(1, copies.size, "different Vert.x versions break linkage")
    }
}
