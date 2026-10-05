package com.panomc.plugins.market.component

import com.panomc.platform.PluginEventManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.plugins.market.provider.ExtensionSource
import com.panomc.plugins.market.provider.PlatformExtensionSource
import com.panomc.plugins.market.provider.PluginExtension
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.provider.ProviderOrigin
import com.panomc.plugins.market.provider.ProviderRegistry
import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pf4j.PluginState
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 02 section 2 / 11, 17 section 11.2: id clash rules, SPI version filter, built-ins present, providers coming and going. */
class ProviderRegistryTest {
    // ---- fakes

    private class Pay(override val id: String, val tag: String = "") : PaymentProvider {
        override val descriptor = ProviderDescriptor(LocalizedText.of(id), LocalizedText.of(id), "credit-card")
        override fun settingsSchema(): SettingsSchema = settingsSchema { }
        override fun capabilities(settings: ProviderSettings) = PaymentCapabilities()
        override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult = error("not used")
        override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult = InboundResult.ignored(HttpReply.empty())
    }

    private class Ship(override val id: String) : ShippingProvider {
        override val descriptor = ProviderDescriptor(LocalizedText.of(id), LocalizedText.of(id), "truck")
        override fun settingsSchema(): SettingsSchema = settingsSchema { }
        override fun capabilities(settings: ProviderSettings) = ShippingCapabilities()
    }

    private class Ext(
        override val spiVersion: Int = MarketSpi.VERSION,
        val payment: () -> List<PaymentProvider> = { emptyList() },
        val shipping: () -> List<ShippingProvider> = { emptyList() }
    ) : MarketExtension {
        override fun paymentProviders(): List<PaymentProvider> = payment()
        override fun shippingProviders(): List<ShippingProvider> = shipping()
    }

    /** The registered extensions and the plugin states, both editable between lookups. */
    private class World {
        val extensions = CopyOnWriteArrayList<PluginExtension>()
        val states = ConcurrentHashMap<String, PluginState>()

        fun plugin(pluginId: String, ext: MarketExtension, state: PluginState = PluginState.STARTED) {
            extensions.add(PluginExtension(pluginId, ext))
            states[pluginId] = state
        }

        fun remove(pluginId: String) {
            extensions.removeIf { it.pluginId == pluginId }
        }

        fun registry(builtInPayment: List<PaymentProvider> = emptyList(), builtInShipping: List<ShippingProvider> = emptyList()) =
            ProviderRegistry({ extensions.toList() }, { states[it] }, { builtInPayment }, { builtInShipping })
    }

    private fun ids(r: ProviderRegistry) = r.allPayment().map { it.id }

    // ---- built-ins

    @Test
    fun `built-ins are present without any plugin and come first`() {
        val world = World()
        val registry = world.registry(builtInPayment = listOf(Pay("free"), Pay("credits"), Pay("bank-transfer")))
        assertEquals(listOf("free", "credits", "bank-transfer"), ids(registry))
        assertTrue(registry.allPayment().all { it.origin == ProviderOrigin.BUILT_IN && it.pluginId == null })
        assertEquals(MarketSpi.VERSION, registry.payment("free")!!.spiVersion)
        world.plugin("pano-plugin-stripe", Ext(payment = { listOf(Pay("stripe")) }))
        assertEquals(listOf("free", "credits", "bank-transfer", "stripe"), ids(registry))
        assertEquals(ProviderAvailability.AVAILABLE, registry.state(ProviderKind.PAYMENT, "free").availability)
    }

    @Test
    fun `a registry with nothing registered is empty and answers every question`() {
        val registry = World().registry()
        assertTrue(registry.allPayment().isEmpty())
        assertTrue(registry.allShipping().isEmpty())
        assertNull(registry.payment("stripe"))
        assertNull(registry.shipping("ups"))
        assertEquals(ProviderAvailability.MISSING, registry.state(ProviderKind.PAYMENT, "stripe").availability)
        assertTrue(registry.listing(ProviderKind.PAYMENT).isEmpty())
        assertTrue(registry.extensions().isEmpty())
    }

    // ---- plugin state

