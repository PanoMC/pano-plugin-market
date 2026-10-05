package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.market.mc.core.platform.DeliverySettings

/** Until the effective config of MC-05 is wired: every local switch is on (the delivery path itself is the product). */
object DefaultDeliverySettings : DeliverySettings {
    override val deliveriesEnabled: Boolean = true
    override val luckPermsEnabled: Boolean = true
    override val broadcastEnabled: Boolean = true
}
