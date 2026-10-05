package com.panomc.plugins.market.mc.core.wire

import com.panomc.plugins.pano.core.platform.PlatformMessage
import com.panomc.plugins.pano.core.platform.PlatformMessage.Companion.responseName
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import com.panomc.plugins.pano.core.platform.PlatformRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * MC-U7: the JSON of every `mc.core.wire` class equals the canonical fixture, field name for field name and type for
 * type. The fixtures are the one document the Pano side is held to as well (market `test` task, property
 * `market.wireFixtures`).
 */
class WireParityTest {
    private val eventId = UUID.fromString("0b8c0f4e-6f0a-4a39-9d52-3c1d2a7e5b10")
    private val v = "1.4.0"
    private val steve = PlayerRef("Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5")

    /** The request instances behind the request fixtures: built with the real constructors, encoded by the real `encode()`. */
    private val requests: Map<String, PlatformRequest> = mapOf(
        "MarketSyncRequest.json" to MarketSyncRequest(
            v, 1, McPlatformName.PAPER, luckPerms = true, vault = false, placeholderApi = true, queued = 3, capacity = 20, configHash = "9f2c0a",
            results = listOf(
                SyncResult("812:a1:4:0:GRANT:0", ResultStatus.DONE, executedAt = 1790000000000, commands = listOf(CommandResult(0, true))),
                SyncResult(
                    "813:a1:4:0:GRANT:0", ResultStatus.FAILED, ResultCode.COMMAND_ERROR, "1 of 2 commands failed", 1790000001000,
                    listOf(CommandResult(0, true), CommandResult(1, false, "Unknown command"))
                ),
                SyncResult("814:a1:4:0:GRANT:0", ResultStatus.QUEUED)
            ),
            eventId = eventId
        ),
        "MarketSyncRequest.first.json" to MarketSyncRequest(
            "local-build", 1, McPlatformName.VELOCITY, luckPerms = false, vault = false, placeholderApi = false, queued = 0, capacity = 20,
            configHash = null, results = emptyList(), eventId = eventId
        ),
        "MarketConfigRequest.json" to MarketConfigRequest(v, 1, "9f2c0a", eventId),
        "MarketConfigRequest.first.json" to MarketConfigRequest(v, 1, null, eventId),
        "MarketQueryRequest.balance.json" to MarketQueryRequest(v, 1, QueryType.BALANCE, steve, null, null, eventId),
        "MarketQueryRequest.catalog.json" to MarketQueryRequest(v, 1, QueryType.CATALOG, steve, 2, null, eventId),
        "MarketQueryRequest.placeholders.json" to MarketQueryRequest(v, 1, QueryType.PLACEHOLDERS, null, null, QueryArgs(listOf("Steve", "Alex")), eventId),
        "MarketPurchaseRequest.json" to MarketPurchaseRequest(v, 1, "4d2a9c1e-0000-4000-8000-000000000001", steve, 10, 2, 3, eventId),
        "MarketPurchaseRequest.plain.json" to MarketPurchaseRequest(v, 1, "4d2a9c1e-0000-4000-8000-000000000002", PlayerRef("Alex"), 11, 1, null, eventId),
        "MarketAdminRequest.give.json" to MarketAdminRequest(
            v, 1, "4d2a9c1e-0000-4000-8000-000000000003", AdminOp.GIVE_CREDITS,
            AdminActor(false, "Admin", "11111111-2222-3333-4444-555555555555"), AdminTarget("Steve"), 10.5, null, null, "contest prize", eventId
        ),
        "MarketAdminRequest.grant.json" to MarketAdminRequest(
            v, 1, "4d2a9c1e-0000-4000-8000-000000000004", AdminOp.GRANT_PRODUCT, AdminActor(true), AdminTarget("Steve"), null, 10, 3, null, eventId
        ),
        "MarketEconomyRequest.deposit.json" to MarketEconomyRequest(v, 1, "4d2a9c1e-0000-4000-8000-000000000005", EconomyOp.DEPOSIT, steve, 5.0, "vault deposit", eventId),
        "MarketEconomyRequest.balance.json" to MarketEconomyRequest(v, 1, "4d2a9c1e-0000-4000-8000-000000000006", EconomyOp.BALANCE, steve, null, null, eventId)
    )

    private val fixtures = WireFixtures.files()

