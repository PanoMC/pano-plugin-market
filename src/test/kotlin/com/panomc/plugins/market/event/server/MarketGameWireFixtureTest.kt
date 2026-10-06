package com.panomc.plugins.market.event.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventRequest
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MC-U7, Pano side, for the five game events (19 section 13; the blocking item MC-01 left to MC-04): every `Market<X>Request*.json` and `Market<X>Message*.json` of
 * the shared wire directory (system property `market.wireFixtures`, set by the `test` task; the Minecraft-side `WireParityTest` reads the same files) is decoded into
 * its Pano-side class the way the platform decodes a frame (`Gson().fromJson(text, requestClass)`, `ServerManager.onServerWrite`), re-encoded with the platform
 * serializer (`JsonObject.mapFrom` for a request, `PlatformMessage.encode` for a response) and compared with the fixture, nulls stripped. The naming rule is the
 * platform's: `<X>Request` -> `<X>EventRequest`, `<X>Message` -> `<X>EventResponse`; a fixture whose class does not exist fails (this covers `MarketSync` too, so
 * no fixture can be added without its Pano twin), and the event name the event class derives must equal the `event` of its request fixtures.
 *
 * Because a Pano-side field that no fixture carries would escape the parity check of the other end, the second half proves the reverse: every property of every
 * Pano request and response class (and of the nested classes) appears in at least one fixture. Field-name and type choices of MC-01 (`permission.nodes` is not
 * a field of any game event, `MarketQueryData` is flattened, money is a decimal number, `commands: []` is MARKET_SYNC's) are confirmed by this test: nothing was
 * renamed on either end.
 */
class MarketGameWireFixtureTest {
    private val gson = Gson()

    private val dir: File = File(requireNotNull(System.getProperty("market.wireFixtures")) { "system property market.wireFixtures is not set by the test task" })

    private val events = listOf("MarketConfig", "MarketQuery", "MarketPurchase", "MarketAdmin", "MarketEconomy")

    private fun fixtures(prefix: String): List<File> =
        requireNotNull(dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name.startsWith(prefix) }) { "no wire fixtures in $dir" }.sortedBy { it.name }

    private fun allFixtures(): List<File> = requireNotNull(dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }).sortedBy { it.name }

    private fun classNameOf(file: File) = file.name.removeSuffix(".json").substringBefore('.')

    private fun panoClass(file: File): Class<*> {
        val wire = classNameOf(file)
        val name = when {
            wire.endsWith("Request") -> "${wire.removeSuffix("Request")}EventRequest"
            wire.endsWith("Message") -> "${wire.removeSuffix("Message")}EventResponse"
            else -> fail<String>("fixture ${file.name} is neither a request nor a message")
        }

        return try {
            Class.forName("com.panomc.plugins.market.event.server.$name")
        } catch (e: ClassNotFoundException) {
            fail("fixture ${file.name} has no Pano-side class (expected com.panomc.plugins.market.event.server.$name)")
        }
    }

    private fun stripNulls(e: JsonElement): JsonElement = when {
        e.isJsonObject -> JsonObject().also { out -> e.asJsonObject.entrySet().forEach { (k, v) -> if (!v.isJsonNull) out.add(k, stripNulls(v)) } }
        e.isJsonArray -> JsonArray().also { out -> e.asJsonArray.forEach { out.add(if (it.isJsonNull) JsonNull.INSTANCE else stripNulls(it)) } }
        else -> e
    }

    private fun json(file: File): JsonObject = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject

    @Test
    fun `every fixture of the directory maps to a Pano-side class, MarketSync included`() {
        val files = allFixtures()

        assertTrue(files.size >= 30, "the shared directory holds the fixtures of all six events: ${files.map { it.name }}")

        for (file in files) assertNotNull(panoClass(file), file.name)
    }

    @Test
    fun `every request and message fixture of the five game events is present`() {
        for (event in events) {
            assertTrue(fixtures("${event}Request").isNotEmpty(), "$event request fixtures")
            assertTrue(fixtures("${event}Message").size >= 2, "$event message fixtures (an answer and a refusal at least): ${fixtures("${event}Message").map { it.name }}")
        }
    }

    @Test
    fun `every request fixture of the five events decodes into its class and the platform serializer encodes it back to the same JSON`() {
        for (event in events) {
            for (file in fixtures("${event}Request")) {
                val cls = panoClass(file)

                assertEquals("com.panomc.plugins.market.event.server.${event}EventRequest", cls.name, file.name)

                val text = file.readText(Charsets.UTF_8)
                val decoded = gson.fromJson(text, cls)
                val encoded = JsonParser.parseString(io.vertx.core.json.JsonObject.mapFrom(decoded).encode())

                assertEquals(stripNulls(JsonParser.parseString(text)), stripNulls(encoded), file.name)

                val request = decoded as ServerEventRequest

                assertNotNull(request.eventId, "${file.name}: the platform answers with the eventId of the request")
                assertEquals(event.uppercaseSnake(), json(file).get("event").asString, file.name)
                assertEquals(1, json(file).get("protocol").asInt, file.name)
            }
        }
    }

    @Test
    fun `every message fixture of the five events decodes into its response class and the platform encodes it back to the same JSON`() {
        for (event in events) {
            for (file in fixtures("${event}Message")) {
                val cls = panoClass(file)

                assertEquals("com.panomc.plugins.market.event.server.${event}EventResponse", cls.name, file.name)

                val text = file.readText(Charsets.UTF_8)
                val decoded = gson.fromJson(text, cls) as ServerEventResponse
                val encoded = JsonParser.parseString(decoded.encode()).asJsonObject

                // the platform's own framing: the wire name, and the `responseName` Jackson reads off the getter (the component ignores it)
                assertEquals(event.uppercaseSnake(), encoded.remove("event").asString, file.name)
                assertEquals(event.uppercaseSnake(), encoded.remove("responseName").asString, file.name)

                assertEquals(stripNulls(JsonParser.parseString(text)), stripNulls(encoded), file.name)
            }
        }
    }

    @Test
    fun `the event name every adapter derives from its class is the event of its fixtures and each carries its request class`() {
        val service = { error("the service is not built while the gate is closed") }
        val adapters = mapOf<String, ServerEvent<*, *>>(
            "MarketConfig" to MarketConfigEvent(service, { false }), "MarketQuery" to MarketQueryEvent(service, { false }),
            "MarketPurchase" to MarketPurchaseEvent(service, { false }), "MarketAdmin" to MarketAdminEvent(service, { false }),
            "MarketEconomy" to MarketEconomyEvent(service, { false })
        )

        for ((event, adapter) in adapters) {
            assertEquals(event.uppercaseSnake(), adapter.getEventName(), event)
            assertEquals(Class.forName("com.panomc.plugins.market.event.server.${event}EventRequest"), adapter.requestClass, event)

            for (file in fixtures("${event}Request")) assertEquals(adapter.getEventName(), json(file).get("event").asString, file.name)
        }
    }

    @Test
    fun `while the runtime gate is closed every adapter answers MARKET_NOT_READY without looking the service up`() = runBlocking {
        val service = { error("the service must not be looked up") }
        val server = Server(
            id = 7, name = "s", motd = "", host = "127.0.0.1", port = 25565, playerCount = 0, maxPlayerCount = 0, type = ServerType.PAPER, version = "1.21",
            favicon = "", status = ServerStatus.ONLINE, startTime = 0, aesKey = ""
        )

        val config = MarketConfigEvent(service, { false }).handle(MarketConfigEventRequest(componentVersion = "1.0.0", protocol = 1), server)
        val query = MarketQueryEvent(service, { false }).handle(MarketQueryEventRequest(componentVersion = "1.0.0", protocol = 1, type = "BALANCE"), server)
        val purchase = MarketPurchaseEvent(service, { false }).handle(MarketPurchaseEventRequest(componentVersion = "1.0.0", protocol = 1), server)
        val admin = MarketAdminEvent(service, { false }).handle(MarketAdminEventRequest(componentVersion = "1.0.0", protocol = 1), server)
        val economy = MarketEconomyEvent(service, { false }).handle(MarketEconomyEventRequest(componentVersion = "1.0.0", protocol = 1), server)

        assertFalse(config.accepted)
        assertFalse(query.accepted)
        assertFalse(purchase.accepted)
        assertFalse(admin.accepted)
        assertFalse(economy.accepted)
        assertEquals(listOf("MARKET_NOT_READY"), listOf(config.reason, query.reason, purchase.reason, admin.reason, economy.reason).distinct())
    }

    // ===== the reverse: no Pano-side field without a fixture ===========================================================================

    private val framing = setOf("eventId", "responseName", "event")

    /** The property names of [cls] as the platform serializer writes them (a default instance, nulls included). */
    private fun properties(cls: Class<*>): Set<String> {
        val instance = cls.getDeclaredConstructor().newInstance()

        return io.vertx.core.json.JsonObject.mapFrom(instance).fieldNames() - framing
    }

    /** The objects at [path] (names of object keys, arrays flattened) of every fixture of [prefix]. */
    private fun objectsAt(prefix: String, vararg path: String): List<JsonObject> {
        var current: List<JsonElement> = fixtures(prefix).map { json(it) }

        for (name in path) {
            current = current.flatMap { e ->
                val value = if (e.isJsonObject) e.asJsonObject.get(name) else null

                when {
                    value == null || value.isJsonNull -> emptyList()
                    value.isJsonArray -> value.asJsonArray.toList()
                    else -> listOf(value)
                }
            }
        }

        return current.filter { it.isJsonObject }.map { it.asJsonObject }
    }

    private fun assertCovered(cls: Class<*>, objects: List<JsonObject>, what: String) {
        val seen = objects.flatMap { it.keySet() }.toSet()
        val missing = properties(cls) - seen

        assertTrue(objects.isNotEmpty(), "$what: no fixture carries this object")
        assertTrue(missing.isEmpty(), "$what: Pano-side properties no fixture carries (the Minecraft side cannot know them): $missing")
        assertTrue((seen - framing - properties(cls)).isEmpty(), "$what: fixture keys the Pano class does not have: ${seen - framing - properties(cls)}")
    }

    @Test
    fun `every property of every Pano request class is carried by a fixture`() {
        assertCovered(MarketConfigEventRequest::class.java, objectsAt("MarketConfigRequest"), "MarketConfigEventRequest")
        assertCovered(MarketQueryEventRequest::class.java, objectsAt("MarketQueryRequest"), "MarketQueryEventRequest")
        assertCovered(GamePlayer::class.java, objectsAt("MarketQueryRequest", "player"), "GamePlayer")
        assertCovered(GameQueryArgs::class.java, objectsAt("MarketQueryRequest", "args"), "GameQueryArgs")
        assertCovered(MarketPurchaseEventRequest::class.java, objectsAt("MarketPurchaseRequest"), "MarketPurchaseEventRequest")
        assertCovered(MarketAdminEventRequest::class.java, objectsAt("MarketAdminRequest"), "MarketAdminEventRequest")
        assertCovered(GameActor::class.java, objectsAt("MarketAdminRequest", "actor"), "GameActor")
        assertCovered(GameTarget::class.java, objectsAt("MarketAdminRequest", "target"), "GameTarget")
        assertCovered(MarketEconomyEventRequest::class.java, objectsAt("MarketEconomyRequest"), "MarketEconomyEventRequest")
    }

    @Test
    fun `every property of every Pano response class is carried by a fixture`() {
        assertCovered(MarketConfigEventResponse::class.java, objectsAt("MarketConfigMessage"), "MarketConfigEventResponse")
        assertCovered(McSettingsView::class.java, objectsAt("MarketConfigMessage", "settings"), "McSettingsView")
        assertCovered(MarketQueryEventResponse::class.java, objectsAt("MarketQueryMessage"), "MarketQueryEventResponse")
        assertCovered(MarketQueryData::class.java, objectsAt("MarketQueryMessage", "data"), "MarketQueryData")
        assertCovered(GameOrder::class.java, objectsAt("MarketQueryMessage", "data", "orders"), "GameOrder")
        assertCovered(GameCategory::class.java, objectsAt("MarketQueryMessage", "data", "categories"), "GameCategory")
        assertCovered(GameProduct::class.java, objectsAt("MarketQueryMessage", "data", "products"), "GameProduct")
        assertCovered(GamePurchasable::class.java, objectsAt("MarketQueryMessage", "data", "products", "purchasable"), "GamePurchasable")
        assertCovered(GameGift::class.java, objectsAt("MarketQueryMessage", "data", "gifts"), "GameGift")
        assertCovered(GamePlayerBalance::class.java, objectsAt("MarketQueryMessage", "data", "players", "Steve"), "GamePlayerBalance")
        assertCovered(MarketPurchaseEventResponse::class.java, objectsAt("MarketPurchaseMessage"), "MarketPurchaseEventResponse")
        assertCovered(MarketAdminEventResponse::class.java, objectsAt("MarketAdminMessage"), "MarketAdminEventResponse")
        assertCovered(GameOrder::class.java, objectsAt("MarketAdminMessage", "orders"), "GameOrder (admin purchases)")
        assertCovered(MarketEconomyEventResponse::class.java, objectsAt("MarketEconomyMessage"), "MarketEconomyEventResponse")
    }

    private fun String.uppercaseSnake(): String = replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
}
