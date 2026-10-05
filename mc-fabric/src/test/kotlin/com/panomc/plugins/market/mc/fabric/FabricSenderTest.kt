package com.panomc.plugins.market.mc.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The sender decision of [FabricSender] (19 section 7.4): `/execute as`, command blocks, functions and RCON never lend an identity. */
class FabricSenderTest {
    private val server = Any()
    private val admin = Any()
    private val adminSource = Any()
    private val me = Any()
    private val mySource = Any()
    private val commandBlock = Any()

    private fun classify(stackSource: Any?, entity: Any?, player: Any?, playerSource: Any?, server: Any? = this.server) =
        FabricSender.classify(stackSource, entity, player, playerSource, server)

    @Test
    fun `a player typing a command is that player`() {
        assertEquals(SenderKind.PLAYER, classify(mySource, me, me, mySource))
    }

    @Test
    fun `the server console is the console`() {
        assertEquals(SenderKind.CONSOLE, classify(server, null, null, null))
    }

    @Test
    fun `execute as an online admin typed by another player is refused - the entity is replaced, the source is not`() {
        // `execute as <admin> run panomarket credits give ...` typed by me: entity = admin, stack source = my command source.
        assertEquals(SenderKind.UNSUPPORTED, classify(mySource, admin, admin, adminSource))
    }

    @Test
    fun `execute as a player run from the console is refused`() {
        assertEquals(SenderKind.UNSUPPORTED, classify(server, admin, admin, adminSource))
    }

    @Test
    fun `a command block or function with a player entity is refused`() {
        assertEquals(SenderKind.UNSUPPORTED, classify(commandBlock, admin, admin, adminSource))
        assertEquals(SenderKind.UNSUPPORTED, classify(commandBlock, null, null, null))
    }

    @Test
    fun `the server source with a non-player entity is no console`() {
        assertEquals(SenderKind.UNSUPPORTED, classify(server, Any(), null, null))
    }

    @Test
    fun `a player stack whose entity is not the player is refused`() {
        assertEquals(SenderKind.UNSUPPORTED, classify(mySource, null, me, mySource))
        assertEquals(SenderKind.UNSUPPORTED, classify(mySource, admin, me, mySource))
    }

    @Test
    fun `an unreadable source or a missing server fails closed`() {
        assertEquals(SenderKind.UNSUPPORTED, classify(null, me, me, mySource))
        assertEquals(SenderKind.UNSUPPORTED, classify(null, null, null, null))
        assertEquals(SenderKind.UNSUPPORTED, classify(server, null, null, null, server = null))
        assertEquals(SenderKind.UNSUPPORTED, classify(mySource, me, me, null))
    }
}
