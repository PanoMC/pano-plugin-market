package com.panomc.plugins.market.provider

import com.panomc.platform.PluginEventManager
import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import org.pf4j.PluginManager
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** A [MarketExtension] and the id of the plugin that registered it. */
class PluginExtension(val pluginId: String, val extension: MarketExtension)

/** Raw read of the registered extensions. May throw [ConcurrentModificationException] while plugins start or stop. */
fun interface ExtensionSource {
    fun extensions(): List<PluginExtension>
}

/** Reads the platform's plugin event bus (`PluginEventManager`, 02 section 2). */
class PlatformExtensionSource : ExtensionSource {
    override fun extensions(): List<PluginExtension> =
        PluginEventManager.getEventListeners().flatMap { (plugin, listeners) ->
            // pluginId is a lateinit set by the host: a plugin caught half way through loading is skipped, not fatal.
            val id = try {
                plugin.pluginId
            } catch (e: UninitializedPropertyAccessException) {
                return@flatMap emptyList<PluginExtension>()
            }
            listeners.toList().filterIsInstance<MarketExtension>().map { PluginExtension(id, it) }
        }
}

/**
 * Finds the payment and shipping providers (02 section 2, 11): the built-ins plus those of every started plugin
 * whose [MarketExtension] speaks a supported SPI version. Evaluated on **every** call, never cached, because plugins
 * start, stop and fail their license check at any time.
 *
 * - Ids are unique per kind. On a clash the built-in wins, then the first plugin by plugin id; the loser is
 *   [ProviderAvailability.SHADOWED] in [listing].
 * - Extensions of a plugin whose state is not `STARTED` are ignored (a failed start leaves its load-time registration
 *   behind, 02 section 2). An extension outside `MIN_SUPPORTED..VERSION` lists its providers as `INCOMPATIBLE`.
 * - The host's event map was not thread safe once; the read is guarded: a [ConcurrentModificationException] is
 *   retried once and then answered from the last good snapshot. Any other failure of the read does the same.
 * - A plugin that throws while it is asked for its providers or their ids never breaks a lookup: it is listed as
 *   `INVALID` (once per message in the log).
 */
