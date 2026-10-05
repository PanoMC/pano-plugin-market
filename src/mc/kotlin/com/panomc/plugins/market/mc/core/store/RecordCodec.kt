package com.panomc.plugins.market.mc.core.store

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * JSON of the journal and the snapshot. Only the Gson API that exists since 2.2.4 is used (Spigot 1.8.8 ships that
 * version): no `JsonParser.parseString`, no `keySet()`, no `JsonArray.isEmpty()`, no reflection based `toJson`.
 */
internal object RecordCodec {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseObject(text: String): JsonObject {
        val element = try {
            gson.fromJson(text, JsonObject::class.java)
        } catch (e: Exception) {
            throw StoreCorruptException("not a JSON object: ${e.message}", e)
        }
        return element ?: throw StoreCorruptException("empty JSON document")
    }

    fun toText(element: JsonElement): String = gson.toJson(element)

    // ---- records ---------------------------------------------------------------------------------------------

    fun recordToJson(r: DeliveryRecord): JsonObject {
        val o = JsonObject()
        o.addProperty("key", r.key)
        o.addProperty("id", r.id)
        o.addProperty("kind", r.kind)
        o.addProperty("phase", r.phase)
        val p = JsonObject()
        p.addProperty("username", r.player.username)
        r.player.uuid?.let { p.addProperty("uuid", it) }
        o.add("player", p)
        o.addProperty("requiresOnline", r.requiresOnline)
        r.expiresAt?.let { o.addProperty("expiresAt", it) }
        r.issuer?.let { o.addProperty("issuer", it) }
        if (r.commands.isNotEmpty()) o.add("commands", stringArray(r.commands))
        r.permission?.let { perm ->
            val po = JsonObject()
            po.addProperty("op", perm.op)
            po.add("nodes", stringArray(perm.nodes))
            perm.expiresAt?.let { po.addProperty("expiresAt", it) }
            o.add("permission", po)
        }
        r.display?.let { d ->
            val dobj = JsonObject()
            d.productName?.let { dobj.addProperty("productName", it) }
            d.orderPublicId?.let { dobj.addProperty("orderPublicId", it) }
            dobj.addProperty("gift", d.gift)
            d.from?.let { dobj.addProperty("from", it) }
            o.add("display", dobj)
        }
        o.addProperty("state", r.state.name)
        r.result?.let { o.add("result", resultToJson(it)) }
        o.addProperty("receivedAt", r.receivedAt)
        r.startedAt?.let { o.addProperty("startedAt", it) }
        r.finishedAt?.let { o.addProperty("finishedAt", it) }
        r.ackedAt?.let { o.addProperty("ackedAt", it) }
        if (r.queuedAcked) o.addProperty("queuedAcked", true)
        return o
    }

    fun recordFromJson(o: JsonObject): DeliveryRecord {
        val player = obj(o, "player")
        val perm = optObj(o, "permission")
        val display = optObj(o, "display")
        return DeliveryRecord(
            key = str(o, "key"),
            id = long(o, "id"),
            kind = str(o, "kind"),
            phase = optStr(o, "phase") ?: "",
            player = PlayerIdentity(str(player, "username"), optStr(player, "uuid")),
            requiresOnline = optBool(o, "requiresOnline") ?: false,
            expiresAt = optLong(o, "expiresAt"),
            issuer = optStr(o, "issuer"),
            commands = optStrings(o, "commands"),
            permission = perm?.let { PermissionSpec(str(it, "op"), optStrings(it, "nodes"), optLong(it, "expiresAt")) },
            display = display?.let {
                DisplayInfo(optStr(it, "productName"), optStr(it, "orderPublicId"), optBool(it, "gift") ?: false, optStr(it, "from"))
            },
            state = state(str(o, "state")),
            result = optObj(o, "result")?.let { resultFromJson(it) },
            receivedAt = long(o, "receivedAt"),
            startedAt = optLong(o, "startedAt"),
            finishedAt = optLong(o, "finishedAt"),
            ackedAt = optLong(o, "ackedAt"),
            queuedAcked = optBool(o, "queuedAcked") ?: false
        )
    }

    private fun resultToJson(r: RecordResult): JsonObject {
        val o = JsonObject()
        r.code?.let { o.addProperty("code", it) }
        r.message?.let { o.addProperty("message", it) }
        r.executedAt?.let { o.addProperty("executedAt", it) }
        if (r.commands.isNotEmpty()) {
            val arr = JsonArray()
            r.commands.forEach { c ->
                val co = JsonObject()
                co.addProperty("index", c.index)
                co.addProperty("ok", c.ok)
                c.error?.let { co.addProperty("error", it) }
                arr.add(co)
            }
            o.add("commands", arr)
        }
        return o
    }

    private fun resultFromJson(o: JsonObject): RecordResult {
        val commands = ArrayList<CommandOutcome>()
        val arr = optArr(o, "commands")
        if (arr != null) {
            for (e in arr) {
                val co = asObj(e, "commands[]")
                commands.add(CommandOutcome(long(co, "index").toInt(), optBool(co, "ok") ?: false, optStr(co, "error")))
            }
        }
        return RecordResult(optStr(o, "code"), optStr(o, "message"), optLong(o, "executedAt"), commands)
    }

