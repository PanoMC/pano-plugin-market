package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** The stats page (04 section 9). */
@PermissionDefinition
class ViewMarketStatsPermission : PanelPermission("fa-chart-line")