    @Test
    fun `only providers of started plugins are offered`() {
        val world = World()
        val registry = world.registry()
        for ((name, state) in listOf(
            "p-resolved" to PluginState.RESOLVED, "p-created" to PluginState.CREATED, "p-disabled" to PluginState.DISABLED,
            "p-failed" to PluginState.FAILED, "p-stopped" to PluginState.STOPPED
        )) {
            world.plugin(name, Ext(payment = { listOf(Pay(name)) }), state)
        }
        world.plugin("p-started", Ext(payment = { listOf(Pay("started")) }))
        world.extensions.add(PluginExtension("p-unknown", Ext(payment = { listOf(Pay("unknown")) }))) // the plugin manager does not know it
        assertEquals(listOf("started"), ids(registry))
        assertEquals(listOf("p-started"), registry.extensions().map { it.pluginId })
        assertEquals(ProviderAvailability.MISSING, registry.state(ProviderKind.PAYMENT, "failed").availability)
    }

    @Test
    fun `a plugin manager that throws counts as not started and breaks nothing`() {
        val world = World()
        world.plugin("good", Ext(payment = { listOf(Pay("good-one")) }))
        world.plugin("bad", Ext(payment = { listOf(Pay("bad-one")) }))
        val registry = ProviderRegistry({ world.extensions.toList() }, { if (it == "bad") throw IllegalStateException("boom") else PluginState.STARTED })
        assertEquals(listOf("good-one"), registry.allPayment().map { it.id })
    }

    // ---- SPI version filter

    @Test
    fun `providers of a plugin outside the supported SPI range are listed as incompatible and never offered`() {
        val world = World()
        val registry = world.registry()
        world.plugin("too-new", Ext(spiVersion = MarketSpi.VERSION + 1, payment = { listOf(Pay("newer")) }))
        world.plugin("too-old", Ext(spiVersion = MarketSpi.MIN_SUPPORTED - 1, payment = { listOf(Pay("older")) }))
        world.plugin("fine", Ext(spiVersion = MarketSpi.VERSION, payment = { listOf(Pay("fine-one")) }))
        assertEquals(listOf("fine-one"), ids(registry))
        val newer = registry.state(ProviderKind.PAYMENT, "newer")
        assertEquals(ProviderAvailability.INCOMPATIBLE, newer.availability)
        assertEquals(MarketSpi.VERSION + 1, newer.spiVersion)
        assertEquals("too-new", newer.pluginId)
        assertEquals("INCOMPATIBLE (built for SPI ${MarketSpi.VERSION + 1})", newer.detail)
        assertEquals(ProviderAvailability.INCOMPATIBLE, registry.state(ProviderKind.PAYMENT, "older").availability)
        assertEquals(listOf("fine"), registry.extensions().map { it.pluginId })
        val listed = registry.listing(ProviderKind.PAYMENT).associate { it.id to it.state.availability }
        assertEquals(
            mapOf("newer" to ProviderAvailability.INCOMPATIBLE, "older" to ProviderAvailability.INCOMPATIBLE, "fine-one" to ProviderAvailability.AVAILABLE),
            listed
        )
    }

    @Test
    fun `an incompatible extension that cannot even name its providers is listed once without an id`() {
        val world = World()
        world.plugin("old-abi", Ext(spiVersion = 99, payment = { throw AbstractMethodError("paymentProviders") }))
        val registry = world.registry()
        val rows = registry.listing(ProviderKind.PAYMENT)
        assertEquals(1, rows.size)
        assertNull(rows[0].id)
        assertEquals(ProviderAvailability.INCOMPATIBLE, rows[0].state.availability)
        assertEquals("old-abi", rows[0].state.pluginId)
        assertTrue(registry.allPayment().isEmpty())
    }

    @Test
    fun `the SPI range boundaries are inclusive`() {
        val world = World()
        world.plugin("lo", Ext(spiVersion = MarketSpi.MIN_SUPPORTED, payment = { listOf(Pay("at-min")) }))
        world.plugin("hi", Ext(spiVersion = MarketSpi.VERSION, payment = { listOf(Pay("at-max")) }))
        assertEquals(setOf("at-min", "at-max"), ids(world.registry()).toSet())
    }

    // ---- id clash rules

