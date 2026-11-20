package com.panomc.plugins.market.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McPushTest {
    private val sent = ArrayList<Pair<Long, MarketWakeMessage>>()
    private var now = 1_000_000L

    private fun push(connected: List<Long> = listOf(1, 2)) =
        McPush(vertx = { null }, send = { id, m -> sent += id to m; true }, connectedServers = { connected }, now = { now })

    @Test
    fun `a wake goes to its server with the reasons and players, a wakeAll to every connected server`() {
        val p = push()
        p.wake(7, McPush.SYNC)
        p.wakeAll(McPush.BALANCE, McPush.PLACEHOLDERS, players = listOf(" Steve ", ""))

        assertEquals(listOf(7L, 1L, 2L), sent.map { it.first })
        assertEquals(MarketWakeMessage(listOf("SYNC"), null), sent[0].second)
        assertEquals(MarketWakeMessage(listOf("BALANCE", "PLACEHOLDERS"), listOf("Steve")), sent[1].second)
    }

    @Test
    fun `nothing is sent for no server or no reason`() {
        val p = push()
        p.wake(0, McPush.SYNC)
        p.wake(5)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a row due now wakes at once, one due far away is left to the heartbeat`() {
        val p = push()
        p.wakeAt(3, now - 1)
        p.wakeAt(4, now + McPush.MAX_TIMER_MS + 1)
        assertEquals(listOf(3L), sent.map { it.first })
    }

    @Test
    fun `a failing send never throws to the caller`() {
        val p = McPush(vertx = { null }, send = { _, _ -> throw IllegalStateException("socket closed") }, connectedServers = { listOf(1) })
        p.wake(1, McPush.CONFIG)
        p.wake(1, McPush.CONFIG)
    }

    @Test
    fun `the event name is MARKET_WAKE`() {
        assertEquals("MARKET_WAKE", MarketWakeMessage(listOf("SYNC")).getResponseName())
    }
}