class ProviderRegistry(
    private val source: ExtensionSource,
    private val pluginState: (String) -> PluginState?,
    private val builtInPayment: () -> List<PaymentProvider> = { emptyList() },
    private val builtInShipping: () -> List<ShippingProvider> = { emptyList() }
) : ProviderLookup {
    @Volatile
    private var lastSnapshot: List<PluginExtension> = emptyList()

    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** Registered extensions of started plugins with a supported SPI version, in plugin id order. */
    fun extensions(): List<PluginExtension> = started().filter { compatible(it) }

    override fun allPayment(): List<ResolvedProvider<PaymentProvider>> = evaluate(ProviderKind.PAYMENT).usablePayment()

    override fun allShipping(): List<ResolvedProvider<ShippingProvider>> = evaluate(ProviderKind.SHIPPING).usableShipping()

    override fun state(kind: ProviderKind, id: String): ProviderState {
        val rows = listing(kind).filter { it.id == id }
        return rows.firstOrNull { it.state.usable }?.state ?: rows.firstOrNull()?.state ?: MISSING
    }

    override fun listing(kind: ProviderKind): List<ProviderListing> = evaluate(kind).listing

    // ---- evaluation

    private class Evaluation(val listing: List<ProviderListing>) {
        @Suppress("UNCHECKED_CAST")
        fun usablePayment() = listing.filter { it.state.usable }.map { it.resolved as ResolvedProvider<PaymentProvider> }

        @Suppress("UNCHECKED_CAST")
        fun usableShipping() = listing.filter { it.state.usable }.map { it.resolved as ResolvedProvider<ShippingProvider> }
    }

    private fun evaluate(kind: ProviderKind): Evaluation {
        val listing = ArrayList<ProviderListing>()
        val claimed = HashMap<String, ResolvedProvider<Any>>()

        fun consider(provider: Any, origin: ProviderOrigin, pluginId: String?, spiVersion: Int, compatible: Boolean) {
            val id = try {
                idOf(kind, provider)
            } catch (e: Throwable) {
                rethrowFatal(e)
                report("$kind:${pluginId ?: "built-in"}:id", "A $kind provider of ${pluginId ?: "market"} threw while its id was read: ${e.javaClass.simpleName}")
                listing.add(ProviderListing(kind, null, ProviderState(ProviderAvailability.INVALID, spiVersion, pluginId, "reading the provider id failed"), null))
                return
            }
            if (!ID.matches(id)) {
                listing.add(ProviderListing(kind, id, ProviderState(ProviderAvailability.INVALID, spiVersion, pluginId, "invalid provider id"), null))
                return
            }
            if (!compatible) {
                listing.add(
                    ProviderListing(kind, id, ProviderState(ProviderAvailability.INCOMPATIBLE, spiVersion, pluginId, "INCOMPATIBLE (built for SPI $spiVersion)"), null)
                )
                return
            }
            val holder = claimed[id]
            if (holder != null) {
                val by = if (holder.origin == ProviderOrigin.BUILT_IN) "the built-in provider" else "plugin ${holder.pluginId}"
                listing.add(ProviderListing(kind, id, ProviderState(ProviderAvailability.SHADOWED, spiVersion, pluginId, "id is already provided by $by"), null))
                return
            }
            val resolved = ResolvedProvider(id, provider, origin, pluginId, spiVersion)
            claimed[id] = resolved
            listing.add(ProviderListing(kind, id, ProviderState(ProviderAvailability.AVAILABLE, spiVersion, pluginId), resolved))
        }

        for (builtIn in builtIns(kind)) consider(builtIn, ProviderOrigin.BUILT_IN, null, MarketSpi.VERSION, true)

        for (pe in started()) {
            val spi = try {
                pe.extension.spiVersion
            } catch (e: Throwable) {
                rethrowFatal(e)
                report("spi:${pe.pluginId}", "Plugin ${pe.pluginId} threw while its SPI version was read: ${e.javaClass.simpleName}")
                listing.add(ProviderListing(kind, null, ProviderState(ProviderAvailability.INVALID, null, pe.pluginId, "reading the SPI version failed"), null))
                continue
            }
            val providers = try {
                providersOf(kind, pe.extension)
            } catch (e: Throwable) {
                rethrowFatal(e)
                report("providers:$kind:${pe.pluginId}", "Plugin ${pe.pluginId} threw while its $kind providers were read: ${e.javaClass.simpleName}")
                val availability = if (spi in SUPPORTED) ProviderAvailability.INVALID else ProviderAvailability.INCOMPATIBLE
                val detail = if (availability == ProviderAvailability.INCOMPATIBLE) "INCOMPATIBLE (built for SPI $spi)" else "reading the providers failed"
                listing.add(ProviderListing(kind, null, ProviderState(availability, spi, pe.pluginId, detail), null))
                continue
            }
            for (provider in providers) consider(provider, ProviderOrigin.PLUGIN, pe.pluginId, spi, spi in SUPPORTED)
        }
        return Evaluation(listing)
    }

    private fun builtIns(kind: ProviderKind): List<Any> = try {
        when (kind) {
            ProviderKind.PAYMENT -> builtInPayment()
            ProviderKind.SHIPPING -> builtInShipping()
        }
    } catch (e: Throwable) {
        rethrowFatal(e)
        report("builtins:$kind", "Reading the built-in $kind providers failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun providersOf(kind: ProviderKind, extension: MarketExtension): List<Any> = when (kind) {
        ProviderKind.PAYMENT -> extension.paymentProviders()
        ProviderKind.SHIPPING -> extension.shippingProviders()
    }

    private fun idOf(kind: ProviderKind, provider: Any): String = when (kind) {
        ProviderKind.PAYMENT -> (provider as PaymentProvider).id
        ProviderKind.SHIPPING -> (provider as ShippingProvider).id
    }

    // ---- reading the bus

    private fun snapshot(): List<PluginExtension> {
        repeat(2) {
            try {
                return source.extensions().also { lastSnapshot = it }
            } catch (e: ConcurrentModificationException) {
                // retry once, then fall back to the last good snapshot
            } catch (e: Exception) {
                report("source", "Reading the registered extensions failed: ${e.javaClass.simpleName}")
                return lastSnapshot
            }
        }
        return lastSnapshot
    }

    private fun started(): List<PluginExtension> =
        snapshot().filter { isStarted(it.pluginId) }.sortedBy { it.pluginId }

    private fun isStarted(pluginId: String): Boolean = try {
        pluginState(pluginId) == PluginState.STARTED
    } catch (e: Exception) {
        false
    }

    private fun compatible(pe: PluginExtension): Boolean = try {
        pe.extension.spiVersion in SUPPORTED
    } catch (e: Exception) {
        false
    }

    private fun report(key: String, message: String) {
        if (reported.add("$key|$message")) logger.warn(message)
    }

    private fun rethrowFatal(e: Throwable) {
        if (e is VirtualMachineError) throw e
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProviderRegistry::class.java)
        private val ID = Regex("^[a-z0-9-]{2,32}$")
        private val SUPPORTED = MarketSpi.MIN_SUPPORTED..MarketSpi.VERSION
        private val MISSING = ProviderState(ProviderAvailability.MISSING, detail = "no provider is registered under this id")

        /** The registry on the real plugin event bus; the plugin state comes from [pluginManager]. */
        fun forPlatform(
            pluginManager: PluginManager,
            builtInPayment: () -> List<PaymentProvider> = { emptyList() },
            builtInShipping: () -> List<ShippingProvider> = { emptyList() }
        ): ProviderRegistry = ProviderRegistry(
            PlatformExtensionSource(),
            { id -> pluginManager.getPlugin(id)?.pluginState },
            builtInPayment,
            builtInShipping
        )
    }
}