    @Test
    fun `on an id clash the built-in wins and the plugin provider is reported as shadowed`() {
        val world = World()
        world.plugin("pano-plugin-evil", Ext(payment = { listOf(Pay("bank-transfer", "plugin")) }))
        val builtIn = Pay("bank-transfer", "built-in")
        val registry = world.registry(builtInPayment = listOf(builtIn))
        assertSame(builtIn, registry.payment("bank-transfer")!!.provider)
        assertEquals(1, ids(registry).size)
        val shadowed = registry.listing(ProviderKind.PAYMENT).single { it.state.availability == ProviderAvailability.SHADOWED }
        assertEquals("bank-transfer", shadowed.id)
        assertEquals("pano-plugin-evil", shadowed.state.pluginId)
        assertNull(shadowed.resolved)
        assertEquals(ProviderAvailability.AVAILABLE, registry.state(ProviderKind.PAYMENT, "bank-transfer").availability, "the id itself is served")
    }

    @Test
    fun `between two plugins the first by plugin id wins whatever the registration order`() {
        for (order in listOf(listOf("b-plugin", "a-plugin"), listOf("a-plugin", "b-plugin"))) {
            val world = World()
            for (p in order) world.plugin(p, Ext(payment = { listOf(Pay("shared", p)) }))
            val registry = world.registry()
            val winner = registry.payment("shared")!!
            assertEquals("a-plugin", winner.pluginId, "registered as $order")
            assertEquals("a-plugin", (winner.provider as Pay).tag)
            val loser = registry.listing(ProviderKind.PAYMENT).single { it.state.availability == ProviderAvailability.SHADOWED }
            assertEquals("b-plugin", loser.state.pluginId)
            assertTrue(loser.state.detail!!.contains("a-plugin"))
        }
    }

    @Test
    fun `the same id twice inside one plugin keeps the first`() {
        val world = World()
        world.plugin("dup", Ext(payment = { listOf(Pay("same", "first"), Pay("same", "second")) }))
        val registry = world.registry()
        assertEquals("first", (registry.payment("same")!!.provider as Pay).tag)
        assertEquals(1, registry.listing(ProviderKind.PAYMENT).count { it.state.availability == ProviderAvailability.SHADOWED })
    }

    @Test
    fun `an incompatible provider does not claim the id`() {
        val world = World()
        world.plugin("a-old", Ext(spiVersion = MarketSpi.VERSION + 1, payment = { listOf(Pay("shared", "old")) }))
        world.plugin("b-new", Ext(payment = { listOf(Pay("shared", "new")) }))
        val registry = world.registry()
        assertEquals("new", (registry.payment("shared")!!.provider as Pay).tag)
        assertEquals(ProviderAvailability.AVAILABLE, registry.state(ProviderKind.PAYMENT, "shared").availability)
    }

    @Test
    fun `payment and shipping ids are separate namespaces`() {
        val world = World()
        world.plugin("both", Ext(payment = { listOf(Pay("acme")) }, shipping = { listOf(Ship("acme")) }))
        val registry = world.registry()
        assertNotNull(registry.payment("acme"))
        assertNotNull(registry.shipping("acme"))
        assertEquals(listOf("acme"), registry.allShipping().map { it.id })
        assertEquals(ProviderAvailability.AVAILABLE, registry.state(ProviderKind.SHIPPING, "acme").availability)
    }

    @Test
    fun `invalid provider ids are listed as invalid and never offered`() {
        val world = World()
        val bad = listOf("A", "x", "has_underscore", "UPPER", "a".repeat(33), "with space", "")
        world.plugin("p", Ext(payment = { bad.map { Pay(it) } + Pay("ok-id") }))
        val registry = world.registry()
        assertEquals(listOf("ok-id"), ids(registry))
        val invalid = registry.listing(ProviderKind.PAYMENT).filter { it.state.availability == ProviderAvailability.INVALID }.map { it.id }
        assertEquals(bad, invalid)
        assertEquals(listOf("a".repeat(32)), World().also { it.plugin("q", Ext(payment = { listOf(Pay("a".repeat(32))) })) }.registry().allPayment().map { it.id })
    }

    // ---- appearing and disappearing

