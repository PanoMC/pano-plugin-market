package com.panomc.plugins.market.util

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.frontend.CoreFrontendTargets
import com.panomc.platform.frontend.FrontendUrlInputs
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.frontend.ThemeRouteMap
import com.panomc.platform.ui.FrontendMode
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The front-end pages market links to (doc 05 section 10.2): a mail button, a payment return redirect, an order link in a
 * webhook, the address a gateway sends the buyer back to, the product address of the Minecraft component. Every method asks
 * the front-end URL map, so a theme that renamed `/store/order/[id]` or a front-end that took the page over is followed.
 * `null` means "no such page" (a disabled route): the caller leaves the link out.
 */
interface StoreLinks {
    fun store(): String?

    fun product(slug: String): String?

    fun checkout(): String?

    /** The order page; [query] is appended as URL-encoded query parameters (`token` of a guest order, `return` of a payment return). */
    fun order(publicId: String, query: Map<String, String> = emptyMap()): String?

    fun subscriptions(): String?

    /** `user.profile` of core. */
    fun profile(): String?

    /** `auth.register` of core. */
    fun register(): String?

    /** `market.product` with `{slug}` left in, for the Minecraft component. */
    fun productTemplate(): String?

    /** The page a gateway is given as the checkout address: the checkout, else the store, else [siteBase]. */
    fun checkoutPage(siteBase: String): String = checkout() ?: store() ?: siteBase

    /** The page a gateway is given as the order address: the order, else the store, else [siteBase]. */
    fun orderPage(publicId: String, siteBase: String): String = order(publicId) ?: store() ?: siteBase

    companion object {
        /** No page at all: every link is left out. */
        val NONE: StoreLinks = object : StoreLinks {
            override fun store(): String? = null
            override fun product(slug: String): String? = null
            override fun checkout(): String? = null
            override fun order(publicId: String, query: Map<String, String>): String? = null
            override fun subscriptions(): String? = null
            override fun profile(): String? = null
            override fun register(): String? = null
            override fun productTemplate(): String? = null
        }

        /**
         * The links of a site that changed nothing: [base] and the default paths of `frontend-targets.json` (and of core). An empty [base] is
         * no site: every link is left out. For tests and for code that has only an address.
         */
        fun ofBase(base: String): StoreLinks {
            val trimmed = base.trim().trimEnd('/')

            return if (trimmed.isEmpty()) NONE else byBase.computeIfAbsent(trimmed) { forMap(DefaultSite.map(it)) }
        }

        /** The links over a URL map: the platform's own, or one a test fills with an override or a renamed route. */
        fun forMap(map: FrontendUrlMap): StoreLinks = UrlMapLinks({ target, params -> map.url(target, params) }, { map.template(it) })

        private val byBase = ConcurrentHashMap<String, StoreLinks>()
    }
}

/** The target ids of `frontend-targets.json` (the namespace of the plugin is `market`) and the two core targets market links to. */
object MarketTargets {
    const val NAMESPACE = "market"
    const val PLUGIN_ID = MarketPaths.PLUGIN_ID

    const val STORE = "$NAMESPACE.store"
    const val PRODUCT = "$NAMESPACE.product"
    const val CHECKOUT = "$NAMESPACE.checkout"
    const val ORDER = "$NAMESPACE.order"
    const val SUBSCRIPTIONS = "$NAMESPACE.subscriptions"
    const val PROFILE = CoreFrontendTargets.USER_PROFILE
    const val REGISTER = CoreFrontendTargets.AUTH_REGISTER

    /** The file the platform reads when the plugin loads. */
    const val FILE = "frontend-targets.json"

    /** The text of [FILE] as it is in the jar. */
    fun fileText(): String = checkNotNull(MarketTargets::class.java.classLoader.getResourceAsStream(FILE)) { "$FILE is missing from the plugin" }
        .use { it.readBytes().toString(Charsets.UTF_8) }
}

/** [StoreLinks] over `url(target, params)` and `template(target)`; a link that is not an absolute `http(s)` address is no link. */
class UrlMapLinks(
    private val url: (target: String, params: Map<String, String>) -> String?,
    private val template: (target: String) -> String?
) : StoreLinks {
    override fun store() = resolve(MarketTargets.STORE)

    override fun product(slug: String) = resolve(MarketTargets.PRODUCT, mapOf("slug" to slug))

    override fun checkout() = resolve(MarketTargets.CHECKOUT)

    override fun order(publicId: String, query: Map<String, String>) = resolve(MarketTargets.ORDER, mapOf("id" to publicId) + query)

    override fun subscriptions() = resolve(MarketTargets.SUBSCRIPTIONS)

    override fun profile() = resolve(MarketTargets.PROFILE)

    override fun register() = resolve(MarketTargets.REGISTER)

    override fun productTemplate(): String? = try {
        template(MarketTargets.PRODUCT)?.takeIf { isWebUrl(it) }
    } catch (e: Exception) {
        null
    }

    private fun resolve(target: String, params: Map<String, String> = emptyMap()): String? = try {
        url(target, params)?.takeIf { isWebUrl(it) }
    } catch (e: Exception) {
        // a link never breaks the mail, the redirect or the payload it sits in
        null
    }

    private fun isWebUrl(value: String) = value.startsWith("http://", true) || value.startsWith("https://", true)
}

/** The links the running platform answers with; the URL map is looked up on first use. */
object MarketLinks {
    val platform: StoreLinks by lazy {
        UrlMapLinks(
            { target, params -> map().url(target, params) },
            { target -> map().template(target) }
        )
    }

    private fun map(): FrontendUrlMap = applicationContext.getBean(FrontendUrlMap::class.java)
}

/** A URL map of a site with nothing changed: a plain `THEME` front-end at one address, the targets of the plugin registered. */
internal object DefaultSite {
    private val logger = LoggerFactory.getLogger("Market:Links")

    fun map(base: String): FrontendUrlMap {
        val inputs = object : FrontendUrlInputs {
            override fun mode() = FrontendMode.THEME
            override fun siteUrl() = base
            override fun websiteUrl() = base
            override fun overrides(): Map<String, String> = emptyMap()
            override fun frontendUrls(): Map<String, Any?> = emptyMap()
            override fun themeRoutes(): ThemeRouteMap? = null
        }

        return FrontendUrlMap(inputs, logger).also { it.registerPlugin(MarketTargets.PLUGIN_ID, MarketTargets.NAMESPACE, MarketTargets.fileText()) }
    }
}
