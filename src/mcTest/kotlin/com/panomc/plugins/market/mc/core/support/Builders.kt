package com.panomc.plugins.market.mc.core.support

import com.panomc.plugins.market.mc.core.store.DeliveryRecord
import com.panomc.plugins.market.mc.core.store.PlayerIdentity
import com.panomc.plugins.market.mc.core.store.RecordState
import com.panomc.plugins.market.mc.core.wire.DeliveryDisplay
import com.panomc.plugins.market.mc.core.wire.DeliveryKind
import com.panomc.plugins.market.mc.core.wire.DeliveryPermission
import com.panomc.plugins.market.mc.core.wire.DeliveryPlayer
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.SyncBroadcast
import com.panomc.plugins.market.mc.core.wire.SyncDelivery

fun delivery(
    key: String,
    id: Long,
    player: String = "Steve",
    commands: List<String> = listOf("give $player diamond 1"),
    requiresOnline: Boolean = false,
    expiresAt: Long? = null,
    kind: String = DeliveryKind.COMMAND,
    permission: DeliveryPermission? = null,
    uuid: String? = null,
    display: DeliveryDisplay? = DeliveryDisplay("Diamonds", "ORD$id", false, null),
    phase: String = "GRANT"
) = SyncDelivery(
    key = key, id = id, kind = kind, phase = phase, player = DeliveryPlayer(player, uuid),
    requiresOnline = requiresOnline, expiresAt = expiresAt, issuer = "market:order-$id",
    commands = if (kind == DeliveryKind.COMMAND) commands else emptyList(), permission = permission, display = display
)

fun permissionDelivery(key: String, id: Long, player: String = "Steve", op: String = "ADD", nodes: List<String> = listOf("group.vip"), expiresAt: Long? = null) =
    delivery(key, id, player, kind = DeliveryKind.PERMISSION, permission = DeliveryPermission(op, nodes, expiresAt))

fun response(
    deliveries: List<SyncDelivery> = emptyList(),
    acked: List<String> = emptyList(),
    cancel: List<String> = emptyList(),
    broadcasts: List<SyncBroadcast> = emptyList(),
    accepted: Boolean = true,
    reason: String? = null,
    marketVersion: String? = "1.4.0",
    configHash: String? = null,
    pollAfterMs: Long = 5000
) = MarketSyncMessage(
    accepted = accepted, reason = reason, marketVersion = marketVersion, acked = acked, deliveries = deliveries,
    cancel = cancel, broadcasts = broadcasts, configHash = configHash, pollAfterMs = pollAfterMs
)

fun record(key: String, id: Long, player: String = "Steve", receivedAt: Long = 1_790_000_000_000L, commands: List<String> = listOf("say hi")) = DeliveryRecord(
    key = key, id = id, kind = DeliveryKind.COMMAND, phase = "GRANT", player = PlayerIdentity(player), requiresOnline = false,
    expiresAt = null, issuer = "market:order-$id", commands = commands, permission = null, display = null,
    state = RecordState.QUEUED, result = null, receivedAt = receivedAt
)