    // ---- journal ops ------------------------------------------------------------------------------------------

    fun opToLine(seq: Long, op: JournalOp): String {
        val o = JsonObject()
        o.addProperty("seq", seq)
        when (op) {
            is JournalOp.Received -> {
                o.addProperty("op", "received")
                o.add("rec", recordToJson(op.record))
            }
            is JournalOp.Started -> {
                o.addProperty("op", "started")
                o.addProperty("key", op.key)
                o.addProperty("at", op.at)
            }
            is JournalOp.Finished -> {
                o.addProperty("op", "finished")
                o.addProperty("key", op.key)
                o.addProperty("state", op.state.name)
                o.add("result", resultToJson(op.result))
                o.addProperty("at", op.at)
            }
            is JournalOp.QueuedAcked -> {
                o.addProperty("op", "queuedAcked")
                o.addProperty("key", op.key)
            }
            is JournalOp.Acked -> {
                o.addProperty("op", "acked")
                o.addProperty("key", op.key)
                o.addProperty("at", op.at)
            }
            is JournalOp.Unacked -> {
                o.addProperty("op", "unacked")
                o.addProperty("key", op.key)
            }
            is JournalOp.Purged -> {
                o.addProperty("op", "purged")
                o.add("keys", stringArray(op.keys))
            }
            JournalOp.Probe -> o.addProperty("op", "probe")
        }
        return toText(o)
    }

    fun seqOf(o: JsonObject): Long = long(o, "seq")

    fun opFromJson(o: JsonObject): JournalOp = when (val name = str(o, "op")) {
        "received" -> JournalOp.Received(recordFromJson(obj(o, "rec")))
        "started" -> JournalOp.Started(str(o, "key"), long(o, "at"))
        "finished" -> JournalOp.Finished(str(o, "key"), state(str(o, "state")), obj(o, "result").let { resultFromJson(it) }, long(o, "at"))
        "queuedAcked" -> JournalOp.QueuedAcked(str(o, "key"))
        "acked" -> JournalOp.Acked(str(o, "key"), long(o, "at"))
        "unacked" -> JournalOp.Unacked(str(o, "key"))
        "purged" -> JournalOp.Purged(optStrings(o, "keys"))
        "probe" -> JournalOp.Probe
        else -> throw StoreCorruptException("unknown journal op '$name'")
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private fun state(name: String): RecordState =
        RecordState.values().firstOrNull { it.name == name } ?: throw StoreCorruptException("unknown state '$name'")

    private fun stringArray(values: List<String>): JsonArray {
        val arr = JsonArray()
        values.forEach { arr.add(JsonPrimitive(it)) }
        return arr
    }

    private fun primitive(o: JsonObject, name: String): JsonPrimitive? {
        val e = o.get(name) ?: return null
        if (e.isJsonNull) return null
        if (!e.isJsonPrimitive) throw StoreCorruptException("field '$name' is not a primitive")
        return e.asJsonPrimitive
    }

    private fun str(o: JsonObject, name: String): String =
        optStr(o, name) ?: throw StoreCorruptException("missing string '$name'")

    private fun optStr(o: JsonObject, name: String): String? {
        val p = primitive(o, name) ?: return null
        if (!p.isString) throw StoreCorruptException("field '$name' is not a string")
        return p.asString
    }

    private fun long(o: JsonObject, name: String): Long =
        optLong(o, name) ?: throw StoreCorruptException("missing number '$name'")

    private fun optLong(o: JsonObject, name: String): Long? {
        val p = primitive(o, name) ?: return null
        if (!p.isNumber) throw StoreCorruptException("field '$name' is not a number")
        return try {
            p.asLong
        } catch (e: NumberFormatException) {
            throw StoreCorruptException("field '$name' is not an integer", e)
        }
    }

    private fun optBool(o: JsonObject, name: String): Boolean? {
        val p = primitive(o, name) ?: return null
        if (!p.isBoolean) throw StoreCorruptException("field '$name' is not a boolean")
        return p.asBoolean
    }

    private fun optStrings(o: JsonObject, name: String): List<String> {
        val arr = optArr(o, name) ?: return emptyList()
        val out = ArrayList<String>()
        for (e in arr) {
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) throw StoreCorruptException("'$name' holds a non-string")
            out.add(e.asString)
        }
        return out
    }

    private fun optArr(o: JsonObject, name: String): JsonArray? {
        val e = o.get(name) ?: return null
        if (e.isJsonNull) return null
        if (!e.isJsonArray) throw StoreCorruptException("field '$name' is not an array")
        return e.asJsonArray
    }

    private fun obj(o: JsonObject, name: String): JsonObject =
        optObj(o, name) ?: throw StoreCorruptException("missing object '$name'")

    private fun optObj(o: JsonObject, name: String): JsonObject? {
        val e = o.get(name) ?: return null
        if (e.isJsonNull) return null
        return asObj(e, name)
    }

    private fun asObj(e: JsonElement, name: String): JsonObject {
        if (!e.isJsonObject) throw StoreCorruptException("field '$name' is not an object")
        return e.asJsonObject
    }
}
