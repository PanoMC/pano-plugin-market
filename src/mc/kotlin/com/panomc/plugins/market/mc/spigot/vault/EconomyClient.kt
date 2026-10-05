package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.feature.GameLink
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.core.wire.MarketEconomyMessage
import com.panomc.plugins.market.mc.core.wire.MarketEconomyRequest
import com.panomc.plugins.market.mc.core.wire.MarketWire
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.core.wire.RefusalReason

/**
 * What Pano said to one `MARKET_ECONOMY` request (19 section 7.5), reduced to what the bridge decides on. The one
 * distinction that matters for money is between "the ledger did / did not apply it" and "not known":
 * - [Ok]: applied (`ok = true`), [balance] is the player's credit balance afterwards;
 * - [Refused]: Pano answered and did NOT apply it (`ok = false` with a `code`: `INSUFFICIENT_CREDITS`, `NO_ACCOUNT`,
 *   `CREDITS_DISABLED`, ...; also an `accepted = false` with a reason that is not a transient one, e.g. the vault mode
 *   being off for this server);
 * - [Transient]: Pano did not take the request now (`MARKET_NOT_READY`, `RATE_LIMITED`, `VERSION_MISMATCH`,
 *   `PROTOCOL_UNSUPPORTED`): nothing was applied by THIS attempt, a later attempt may work;
 * - [Unknown]: no usable answer (timeout, not connected, undecodable): the request may or may not have been applied.
 */
sealed class EconomyAnswer {
    data class Ok(val balance: Double?) : EconomyAnswer()
    data class Refused(val code: String, val balance: Double?) : EconomyAnswer()
    data class Transient(val reason: String) : EconomyAnswer()
    object Unknown : EconomyAnswer()

    companion object {
        private val TRANSIENT = setOf(
            RefusalReason.MARKET_NOT_READY, RefusalReason.RATE_LIMITED, RefusalReason.VERSION_MISMATCH, RefusalReason.PROTOCOL_UNSUPPORTED
        )

        fun classify(m: MarketEconomyMessage?): EconomyAnswer = when {
            m == null -> Unknown
            !m.accepted -> {
                val reason = m.reason
                if (reason == null || reason in TRANSIENT) Transient(reason ?: "UNKNOWN") else Refused(reason, null)
            }
            m.ok == true -> Ok(m.balance)
            m.ok == false -> Refused(m.code ?: "UNKNOWN", m.balance)
            else -> Unknown // accepted, but neither ok nor refused: never guess about money
        }
    }
}

/** `MARKET_ECONOMY` over the one Pano connection. Callback based: the callback runs exactly once, on any thread. */
class EconomyClient(private val link: GameLink, private val componentVersion: String, private val log: McLog) {
    fun connected(): Boolean = try {
        link.connected()
    } catch (_: Throwable) {
        false
    }

    fun send(op: String, player: PlayerRef, amount: Double?, operationId: String, reason: String, callback: (EconomyAnswer) -> Unit) {
        val request = MarketEconomyRequest(componentVersion, MarketWire.PROTOCOL, operationId, op, player, amount, reason)
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            link.request(request, MarketEconomyMessage::class.java) { m -> if (once.compareAndSet(false, true)) callback(EconomyAnswer.classify(m)) }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            log.warn("MARKET_ECONOMY $op could not be sent: ${e.message}")
            if (once.compareAndSet(false, true)) callback(EconomyAnswer.Unknown)
        }
    }

    fun balance(player: PlayerRef, callback: (EconomyAnswer) -> Unit) =
        send(EconomyOp.BALANCE, player, null, java.util.UUID.randomUUID().toString(), "vault balance", callback)
}
