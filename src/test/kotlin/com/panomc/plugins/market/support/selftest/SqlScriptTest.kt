package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.support.SqlScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The fixture loader splits `.sql` files itself (the MySQL client sends one statement at a time). */
class SqlScriptTest {
    @Test
    fun `splits on semicolons and trims`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), SqlScript.split("SELECT 1;\n\n  SELECT 2;\n"))
        assertEquals(listOf("SELECT 1"), SqlScript.split("SELECT 1"))
        assertEquals(emptyList<String>(), SqlScript.split("  \n ; ;; \n"))
    }

    @Test
    fun `semicolons inside quotes and backticks stay inside`() {
        assertEquals(
            listOf("INSERT INTO t VALUES ('a;b', \"c;d\", `e;f`)", "SELECT 2"),
            SqlScript.split("INSERT INTO t VALUES ('a;b', \"c;d\", `e;f`); SELECT 2;")
        )
    }

    @Test
    fun `doubled quotes and backslash escapes do not end a literal`() {
        assertEquals(listOf("SELECT 'it''s; fine'"), SqlScript.split("SELECT 'it''s; fine';"))
        assertEquals(listOf("SELECT 'a\\'; b'"), SqlScript.split("SELECT 'a\\'; b';"))
        assertEquals(listOf("SELECT '\"; \"'", "SELECT 2"), SqlScript.split("SELECT '\"; \"'; SELECT 2;"))
    }

    @Test
    fun `comments are dropped and their semicolons ignored`() {
        val script = """
            -- header; with a semicolon
            SELECT 1; -- trailing; comment
            # hash; comment
            SELECT /* inline; block */ 2;
            /* block
               spanning; lines */
            SELECT '-- not a comment; really', '/* nor this */';
        """.trimIndent()
        val statements = SqlScript.split(script).map { it.replace(Regex("[ \\t]+"), " ") }
        assertEquals(
            listOf("SELECT 1", "SELECT 2", "SELECT '-- not a comment; really', '/* nor this */'"),
            statements
        )
    }

    @Test
    fun `a double dash without a blank is not a comment`() {
        assertEquals(listOf("SELECT 5--3"), SqlScript.split("SELECT 5--3;"))
    }

    @Test
    fun `the frozen fixtures split into the expected statements`() {
        fun load(name: String) = SqlScriptTest::class.java.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val schema = SqlScript.split(load("schema-v2.sql"))
        assertEquals(10, schema.size)
        assertEquals(true, schema.all { it.startsWith("CREATE TABLE IF NOT EXISTS `pano_market_") })
        val seed = SqlScript.split(load("seed-v2.sql"))
        assertEquals(9, seed.size, "one INSERT per seeded table (no comparison rows)")
        assertEquals(true, seed.all { it.startsWith("INSERT INTO `pano_market_") })
    }
}
