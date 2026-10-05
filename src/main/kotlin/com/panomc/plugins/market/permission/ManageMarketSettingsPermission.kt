package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Settings, currencies, legal text, payment providers and their secrets, shipping setup, webhooks, health (04 section 9). */
@PermissionDefinition
class ManageMarketSettingsPermission : PanelPermission("fa-gear")
