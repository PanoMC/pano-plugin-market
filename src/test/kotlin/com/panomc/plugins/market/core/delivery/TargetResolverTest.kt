package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliverySourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TargetResolverTest {
    private fun cmd(mode: ServerMode, targets: List<Long> = emptyList()) =
        ProductAction("a1", DeliveryActionType.COMMAND, serverMode = mode, targetServers = targets, commands = listOf("say hi"))

    private val lookup = TargetResolver.ServerLookup(grantedIds = listOf(1L, 2L, 5L), existingIds = listOf(1L, 2L, 3L, 5L), connectedIds = listOf(5L, 2L))

    @Test
    fun `inline actions and PERMISSION via PANO produce one inline row`() {
        val credit = ProductAction("a1", DeliveryActionType.CREDIT, credit = 100)
        val panoPerm = ProductAction("a2", DeliveryActionType.PERMISSION, nodes = listOf("x"), serverMode = ServerMode.FIXED, targetServers = listOf(1L))
        val hook = ProductAction("a3", DeliveryActionType.WEBHOOK)

        for (a in listOf(credit, panoPerm, hook)) {
            val t = TargetResolver.servers(a, TargetResolver.ItemTarget(), lookup)
            assertEquals(listOf(0L), t.serverIds)
            assertFalse(t.failed)
        }
    }

    @Test
    fun `FIXED with targets gives those ids, skipping removed servers`() {
        assertEquals(listOf(2L, 1L), TargetResolver.servers(cmd(ServerMode.FIXED, listOf(2, 1, 2)), TargetResolver.ItemTarget(), lookup).serverIds)
        val partial = TargetResolver.servers(cmd(ServerMode.FIXED, listOf(1, 99)), TargetResolver.ItemTarget(), lookup)
        assertEquals(listOf(1L), partial.serverIds)
        assertFalse(partial.failed)
    }

    @Test
    fun `FIXED with every target gone is one FAILED row on server 0`() {
        val t = TargetResolver.servers(cmd(ServerMode.FIXED, listOf(98, 99)), TargetResolver.ItemTarget(), lookup)
        assertEquals(listOf(0L), t.serverIds)
        assertEquals("NO_TARGET_SERVER", t.errorCode)
        assertTrue(t.failed)
    }

    @Test
    fun `FIXED with an empty list means every server with permissionGranted`() {
        assertEquals(listOf(1L, 2L, 5L), TargetResolver.servers(cmd(ServerMode.FIXED), TargetResolver.ItemTarget(), lookup).serverIds)
        val none = TargetResolver.servers(cmd(ServerMode.FIXED), TargetResolver.ItemTarget(), TargetResolver.ServerLookup())
        assertEquals("NO_TARGET_SERVER", none.errorCode)
        assertEquals(listOf(0L), none.serverIds)
    }

    @Test
    fun `BUYER_CHOICE uses the item's server when it is one of the snapshot choices`() {
        val choices = listOf(1L, 2L)
        assertEquals(listOf(2L), TargetResolver.servers(cmd(ServerMode.BUYER_CHOICE), TargetResolver.ItemTarget(2, choices), lookup).serverIds)
        for (bad in listOf<Long?>(null, 0L, 5L, 99L)) {
            val t = TargetResolver.servers(cmd(ServerMode.BUYER_CHOICE), TargetResolver.ItemTarget(bad, choices), lookup)
            assertEquals("NO_TARGET_SERVER", t.errorCode, "$bad")
            assertEquals(listOf(0L), t.serverIds)
        }
        assertEquals("NO_TARGET_SERVER", TargetResolver.servers(cmd(ServerMode.BUYER_CHOICE), TargetResolver.ItemTarget(1, emptyList()), lookup).errorCode)
        // the action's own targetServers never override the buyer's choice
        assertEquals(listOf(2L), TargetResolver.servers(cmd(ServerMode.BUYER_CHOICE, listOf(1)), TargetResolver.ItemTarget(2, choices), lookup).serverIds)
    }

    @Test
    fun `ALL_CONNECTED is the servers connected at plan time, none is a FAILED row`() {
        assertEquals(listOf(2L, 5L), TargetResolver.servers(cmd(ServerMode.ALL_CONNECTED), TargetResolver.ItemTarget(), lookup).serverIds)
        val none = TargetResolver.servers(cmd(ServerMode.ALL_CONNECTED), TargetResolver.ItemTarget(), TargetResolver.ServerLookup(grantedIds = listOf(1L)))
        assertEquals("NO_TARGET_SERVER", none.errorCode)
    }

    @Test
    fun `PERMISSION via SERVER is resolved like a command`() {
        val perm = ProductAction("a1", DeliveryActionType.PERMISSION, via = PermissionVia.SERVER, nodes = listOf("x"), serverMode = ServerMode.FIXED, targetServers = listOf(3L))
        assertEquals(listOf(3L), TargetResolver.servers(perm, TargetResolver.ItemTarget(), lookup).serverIds)
    }

    @Test
    fun `the player of an order item is the recipient`() {
        val p = TargetResolver.player(DeliverySourceType.ORDER_ITEM, TargetResolver.Parties("Steve", "uuid-1", "Payer", 7, "uuid-2"))
        assertEquals("Steve", p.username)
        assertEquals("uuid-1", p.uuidHint)
        assertFalse(p.needsConfirmation)
        assertTrue(p.valid)
    }

    @Test
    fun `a chargeback action targets the payer of an account order and the recipient of a guest order`() {
        val account = TargetResolver.player(DeliverySourceType.CHARGEBACK_ACTION, TargetResolver.Parties("Gift_Target", "u1", "Payer", 7, "u2"))
        assertEquals("Payer", account.username)
        assertEquals("u2", account.uuidHint)
        assertFalse(account.needsConfirmation)

        val guest = TargetResolver.player(DeliverySourceType.CHARGEBACK_ACTION, TargetResolver.Parties("Gift_Target", "u1", "Mallory", null, null))
        assertEquals("Gift_Target", guest.username)
        assertTrue(guest.needsConfirmation)

        val self = TargetResolver.player(DeliverySourceType.CHARGEBACK_ACTION, TargetResolver.Parties("Steve", null, "steve", null, null))
        assertEquals("Steve", self.username)
        assertFalse(self.needsConfirmation)
    }

    @Test
    fun `a creator payout targets the creator and a bad name is not valid`() {
        val p = TargetResolver.player(DeliverySourceType.CREATOR_PAYOUT, TargetResolver.Parties("ignored"), creator = " Creator1 ")
        assertEquals("Creator1", p.username)
        assertNull(p.uuidHint)
        assertTrue(p.valid)
        assertFalse(TargetResolver.player(DeliverySourceType.ORDER_ITEM, TargetResolver.Parties("Ste ve; op")).valid)
        assertFalse(TargetResolver.player(DeliverySourceType.ORDER_ITEM, TargetResolver.Parties("")).valid)
        assertFalse(TargetResolver.player(DeliverySourceType.CREATOR_PAYOUT, TargetResolver.Parties("x"), creator = null).valid)
        assertTrue(TargetResolver.player(DeliverySourceType.ORDER_ITEM, TargetResolver.Parties(".BedrockUser")).valid)
    }

    @Test
    fun `a credit needs a real account`() {
        assertEquals(5L, TargetResolver.creditAccountUserId(5, "Steve") { error("no lookup when the order has the user") })
        assertEquals(9L, TargetResolver.creditAccountUserId(null, "Steve") { if (it == "Steve") 9L else null })
        assertNull(TargetResolver.creditAccountUserId(null, "Nobody") { null })
        assertNull(TargetResolver.creditAccountUserId(0, "Nobody") { null })
    }
}
