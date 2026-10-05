package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Categories, products, variants, bundles, comparisons, goals, stock (04 section 9). */
@PermissionDefinition
class ManageMarketCatalogPermission : PanelPermission("fa-boxes-stacked")