    @Test
    fun `providers appearing and disappearing between two lookups are noticed at once`() {
        val world = World()
        val registry = world.registry()
        assertNull(registry.payment("stripe"))
        world.plugin("stripe-plugin", Ext(payment = { listOf(Pay("stripe")) }))
        assertNotNull(registry.payment("stripe"))
        world.states["stripe-plugin"] = PluginState.FAILED // license lapsed: the host stops the plugin
        assertNull(registry.payment("stripe"))
        assertEquals(ProviderAvailability.MISSING, registry.state(ProviderKind.PAYMENT, "stripe").availability)
        world.states["stripe-plugin"] = PluginState.STARTED
        assertNotNull(registry.payment("stripe"))
        world.remove("stripe-plugin") // unRegister in onStop
        assertNull(registry.payment("stripe"))
    }

    @Test
    fun `the provider list of an extension is asked on every lookup, never cached`() {
        val calls = AtomicInteger()
        val world = World()
        world.plugin("p", Ext(payment = { calls.incrementAndGet(); listOf(Pay("one")) }))
        val registry = world.registry()
        repeat(5) { registry.allPayment() }
        assertEquals(5, calls.get())
        registry.payment("one")
        assertEquals(6, calls.get())
    }

    @Test
    fun `a plugin whose provider list changes between lookups is followed`() {
        val current = java.util.concurrent.atomic.AtomicReference(listOf("a-one"))
        val world = World()
        world.plugin("p", Ext(payment = { current.get().map { Pay(it) } }))
        val registry = world.registry()
        assertEquals(listOf("a-one"), ids(registry))
        current.set(listOf("a-one", "b-two"))
        assertEquals(listOf("a-one", "b-two"), ids(registry))
        current.set(listOf("b-two"))
        assertEquals(listOf("b-two"), ids(registry))
    }

    // ---- hostile plugins

    @Test
    fun `a plugin that throws never breaks the lookup of the others`() {
        val world = World()
        world.plugin("a-spi-throws", object : MarketExtension {
            override val spiVersion: Int get() = throw IllegalStateException("no spi")
        })
        world.plugin("b-list-throws", Ext(payment = { throw IllegalStateException("no list") }))
        world.plugin("c-id-throws", Ext(payment = {
            listOf(object : PaymentProvider by Pay("x-y") {
                override val id: String get() = throw IllegalStateException("no id")
            })
        }))
        world.plugin("d-linkage", Ext(payment = { throw NoClassDefFoundError("missing/Class") }))
        world.plugin("e-fine", Ext(payment = { listOf(Pay("fine")) }))
        val registry = world.registry()
        assertEquals(listOf("fine"), ids(registry))
        val invalid = registry.listing(ProviderKind.PAYMENT).filter { it.state.availability == ProviderAvailability.INVALID }
        assertEquals(setOf("a-spi-throws", "b-list-throws", "c-id-throws", "d-linkage"), invalid.map { it.state.pluginId }.toSet())
        assertTrue(registry.allShipping().isEmpty())
        repeat(3) { assertEquals(listOf("fine"), ids(registry)) } // stable on every lookup
    }

    @Test
    fun `throwing built-in suppliers give an empty built-in list`() {
        val registry = ProviderRegistry({ emptyList() }, { PluginState.STARTED }, { throw IllegalStateException("x") }, { throw IllegalStateException("y") })
        assertTrue(registry.allPayment().isEmpty())
        assertTrue(registry.allShipping().isEmpty())
    }

    // ---- unsafe host map

    private class FlakySource(val failures: Int, val inner: List<PluginExtension>) : ExtensionSource {
        val calls = AtomicInteger()
        override fun extensions(): List<PluginExtension> {
            if (calls.incrementAndGet() <= failures) throw ConcurrentModificationException("host map changed")
            return inner
        }
    }

    @Test
    fun `a concurrent modification is retried once`() {
        val ext = PluginExtension("p", Ext(payment = { listOf(Pay("one")) }))
        val source = FlakySource(1, listOf(ext))
        val registry = ProviderRegistry(source, { PluginState.STARTED })
        assertEquals(listOf("one"), registry.allPayment().map { it.id })
        assertEquals(2, source.calls.get(), "one failure, one retry")
    }

