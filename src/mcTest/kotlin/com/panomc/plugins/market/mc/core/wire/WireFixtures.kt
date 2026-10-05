package com.panomc.plugins.market.mc.core.wire

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * The shared wire fixtures (`src/mcTest/resources/wire/<Class>[.<variant>].json`): the canonical JSON of every
 * request and response. The Pano-side event tests read the same directory (system property `market.wireFixtures` of the
 * market `test` task), so both ends are held to one document (19 section 13, MC-U7).
 */
object WireFixtures {
    const val PACKAGE = "com.panomc.plugins.market.mc.core.wire"

    /** Same decoding as `Pano.gson` in pano-mc-plugin Core (the JsonObject adapter is irrelevant for these classes). */
    val gson: Gson = GsonBuilder().create()

    val dir: File by lazy {
        val prop = System.getProperty("market.wireFixtures")
        if (prop != null) File(prop) else File(requireNotNull(WireFixtures::class.java.classLoader.getResource("wire")) { "wire fixtures missing" }.toURI())
    }

    fun files(): List<File> = requireNotNull(dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }) { "no fixtures in $dir" }.sortedBy { it.name }

    /** `MarketSyncRequest.first.json` -> `MarketSyncRequest`. */
    fun classNameOf(file: File): String = file.name.removeSuffix(".json").substringBefore('.')

    fun load(file: File): JsonElement = JsonParser.parseString(file.readText(Charsets.UTF_8))

    fun classOf(simpleName: String): Class<*> = Class.forName("$PACKAGE.$simpleName")

    /** Removes every null value (Jackson on the request side emits them, Gson on the response side does not). */
    fun stripNulls(e: JsonElement): JsonElement = when {
        e.isJsonObject -> JsonObject().also { out ->
            e.asJsonObject.entrySet().forEach { (k, v) -> if (!v.isJsonNull) out.add(k, stripNulls(v)) }
        }
        e.isJsonArray -> JsonArray().also { out -> e.asJsonArray.forEach { out.add(if (it.isJsonNull) JsonNull.INSTANCE else stripNulls(it)) } }
        else -> e
    }

    /** Every field of a wire class (and, for requests, of MarketRequest / PlatformRequest) that is part of the JSON. */
    private fun jsonFields(c: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        var cur: Class<*>? = c
        while (cur != null && cur != Any::class.java) {
            // PlatformRequest.eventName is protected and never serialized; its eventId is.
            cur.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .filter { !(cur!!.name.endsWith(".PlatformRequest") && it.name != "eventId") }
                .forEach { out.add(it) }
            cur = cur.superclass
        }
        return out
    }

    private fun rawClass(t: Type): Class<*> = when (t) {
        is Class<*> -> t
        is ParameterizedType -> t.rawType as Class<*>
        is WildcardType -> rawClass(t.upperBounds[0])
        else -> Any::class.java
    }

    private fun isWire(t: Type) = rawClass(t).name.startsWith("$PACKAGE.") && !rawClass(t).isEnum

    /** All JSON paths a class can produce: `a.b`, lists add nothing, map values add `{}`. */
    fun classPaths(c: Class<*>, requestExtras: Boolean = true): Set<String> {
        val out = linkedSetOf<String>()
        if (requestExtras && com.panomc.plugins.pano.core.platform.PlatformRequest::class.java.isAssignableFrom(c)) out.add("event")
        fun walk(t: Type, prefix: String) {
            val raw = rawClass(t)
            when {
                Collection::class.java.isAssignableFrom(raw) -> walk((t as ParameterizedType).actualTypeArguments[0], prefix)
                Map::class.java.isAssignableFrom(raw) -> walk((t as ParameterizedType).actualTypeArguments[1], "$prefix{}")
                isWire(t) -> jsonFields(raw).forEach { f ->
                    val p = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
                    out.add(p)
                    walk(f.genericType, p)
                }
            }
        }
        walk(c, "")
        return out
    }

    /** Paths present (non-null) in a fixture, walked along the class; unknown keys of a wire object are reported. */
    fun fixturePaths(c: Class<*>, json: JsonElement, unknown: MutableList<String>): Set<String> {
        val out = linkedSetOf<String>()
        if (com.panomc.plugins.pano.core.platform.PlatformRequest::class.java.isAssignableFrom(c) && json.isJsonObject && json.asJsonObject.has("event")) out.add("event")
        fun walk(t: Type, e: JsonElement, prefix: String) {
            if (e.isJsonNull) return
            val raw = rawClass(t)
            when {
                Collection::class.java.isAssignableFrom(raw) ->
                    e.asJsonArray.forEach { walk((t as ParameterizedType).actualTypeArguments[0], it, prefix) }
                Map::class.java.isAssignableFrom(raw) ->
                    e.asJsonObject.entrySet().forEach { (_, v) -> walk((t as ParameterizedType).actualTypeArguments[1], v, "$prefix{}") }
                isWire(t) -> {
                    val fields = jsonFields(raw).associateBy { it.name }
                    e.asJsonObject.entrySet().forEach { (k, v) ->
                        val p = if (prefix.isEmpty()) k else "$prefix.$k"
                        val f = fields[k]
                        if (f == null) {
                            if (!(prefix.isEmpty() && (k == "event"))) unknown.add("${raw.simpleName}.$k")
                        } else {
                            if (!v.isJsonNull) out.add(p)
                            walk(f.genericType, v, p)
                        }
                    }
                }
            }
        }
        walk(c, json, "")
        return out
    }
}
