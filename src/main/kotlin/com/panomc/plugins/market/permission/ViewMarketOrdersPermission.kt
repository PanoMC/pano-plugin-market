package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Read orders, deliveries, shipments, subscriptions, payment events, CSV export, player summary; never changes state (04 section 9). */
@PermissionDefinition
class ViewMarketOrdersPermission : PanelPermission("fa-receipt")