    @Test
    fun `after two failures the last good snapshot answers`() {
        val ext = PluginExtension("p", Ext(payment = { listOf(Pay("one")) }))
        val world = object : ExtensionSource {
            @Volatile var mode = 0
            override fun extensions(): List<PluginExtension> = if (mode == 0) listOf(ext) else throw ConcurrentModificationException()
        }
        val registry = ProviderRegistry(world, { PluginState.STARTED })
        assertEquals(listOf("one"), registry.allPayment().map { it.id })
        world.mode = 1
        assertEquals(listOf("one"), registry.allPayment().map { it.id }, "the snapshot stands in")
        assertEquals(ProviderAvailability.AVAILABLE, registry.state(ProviderKind.PAYMENT, "one").availability)
    }

    @Test
    fun `without a snapshot yet a failing read gives an empty answer, not an exception`() {
        val registry = ProviderRegistry(FlakySource(100, emptyList()), { PluginState.STARTED })
        assertTrue(registry.allPayment().isEmpty())
        assertEquals(ProviderAvailability.MISSING, registry.state(ProviderKind.PAYMENT, "x").availability)
    }

    @Test
    fun `any other failure of the read also falls back to the snapshot`() {
        val ext = PluginExtension("p", Ext(payment = { listOf(Pay("one")) }))
        val flip = AtomicBoolean(false)
        val registry = ProviderRegistry({ if (flip.get()) throw IllegalStateException("host broke") else listOf(ext) }, { PluginState.STARTED })
        assertEquals(1, registry.allPayment().size)
        flip.set(true)
        assertEquals(1, registry.allPayment().size)
    }

    @Test
    fun `the plugin state is evaluated afresh even when the snapshot stands in`() {
        val ext = PluginExtension("p", Ext(payment = { listOf(Pay("one")) }))
        val states = ConcurrentHashMap<String, PluginState>().also { it["p"] = PluginState.STARTED }
        val broken = AtomicBoolean(false)
        val registry = ProviderRegistry({ if (broken.get()) throw ConcurrentModificationException() else listOf(ext) }, { states[it] })
        assertEquals(1, registry.allPayment().size)
        broken.set(true)
        states["p"] = PluginState.DISABLED
        assertTrue(registry.allPayment().isEmpty())
    }

    /** A source that reads a plain (unsynchronised) list the way the host's old map was read: the iterator throws while a writer changes it. */
    private class UnsafeSource : ExtensionSource {
        private val list = java.util.ArrayList<PluginExtension>()
        val modifications = AtomicInteger()
        val concurrentModifications = AtomicInteger()

        @Synchronized fun add(e: PluginExtension) { list.add(e); modifications.incrementAndGet() }
        @Synchronized fun removeFirst() { if (list.isNotEmpty()) list.removeAt(0); modifications.incrementAndGet() }

        override fun extensions(): List<PluginExtension> {
            val out = ArrayList<PluginExtension>()
            try {
                // iterating without the lock: a writer thread can modify the list underneath
                for (e in list) { out.add(e); Thread.yield() }
            } catch (e: ConcurrentModificationException) {
                concurrentModifications.incrementAndGet()
                throw e
            } catch (e: IndexOutOfBoundsException) {
                concurrentModifications.incrementAndGet()
                throw ConcurrentModificationException()
            }
            return out
        }
    }

    @Test
    fun `looking providers up in a loop while another thread registers and unregisters never throws`() {
        val source = UnsafeSource()
        val registry = ProviderRegistry(source, { PluginState.STARTED }, { listOf(Pay("built-in")) })
        val running = AtomicBoolean(true)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = Thread {
            var n = 0
            try {
                while (running.get()) {
                    source.add(PluginExtension("plugin-${n % 7}", Ext(payment = { listOf(Pay("p-${n % 7}")) })))
                    if (n % 3 != 0) source.removeFirst()
                    n++
                }
            } catch (t: Throwable) { failure.set(t) }
        }
        writer.start()
        var lookups = 0
        val deadline = System.nanoTime() + 5_000_000_000L
        try {
            while ((lookups < 3000 || source.concurrentModifications.get() == 0) && System.nanoTime() < deadline) {
                registry.allPayment()
                registry.state(ProviderKind.PAYMENT, "p-1")
                registry.payment("built-in")
                lookups++
            }
        } finally {
            running.set(false)
            writer.join(5000)
        }
        assertNull(failure.get(), "writer failed: ${failure.get()}")
        assertTrue(lookups >= 3000, "only $lookups lookups")
        assertTrue(source.concurrentModifications.get() > 0, "the test never hit a concurrent modification, so it proves nothing")
        assertNotNull(registry.payment("built-in"))
    }

