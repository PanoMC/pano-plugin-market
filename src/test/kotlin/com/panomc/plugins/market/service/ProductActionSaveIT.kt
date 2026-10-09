package com.panomc.plugins.market.service

import com.panomc.platform.error.NoPermission
import com.panomc.plugins.market.core.abuse.ActionGuard
import com.panomc.plugins.market.core.catalog.ProductInput
import com.panomc.plugins.market.core.catalog.ProductRequestParser
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.error.InvalidProduct
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.routes.panel.product.ProductActionRules
import com.panomc.plugins.market.service.platform.ServerRoster
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.support.ErrorBodies

/**
 * Action validation and `ActionGuard` inside the product save transaction (MK-104; 08 section 2.2, 11 section 14.4): the rules run against the stored row
 * that the save holds locked, a refused save leaves the product as it was, and a clone counts as new.
 */
class ProductActionSaveIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val cipher = SecretCipher(ByteArray(32) { (it * 3).toByte() })
    private val service by lazy {
        CatalogService(
            w.db, { w.config }, w.clock, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.providerMeta, w.categories, w.comparisons,
            ProductActionRules(ServerRoster { ServerRoster.Roster(listOf(1, 2, 3), listOf(1, 2, 3), emptyMap()) }, cipher)
        )
    }
    private var counter = 0

    override suspend fun assertInvariants() {}

    private class Caller(val admin: Boolean = false, val groups: Boolean = false, val consoles: Set<Long> = emptySet(), val global: Boolean = false) : ActionGuard.Caller {
        override suspend fun isAdmin() = admin

        override suspend fun canManagePermissionGroups() = groups

        override suspend fun canConsole(serverId: Long) = global || serverId in consoles

        override suspend fun canConsoleGlobally() = global
    }

    private fun input(vararg pairs: Pair<String, Any?>): ProductInput = ProductRequestParser.parse(JsonObject(mapOf(*pairs)))

    private fun named(vararg pairs: Pair<String, Any?>): ProductInput = input("name" to "Product ${++counter}", "price" to "10", *pairs)

    private val command = """[{"type":"COMMAND","value":["give {username} diamond"],"serverMode":"FIXED","targetServers":[1]}]"""

    private suspend fun storedActions(id: Long): JsonArray = JsonArray(w.products.getById(id, pool)!!.actions)

    @Test
    fun `a new command needs the console of its server and a refused create writes no product`(): Unit = runBlocking {
        assertThrows(NoPermission::class.java) { runBlocking { service.create(named("actions" to command), Caller()) } }
        assertThrows(NoPermission::class.java) { runBlocking { service.create(named("actions" to command), null) } }
        assertThrows(NoPermission::class.java) { runBlocking { service.create(named("actions" to command), Caller(consoles = setOf(2))) } }
        assertEquals(0, count("market_product"))

        val saved = service.create(named("actions" to command), Caller(consoles = setOf(1)))

        assertEquals(1, count("market_product"))

        val action = storedActions(saved.id).getJsonObject(0)

        assertEquals("a1", action.getString("id"))
        assertEquals("GRANT", action.getString("phase"))
        assertEquals(listOf(1), action.getJsonArray("targetServers").map { it as Int })
    }

    @Test
    fun `a catalogue editor can still edit a product whose actions someone else configured`(): Unit = runBlocking {
        val saved = service.create(named("actions" to command, "price" to "10"), Caller(admin = true))
        val before = w.products.getById(saved.id, pool)!!.actions

        // price, texts and stock are saved without a privilege: the submitted actions equal the stored ones
        service.update(saved.id, input("price" to "12", "actions" to before), Caller())
        assertEquals(1200L, w.products.getById(saved.id, pool)!!.price)
        assertEquals(before, w.products.getById(saved.id, pool)!!.actions)

        // a request without actions never touches them
        service.update(saved.id, input("price" to "13"), Caller())
        assertEquals(before, w.products.getById(saved.id, pool)!!.actions)

        // a changed command is refused and the stored one stays
        val changed = """[{"id":"a1","type":"COMMAND","value":["op {username}"],"serverMode":"FIXED","targetServers":[1]}]"""

        assertThrows(NoPermission::class.java) { runBlocking { service.update(saved.id, input("price" to "14", "actions" to changed), Caller(groups = true)) } }
        assertEquals(before, w.products.getById(saved.id, pool)!!.actions)
        assertEquals(1300L, w.products.getById(saved.id, pool)!!.price)

        service.update(saved.id, input("actions" to changed), Caller(consoles = setOf(1)))
        assertEquals("op {username}", storedActions(saved.id).getJsonObject(0).getJsonArray("value").getString(0))

        // removing every action needs no right
        service.update(saved.id, input("actions" to "[]"), Caller())
        assertEquals(0, storedActions(saved.id).size())
    }

    @Test
    fun `a permission action needs the permission group right and a Pano node needs the star`(): Unit = runBlocking {
        val permission = """[{"type":"PERMISSION","value":["group.vip"]}]"""
        val pano = """[{"type":"PERMISSION","value":["pano.manage.servers"]}]"""

        assertThrows(NoPermission::class.java) { runBlocking { service.create(named("actions" to permission), Caller(consoles = setOf(1), global = true)) } }
        assertThrows(NoPermission::class.java) { runBlocking { service.create(named("actions" to pano), Caller(groups = true)) } }
        assertEquals(0, count("market_product"))

        service.create(named("actions" to permission), Caller(groups = true))
        service.create(named("actions" to pano), Caller(admin = true))

        assertEquals(2, count("market_product"))
    }

    @Test
    fun `widening the server choices widens a buyer choice command and needs the console of the new server`(): Unit = runBlocking {
        val choice = """[{"type":"COMMAND","value":["say hi"],"serverMode":"BUYER_CHOICE"}]"""
        val saved = service.create(named("actions" to choice, "serverChoices" to listOf(1, 2)), Caller(consoles = setOf(1, 2)))

        // the action is unchanged, but server 3 is new for it
        assertThrows(NoPermission::class.java) { runBlocking { service.update(saved.id, input("actions" to w.products.getById(saved.id, pool)!!.actions, "serverChoices" to listOf(1, 2, 3)), Caller(consoles = setOf(1, 2))) } }
        assertEquals("[1,2]", w.products.getById(saved.id, pool)!!.serverChoices)

        service.update(saved.id, input("actions" to w.products.getById(saved.id, pool)!!.actions, "serverChoices" to listOf(1, 2, 3)), Caller(consoles = setOf(1, 2, 3)))
        assertEquals("[1,2,3]", w.products.getById(saved.id, pool)!!.serverChoices)
    }

    @Test
    fun `a partial update without actions that widens the server choices is checked against the stored actions`(): Unit = runBlocking {
        val choice = """[{"type":"COMMAND","value":["say hi"],"serverMode":"BUYER_CHOICE"}]"""
        val saved = service.create(named("actions" to choice, "serverChoices" to listOf(1, 2)), Caller(consoles = setOf(1, 2)))
        val before = w.products.getById(saved.id, pool)!!

        // only serverChoices is sent: a catalogue-only editor must not make the command run on server 3
        assertThrows(NoPermission::class.java) { runBlocking { service.update(saved.id, input("serverChoices" to listOf(1, 2, 3)), Caller(consoles = setOf(1, 2))) } }
        assertEquals("[1,2]", w.products.getById(saved.id, pool)!!.serverChoices)
        assertEquals(before.actions, w.products.getById(saved.id, pool)!!.actions)

        // a field that nothing depends on stays free, the same choices pass, and narrowing passes
        service.update(saved.id, input("price" to "15"), Caller())
        service.update(saved.id, input("serverChoices" to listOf(1)), Caller())
        assertEquals("[1]", w.products.getById(saved.id, pool)!!.serverChoices)

        // with the console of the new server the widening is allowed
        service.update(saved.id, input("serverChoices" to listOf(1, 3)), Caller(consoles = setOf(3)))
        assertEquals("[1,3]", w.products.getById(saved.id, pool)!!.serverChoices)

        // dropping every choice leaves a BUYER_CHOICE command without a choice: the strict rule refuses it
        val e = runCatching { service.update(saved.id, input("serverChoices" to emptyList<Int>()), Caller(admin = true)) }.exceptionOrNull()

        assertTrue(e is InvalidProduct)
        assertEquals("SERVER_CHOICES_REQUIRED", ErrorBodies.details((e as InvalidProduct)).getJsonObject("fieldErrors").getString("actions.0.serverMode"))
        assertEquals("[1,3]", w.products.getById(saved.id, pool)!!.serverChoices)
    }

    @Test
    fun `a bad action list is INVALID_PRODUCT with the dotted path and nothing is saved`(): Unit = runBlocking {
        val unknown = """[{"type":"COMMAND","value":["x"],"targetServers":[99]}]"""
        val e = runCatching { service.create(named("actions" to unknown), Caller(admin = true)) }.exceptionOrNull()

        assertTrue(e is InvalidProduct)
        assertEquals("UNKNOWN_SERVER", ErrorBodies.details((e as InvalidProduct)).getJsonObject("fieldErrors").getString("actions.0.targetServers"))
        assertEquals(0, count("market_product"))

        val perUnit = runCatching { service.create(named("actions" to """[{"type":"COMMAND","value":["x"],"targetServers":[1],"perUnit":true}]"""), Caller(admin = true)) }.exceptionOrNull()

        assertTrue(perUnit is InvalidProduct)
    }

    @Test
    fun `a webhook secret is created once, stored encrypted and never read back`(): Unit = runBlocking {
        val webhook = """[{"type":"WEBHOOK","value":{"url":"https://example.com/hook","signing":"HMAC_SHA256"}}]"""
        val saved = service.create(named("actions" to webhook), Caller())
        val secret = saved.generatedSecrets.getValue("a1")
        val stored = w.products.getById(saved.id, pool)!!.actions!!

        assertTrue(secret.startsWith("whsec_"))
        assertFalse(stored.contains(secret))
        assertEquals(secret, cipher.decrypt(JsonArray(stored).getJsonObject(0).getJsonObject("value").getString("secret")))

        val view = com.panomc.plugins.market.core.catalog.ProductActions.view(stored)

        assertEquals("********", view.getJsonObject(0).getJsonObject("value").getString("secret"))
        assertFalse(view.encode().contains("v1:"))

        // saving the masked form back keeps the secret and creates no other
        val again = service.update(saved.id, input("actions" to view.encode()), Caller())

        assertTrue(again.generatedSecrets.isEmpty())
        assertEquals(stored, w.products.getById(saved.id, pool)!!.actions)
    }

    @Test
    fun `a clone counts as new, so the cloning user needs the right for every action of the source`(): Unit = runBlocking {
        val source = service.create(named("actions" to command), Caller(admin = true))

        assertThrows(NoPermission::class.java) { runBlocking { service.clone(source.id, " (Copy)", Caller(groups = true)) } }
        assertThrows(NoPermission::class.java) { runBlocking { service.clone(source.id, " (Copy)") } }
        assertEquals(1, count("market_product"))

        val copy = service.clone(source.id, " (Copy)", Caller(consoles = setOf(1)))

        assertNotNull(copy.id)
        assertEquals(2, count("market_product"))

        // a product with a credit only clones for anyone
        val plain = service.create(named("actions" to """[{"type":"CREDIT","value":1}]"""), Caller())

        service.clone(plain.id, " (Copy)", Caller())
    }
}
