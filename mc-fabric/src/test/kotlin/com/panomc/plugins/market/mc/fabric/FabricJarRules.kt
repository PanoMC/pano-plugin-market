package com.panomc.plugins.market.mc.fabric

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.zip.ZipFile

/**
 * The rules the Fabric jar has to satisfy (19 section 2.1, 2.3): relocated references, nothing bundled, only the
 * engine and the Fabric adapter inside, the descriptor filled in. Returns the violations (empty = the jar is fine), so
 * the test can run it on the real jar and on deliberately bad ones.
 */
object FabricJarRules {
    const val MOD_ID = "panomarket"
    const val ENTRYPOINT = "com.panomc.plugins.market.mc.fabric.MarketFabricMod"

    /** Class major 68 = Java 24, the byte level of pano-mc-plugin's Fabric module. */
    const val MAX_CLASS_MAJOR = 68

    private val allowedClassRoots = listOf("com/panomc/plugins/market/mc/core/", "com/panomc/plugins/market/mc/fabric/")

    /**
     * Packages the Pano mod ships relocated under com.panomc.shadow (or, for Netty, vertx.io.netty); a reference to one of
     * them under its original name cannot link on Fabric's flat class loader.
     */
    private val unrelocated = listOf(
        "kotlin/", "kotlinx/", "com/google/gson/", "io/vertx/", "io/netty/", "org/springframework/", "com/github/jknack/",
        "com/typesafe/", "org/jetbrains/annotations/", "org/intellij/lang/annotations/", "com/fasterxml/jackson/",
        "org/apache/commons/logging/"
    )

    /** What a relocated jar has to reference at least once (proof that the relocation really ran). */
    private val mustReference = listOf("com/panomc/shadow/kotlin/", "com/panomc/shadow/kotlinx/", "com/panomc/shadow/gson/")

    private val forbidden = listOf("com/panomc/platform/", "org/bukkit/", "net/md_5/", "com/velocitypowered/", "net/milkbowl/", "me/clip/")
    private val otherMarketRef = Regex("com/panomc/plugins/market/(?!mc/)")

    /**
     * Every class the bytecode refers to (supers, member descriptors, signatures, instructions, annotation TYPES), by
     * letting ASM's remapper walk the class and recording what it is asked to map. Strings are not mapped by a remapper, so the
     * Kotlin `@Metadata` d1 / d2 strings (which no relocation rewrites, and which are harmless) never show up here.
     */
    fun referencedClasses(bytes: ByteArray): Set<String> {
        val seen = HashSet<String>()
        val remapper = object : Remapper() {
            override fun map(internalName: String): String {
                seen.add(internalName)
                return internalName
            }
        }
        ClassReader(bytes).accept(ClassRemapper(ClassWriter(0), remapper), 0)
        return seen
    }

    fun check(jar: File, version: String, expectedResources: Map<String, ByteArray>): List<String> {
        val out = ArrayList<String>()
        ZipFile(jar).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            fun bytes(name: String) = z.getInputStream(z.getEntry(name)).use { it.readBytes() }

            // Contents: only the engine, the adapter, the descriptor, the shared resources and META-INF.
            for (n in names) {
                if (n.endsWith("/")) continue
                val ok = when {
                    n.endsWith(".class") -> allowedClassRoots.any { n.startsWith(it) }
                    n == "fabric.mod.json" || n.startsWith("META-INF/") -> true
                    n.startsWith("mc/") -> true
                    else -> false
                }
                if (!ok) out.add("unexpected entry $n")
            }

            // Descriptor.
            if ("fabric.mod.json" !in names) {
                out.add("fabric.mod.json missing")
            } else {
                val text = String(bytes("fabric.mod.json"), Charsets.UTF_8)
                if (text.contains("\${")) out.add("fabric.mod.json holds an unexpanded placeholder")
                if (!Regex("\"id\"\\s*:\\s*\"$MOD_ID\"").containsMatchIn(text)) out.add("fabric.mod.json id is not $MOD_ID")
                if (!Regex("\"version\"\\s*:\\s*\"${Regex.escape(version)}\"").containsMatchIn(text)) out.add("fabric.mod.json does not carry the version $version")
                if (!Regex("\"server\"\\s*:\\s*\\[\\s*\"${Regex.escape(ENTRYPOINT)}\"").containsMatchIn(text)) out.add("fabric.mod.json names no server entry point $ENTRYPOINT")
                if (!Regex("\"pano\"\\s*:").containsMatchIn(text)) out.add("fabric.mod.json does not depend on pano")
                if (!Regex("\"environment\"\\s*:\\s*\"server\"").containsMatchIn(text)) out.add("fabric.mod.json is not server only")
            }
            if (ENTRYPOINT.replace('.', '/') + ".class" !in names) out.add("entry point class $ENTRYPOINT is not in the jar")

            // Shared resources are the very files of the other platforms.
            for ((name, expected) in expectedResources) {
                if (name !in names) out.add("$name missing") else if (!bytes(name).contentEquals(expected)) out.add("$name differs from src/mc/resources")
            }

            // Classes.
            val referenced = HashSet<String>()
            for (n in names.filter { it.endsWith(".class") }) {
                val b = bytes(n)
                if (b.size < 8) { out.add("$n is truncated"); continue }
                val major = ((b[6].toInt() and 0xff) shl 8) or (b[7].toInt() and 0xff)
                if (major > MAX_CLASS_MAJOR) out.add("$n is class major $major (> $MAX_CLASS_MAJOR)")
                val refs = try {
                    referencedClasses(b)
                } catch (e: Exception) {
                    out.add("$n cannot be read: ${e.javaClass.simpleName}")
                    continue
                }
                referenced.addAll(refs)
                for (r in refs) {
                    unrelocated.firstOrNull { r.startsWith(it) }?.let { out.add("$n references the unrelocated library class $r") }
                    forbidden.firstOrNull { r.startsWith(it) }?.let { out.add("$n references $r") }
                    if (otherMarketRef.containsMatchIn(r)) out.add("$n references a market package outside mc.**: $r")
                }
            }
            if (names.any { it.endsWith(".class") }) {
                for (m in mustReference) if (referenced.none { it.startsWith(m) }) out.add("no class references $m: the relocation did not run")
            }
        }
        return out
    }
}
