package com.panomc.plugins.market.event.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.plugins.market.service.McSyncService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MC-U7, Pano side (19 section 13, `MK-103` "Blocking"): every `MarketSyncRequest*.json` and `MarketSyncMessage*.json` of the shared wire directory
 * (system property `market.wireFixtures`, set by the `test` task; the Minecraft-side `WireParityTest` reads the same files) is decoded into the Pano-side class
 * the way the platform decodes a frame (`Gson().fromJson(text, requestClass)`, `ServerManager.onServerWrite`), re-encoded with the platform serializer
 * (`JsonObject.mapFrom` for a request, `PlatformMessage.encode` for a response) and compared with the fixture, nulls stripped (as `WireFixtures.stripNulls`).
 * A fixture whose class has no Pano-side counterpart fails the test, so a new fixture cannot be added without its Pano twin.
 *
 * Fixture class names map by the naming rule of the platform: `MarketSyncRequest` -> `MarketSyncEventRequest`, `MarketSyncMessage` -> `MarketSyncEventResponse`;
 * the event name derived from the class `MarketSyncEvent` must equal the `event` the fixtures carry.
 */
class MarketSyncWireFixtureTest {
    private val gson = Gson()

    private val dir: File = File(requireNotNull(System.getProperty("market.wireFixtures")) { "system property market.wireFixtures is not set by the test task" })

