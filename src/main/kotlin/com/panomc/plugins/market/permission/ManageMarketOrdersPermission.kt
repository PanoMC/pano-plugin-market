package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Fulfilment: deliveries, revoke, shipments, mail resend, notes, block list, status re-check (04 section 9). */
@PermissionDefinition
class ManageMarketOrdersPermission : PanelPermission("fa-truck-fast")
