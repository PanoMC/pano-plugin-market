package com.panomc.plugins.market.permission

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.Permission
import io.vertx.ext.web.RoutingContext

/**
 * The one place a market panel route is authorised (04 section 9, 11 section 14.2): any of the given nodes, or the
 * umbrella [ManageMarketPermission] (platform admins and `*` holders pass `hasPermission` themselves).
 */
object MarketPermissions {
    /** The permissions that satisfy [nodes]: those nodes plus the umbrella; no nodes = any market node. */
    fun accepted(nodes: Set<MarketNode>): List<Permission> {
        val granular = if (nodes.isEmpty()) MarketNode.values().toList() else nodes.sortedBy { it.ordinal }

        return granular.map { it.permission } + ManageMarketPermission()
    }

    /** Throws the platform `NoPermission` (403) unless the caller holds one of [nodes] or the umbrella. */
    suspend fun require(context: RoutingContext, nodes: Set<MarketNode>) {
        authProvider().requireAnyPermission(context, *accepted(nodes).toTypedArray())
    }

    suspend fun has(context: RoutingContext, nodes: Set<MarketNode>): Boolean {
        val authProvider = authProvider()

        return accepted(nodes).any { authProvider.hasPermission(it, context) }
    }

    private fun authProvider(): AuthProvider = applicationContext.getBean(AuthProvider::class.java)
}