    // ---- the real plugin event bus

    private class TestPlugin(id: String) : PanoPlugin() {
        init {
            val field = PanoPlugin::class.java.getDeclaredField("pluginId")
            field.isAccessible = true
            field.set(this, id)
        }
    }

    private val installed = ArrayList<PanoPlugin>()
    private val manager = PluginEventManager()

    private fun internalCall(name: String, vararg args: Any) {
        val m = PluginEventManager::class.java.declaredMethods.single { it.name.startsWith(name) && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(manager, *args)
    }

    private fun install(pluginId: String, ext: MarketExtension): PanoPlugin {
        val plugin = TestPlugin(pluginId)
        AnnotationConfigApplicationContext().use { ctx ->
            ctx.refresh()
            internalCall("initializePlugin", plugin, ctx)
        }
        installed.add(plugin)
        manager.register(plugin, ext)
        return plugin
    }

    @AfterEach
    fun cleanBus() {
        for (p in installed) internalCall("unregisterPlugin", p)
        installed.clear()
    }

    @Test
    fun `the platform source reads the real plugin event bus and follows registration and unregistration`() {
        val states = ConcurrentHashMap<String, PluginState>()
        val registry = ProviderRegistry(PlatformExtensionSource(), { states[it] }, { listOf(Pay("built-in")) })
        val ext = Ext(payment = { listOf(Pay("bus-pay")) }, shipping = { listOf(Ship("bus-ship")) })
        val plugin = install("bus-plugin-test", ext)
        states["bus-plugin-test"] = PluginState.STARTED
        assertEquals(listOf("built-in", "bus-pay"), ids(registry))
        assertEquals(listOf("bus-ship"), registry.allShipping().map { it.id })
        assertEquals("bus-plugin-test", registry.payment("bus-pay")!!.pluginId)

        states["bus-plugin-test"] = PluginState.FAILED
        assertEquals(listOf("built-in"), ids(registry))
        states["bus-plugin-test"] = PluginState.STARTED
        assertEquals(2, ids(registry).size)

        manager.unRegister(plugin, ext) // what PanoPlugin.unRegister does with the host's manager
        assertEquals(listOf("built-in"), ids(registry))
    }

    @Test
    fun `a plugin whose id is not set yet is skipped by the platform source`() {
        val plugin = object : PanoPlugin() {}
        AnnotationConfigApplicationContext().use { ctx ->
            ctx.refresh()
            internalCall("initializePlugin", plugin, ctx)
        }
        installed.add(plugin)
        manager.register(plugin, Ext(payment = { listOf(Pay("no-id")) }))
        val registry = ProviderRegistry(PlatformExtensionSource(), { PluginState.STARTED })
        assertFalse(registry.allPayment().any { it.id == "no-id" })
    }

    @Test
    fun `lookups while plugins register and unregister on the real bus never throw`() {
        val registry = ProviderRegistry(PlatformExtensionSource(), { PluginState.STARTED })
        val running = AtomicBoolean(true)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                var n = 0
                while (running.get()) {
                    val id = "bus-loop-${n % 5}"
                    val ext = Ext(payment = { listOf(Pay("loop-$n")) })
                    val plugin = TestPlugin(id)
                    AnnotationConfigApplicationContext().use { ctx ->
                        ctx.refresh()
                        internalCall("initializePlugin", plugin, ctx)
                    }
                    manager.register(plugin, ext)
                    manager.unRegister(plugin, ext)
                    internalCall("unregisterPlugin", plugin)
                    n++
                }
            } catch (t: Throwable) { failure.set(t) }
        }
        writer.start()
        try {
            repeat(2000) { registry.allPayment() }
        } finally {
            running.set(false)
            writer.join(10_000)
        }
        assertNull(failure.get(), "writer failed: ${failure.get()}")
    }
}