    private fun fixtures(prefix: String): List<File> =
        requireNotNull(dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name.startsWith(prefix) }) { "no wire fixtures in $dir" }.sortedBy { it.name }

    private fun classNameOf(file: File) = file.name.removeSuffix(".json").substringBefore('.')

    /** The Pano-side class of a fixture, by the naming rule; a missing class is a failure with the fixture named. */
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

    @Test
    fun `the directory holds MarketSync request and message fixtures`() {
        assertTrue(fixtures("MarketSyncRequest").size >= 2, "request fixtures: ${fixtures("MarketSyncRequest").map { it.name }}")
        assertTrue(fixtures("MarketSyncMessage").size >= 2, "message fixtures: ${fixtures("MarketSyncMessage").map { it.name }}")
    }

    @Test
    fun `every MarketSyncRequest fixture decodes into MarketSyncEventRequest and re-encodes to the same JSON`() {
        val files = fixtures("MarketSyncRequest")

        assertTrue(files.isNotEmpty())

        for (file in files) {
            val cls = panoClass(file)

            assertEquals(MarketSyncEventRequest::class.java, cls, file.name)

            val text = file.readText(Charsets.UTF_8)
            val decoded = gson.fromJson(text, cls)
            val encoded = JsonParser.parseString(io.vertx.core.json.JsonObject.mapFrom(decoded).encode())

            assertEquals(stripNulls(JsonParser.parseString(text)), stripNulls(encoded), file.name)

            // what the handler reads
            val request = decoded as MarketSyncEventRequest

            assertNotNull(request.eventId, "${file.name}: the platform answers with the eventId of the request")
            assertEquals("MARKET_SYNC", request.event, file.name)
            assertEquals(1, request.protocol, file.name)
        }
    }

    @Test
    fun `every MarketSyncMessage fixture decodes into MarketSyncEventResponse and the platform encodes it back to the same JSON`() {
        val files = fixtures("MarketSyncMessage")

        assertTrue(files.isNotEmpty())

        for (file in files) {
            val cls = panoClass(file)

            assertEquals(MarketSyncEventResponse::class.java, cls, file.name)

            val text = file.readText(Charsets.UTF_8)
            val decoded = gson.fromJson(text, cls) as MarketSyncEventResponse
            val encoded = JsonParser.parseString(decoded.encode()).asJsonObject

            // the platform's own framing: the wire name, and the `responseName` Jackson reads off the getter (the component ignores it)
            assertEquals("MARKET_SYNC", encoded.remove("event").asString, file.name)
            assertEquals("MARKET_SYNC", encoded.remove("responseName").asString, file.name)

            assertEquals(stripNulls(JsonParser.parseString(text)), stripNulls(encoded), file.name)
        }
    }

    @Test
    fun `the full response fixture carries every field of the Pano class and the request fixtures cover the request class`() {
        val full = JsonParser.parseString(File(dir, "MarketSyncMessage.json").readText(Charsets.UTF_8)).asJsonObject

        assertEquals(
            setOf("accepted", "marketVersion", "acked", "deliveries", "cancel", "broadcasts", "configHash", "pollAfterMs"),
            full.keySet(),
            "the main response fixture exercises every non-null field"
        )

        val deliveryKeys = full.getAsJsonArray("deliveries").flatMap { it.asJsonObject.keySet() }.toSet()

        assertTrue(deliveryKeys.containsAll(setOf("key", "id", "kind", "phase", "player", "requiresOnline", "expiresAt", "issuer", "commands", "permission", "display")), "$deliveryKeys")

        val refusal = JsonParser.parseString(File(dir, "MarketSyncMessage.refused.json").readText(Charsets.UTF_8)).asJsonObject

        assertFalse(refusal.get("accepted").asBoolean)
        assertEquals("VERSION_MISMATCH", refusal.get("reason").asString)
    }

    @Test
    fun `the event name derived from the class is MARKET_SYNC and its request class is MarketSyncEventRequest`() {
        val event = MarketSyncEvent({ error("the service is not built while the gate is closed") }, { false })

        assertEquals("MARKET_SYNC", event.getEventName())
        assertEquals(MarketSyncEventRequest::class.java, event.requestClass)
        assertEquals(MarketSyncEvent::class.java.simpleName.replace("Event", ""), "MarketSync")
        assertTrue(ServerEvent::class.java.isAssignableFrom(MarketSyncEvent::class.java))

        for (file in fixtures("MarketSyncRequest")) {
            assertEquals(event.getEventName(), JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject.get("event").asString, file.name)
        }
    }

    @Test
    fun `while the runtime gate is closed the event answers MARKET_NOT_READY without looking the service up`() = runBlocking {
        val event = MarketSyncEvent({ error("the service must not be looked up") }, { false })
        val server = Server(
            id = 7, name = "s", motd = "", host = "127.0.0.1", port = 25565, playerCount = 0, maxPlayerCount = 0, type = ServerType.PAPER, version = "1.21",
            favicon = "", status = ServerStatus.ONLINE, startTime = 0, aesKey = ""
        )

        val response = event.handle(MarketSyncEventRequest(componentVersion = "1.0.0", protocol = 1), server)

        assertFalse(response.accepted)
        assertEquals("MARKET_NOT_READY", response.reason)
        assertTrue(response.deliveries.isEmpty())
        assertEquals(5000L, response.pollAfterMs)
    }

    @Test
    fun `a refusal names the reason, carries the applied keys and offers nothing`() {
        val refused = McSyncService.refusal(McSyncService.REASON_VERSION, "1.4.0", listOf("812:a1:4:0:GRANT:0"))

        assertFalse(refused.accepted)
        assertEquals("VERSION_MISMATCH", refused.reason)
        assertEquals("1.4.0", refused.marketVersion)
        assertEquals(listOf("812:a1:4:0:GRANT:0"), refused.acked)
        assertTrue(refused.deliveries.isEmpty() && refused.cancel.isEmpty() && refused.broadcasts.isEmpty())
        assertEquals(setOf("MARKET_NOT_READY", "PROTOCOL_UNSUPPORTED", "VERSION_MISMATCH"), setOf(McSyncService.REASON_NOT_READY, McSyncService.REASON_PROTOCOL, McSyncService.REASON_VERSION))
    }

    // ===== the announcement text (19 section 9) ==================================================================================

    @Test
    fun `an announcement turns template colour codes into section signs and substitutes the four variables`() {
        val text = McSyncService.renderAnnouncement("&a{player} &7bought &f{product} x{quantity} &8@ {store}", "Steve", "Diamonds", 3, "Shop")

        assertEquals("§aSteve §7bought §fDiamonds x3 §8@ Shop", text)
    }

    @Test
    fun `what a player or a product name contains is never interpreted as a colour code or a control character`() {
        // a username is letters, digits and underscore only; a product name loses control and colour characters and its own & codes stay literal text
        val text = McSyncService.renderAnnouncement("{player} bought {product}", "St§eve&!\n", "&4Dia§cmo\r\nnds\u0007", 1, "")

        assertEquals("Steve bought &4Diacmonds", text)
        assertFalse(text.contains('§'))
        assertFalse(text.any { it < ' ' })
    }

    @Test
    fun `uppercase colour codes and format codes of a template are converted, an ampersand without a code is kept`() {
        val text = McSyncService.renderAnnouncement("&Ahi &l{player} &z& done", "Alex", "x", 1, "s")

        assertEquals("§Ahi §lAlex &z& done", text)
    }
}
