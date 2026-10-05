package com.panomc.plugins.market.mc.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class FabricPermissionsTest {
    private val id = UUID.randomUUID()
    private val node = "panomarket.admin.credits.give"

    private fun perms(lookup: NodeLookup?) = FabricPermissions(lookup) { false }

    @Test
    fun `without a permission mod only an administrator passes`() {
        assertTrue(perms(null).decide(id, node) { true })
        assertFalse(perms(null).decide(id, node) { false })
    }

    @Test
    fun `a permission mod that grants the node wins over a non administrator`() {
        assertTrue(perms { _, _ -> true }.decide(id, node) { false })
    }

    @Test
    fun `a permission mod that denies the node wins over an operator`() {
        assertFalse(perms { _, _ -> false }.decide(id, node) { true })
    }

    @Test
    fun `an undefined node falls back to the operator default`() {
        assertTrue(perms { _, _ -> null }.decide(id, node) { true })
        assertFalse(perms { _, _ -> null }.decide(id, node) { false })
    }

    @Test
    fun `a failing permission mod is no opinion, not a grant`() {
        assertFalse(perms { _, _ -> throw IllegalStateException("LuckPerms not loaded") }.decide(id, node) { false })
        assertFalse(perms { _, _ -> throw NoClassDefFoundError("net/luckperms/api/LuckPermsProvider") }.decide(id, node) { false })
    }

    @Test
    fun `the lookup gets the player and the node`() {
        val seen = ArrayList<Pair<UUID, String>>()
        perms { u, n -> seen.add(u to n); null }.decide(id, node) { false }
        assertEquals(listOf(id to node), seen)
    }
}