    private fun wireTopLevelClasses(): List<Class<*>> {
        val root = File(MarketWire::class.java.protectionDomain.codeSource.location.toURI())
        val dir = File(root, WireFixtures.PACKAGE.replace('.', '/'))
        return requireNotNull(dir.listFiles { f -> f.name.endsWith(".class") && !f.name.contains('$') }) { "wire classes not found in $dir" }
            .map { WireFixtures.classOf(it.name.removeSuffix(".class")) }
            .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) && !it.name.endsWith("Kt") }
    }

    private fun isRequest(c: Class<*>) = PlatformRequest::class.java.isAssignableFrom(c)
    private fun isResponse(c: Class<*>) = PlatformMessageResponse::class.java.isAssignableFrom(c)

    @Test
    fun `every request and response class has fixtures and every fixture belongs to a class`() {
        val protocolClasses = wireTopLevelClasses().filter { isRequest(it) || isResponse(it) }.map { it.simpleName }.toSortedSet()
        assertEquals(
            setOf(
                "MarketSyncRequest", "MarketConfigRequest", "MarketQueryRequest", "MarketPurchaseRequest", "MarketAdminRequest", "MarketEconomyRequest",
                "MarketSyncMessage", "MarketConfigMessage", "MarketQueryMessage", "MarketPurchaseMessage", "MarketAdminMessage", "MarketEconomyMessage"
            ),
            protocolClasses,
            "the six events of 19 section 7 (request + response each)"
        )
        val fixtureClasses = fixtures.map { WireFixtures.classNameOf(it) }.toSet()
        assertEquals(protocolClasses.toSet(), fixtureClasses, "a class without a fixture or a fixture without a class")
    }

    @Test
    fun `event names are the class names the platform derives`() {
        val expected = mapOf("Sync" to "MARKET_SYNC", "Config" to "MARKET_CONFIG", "Query" to "MARKET_QUERY", "Purchase" to "MARKET_PURCHASE", "Admin" to "MARKET_ADMIN", "Economy" to "MARKET_ECONOMY")
        expected.forEach { (suffix, event) ->
            val request = WireFixtures.classOf("Market${suffix}Request")
            val message = WireFixtures.classOf("Market${suffix}Message")
            @Suppress("UNCHECKED_CAST")
            assertEquals(event, (message as Class<out PlatformMessage>).responseName(), "response event of ${message.simpleName}")
            // the request side: encode() of the matching fixture request
            val sample = requests.entries.first { it.value.javaClass == request }.value
            assertEquals(event, com.google.gson.JsonParser.parseString(sample.encode()).asJsonObject.get("event").asString)
        }
        assertEquals("MARKET_SYNC", MarketWire.EVENT_SYNC)
        assertEquals(1, MarketWire.PROTOCOL)
    }

    @Test
    fun `every request fixture has a request instance and the encoded request equals it`() {
        val requestFixtures = fixtures.filter { isRequest(WireFixtures.classOf(WireFixtures.classNameOf(it))) }
        assertEquals(requestFixtures.map { it.name }.toSet(), requests.keys, "request fixtures and request instances differ")
        requestFixtures.forEach { f ->
            val encoded = com.google.gson.JsonParser.parseString(requests.getValue(f.name).encode())
            assertEquals(WireFixtures.stripNulls(WireFixtures.load(f)), WireFixtures.stripNulls(encoded), f.name)
        }
    }

    @Test
    fun `every response fixture decodes into its class and encodes back to the same JSON`() {
        val responseFixtures = fixtures.filter { isResponse(WireFixtures.classOf(WireFixtures.classNameOf(it))) }
        assertTrue(responseFixtures.size >= 6)
        responseFixtures.forEach { f ->
            val c = WireFixtures.classOf(WireFixtures.classNameOf(f))
            val decoded = WireFixtures.gson.fromJson(f.readText(Charsets.UTF_8), c)
            val back = com.google.gson.JsonParser.parseString(WireFixtures.gson.toJson(decoded))
            assertEquals(WireFixtures.stripNulls(WireFixtures.load(f)), WireFixtures.stripNulls(back), f.name)
        }
    }

    @Test
    fun `fixtures use only declared fields and together cover every field of every class`() {
        val byClass = fixtures.groupBy { WireFixtures.classNameOf(it) }
        byClass.forEach { (name, files) ->
            val c = WireFixtures.classOf(name)
            val unknown = mutableListOf<String>()
            val seen = linkedSetOf<String>()
            files.forEach { seen += WireFixtures.fixturePaths(c, WireFixtures.load(it), unknown) }
            assertTrue(unknown.isEmpty(), "$name fixtures hold keys the class does not declare: $unknown")
            val declared = WireFixtures.classPaths(c)
            assertEquals(declared.toSortedSet(), seen.toSortedSet(), "$name: fields never exercised by a fixture (or fixture paths not declared)")
        }
    }

    @Test
    fun `every response class and nested data class decodes from an empty object with its defaults`() {
        val msg = WireFixtures.gson.fromJson("{}", MarketSyncMessage::class.java)
        assertFalse(msg.accepted)
        assertEquals(5000L, msg.pollAfterMs)
        assertTrue(msg.deliveries.isEmpty() && msg.acked.isEmpty() && msg.cancel.isEmpty() && msg.broadcasts.isEmpty())
        // Gson builds a class without a no-arg constructor through Unsafe and leaves absent keys null: every data
        // class in the package must keep a default for every parameter.
        val root = File(File(MarketWire::class.java.protectionDomain.codeSource.location.toURI()), WireFixtures.PACKAGE.replace('.', '/'))
        root.listFiles { f -> f.name.endsWith(".class") }!!.map { WireFixtures.classOf(it.name.removeSuffix(".class")) }
            .filter { !isRequest(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) && it.declaredFields.any { f -> !Modifier.isStatic(f.modifiers) } }
            .filter { it.isAnnotationPresent(Metadata::class.java) && it.simpleName.let { n -> n != "MarketWire" && !n.endsWith("Kt") } }
            .filter { c -> c.declaredConstructors.any { it.parameterCount > 0 } }
            .forEach { c ->
                assertTrue(c.declaredConstructors.any { it.parameterCount == 0 }, "${c.name} has no no-arg constructor (a parameter lacks a default)")
            }
    }
}
