package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Discounts, coupons, creator codes (not payouts), gifts, redemption lists (04 section 9). */
@PermissionDefinition
class ManageMarketDiscountsPermission : PanelPermission("fa-tags")
