package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Everything that moves value: refunds, mark paid, review, disputes, manual orders, credits, payouts (04 section 9). */
@PermissionDefinition
class ManageMarketPaymentsPermission : PanelPermission("fa-money-bill-transfer")
