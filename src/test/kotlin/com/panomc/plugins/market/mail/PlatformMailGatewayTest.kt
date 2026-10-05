package com.panomc.plugins.market.mail

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets

/** The X-4 adapter (MK-141; 12 section 3.2): what a send hands to `MailManager`, and that nothing else touches `MailOptions`. */
class PlatformMailGatewayTest {
    private fun message(replyTo: String? = "support@shop.example") = OutboundMail(
        recipient = "guest@example.com",
        locale = "tr",
        content = MailContent(subject = "Siparisiniz alindi", preheader = "p", heading = "Tesekkurler", paragraphs = listOf("Merhaba")),
        replyTo = replyTo,
        attachments = listOf(MailAttachment("INV-2026-000001.pdf", "application/pdf", byteArrayOf(37, 80, 68, 70)))
    )

    @Test
    fun `the options carry the recipient, the locale, the translated subject, the text part, the reply-to and the files`() {
        val o = PlatformMailGateway.toOptions(message())
        assertEquals("guest@example.com", o.email)
        assertEquals("tr", o.locale)
        assertEquals("Siparisiniz alindi", o.subject)
        assertEquals(message().content.toText(), o.text)
        assertEquals("support@shop.example", o.replyTo)
        val file = o.attachments.single()
        assertEquals("INV-2026-000001.pdf", file.name)
        assertEquals("application/pdf", file.contentType)
        assertEquals(listOf<Byte>(37, 80, 68, 70), file.data.toList())
    }

    @Test
    fun `a blank reply-to is not sent and no attachment gives an empty list`() {
        val o = PlatformMailGateway.toOptions(
            OutboundMail("a@example.com", "en-US", MailContent(subject = "s", preheader = "p", heading = "h"), replyTo = "  ")
        )
        assertNull(o.replyTo)
        assertTrue(o.attachments.isEmpty())
        assertNull(PlatformMailGateway.toOptions(message(replyTo = null)).replyTo)
    }

    @Test
    fun `the stand-in gateway refuses to send`(): Unit = runBlocking {
        val e = assertThrows(IllegalStateException::class.java) { runBlocking { UnavailableMailGateway.send(message()) } }
        assertTrue(e.message!!.contains("HOST_TOO_OLD"))
    }

    @Test
    fun `only the gateway class references MailOptions`() {
        val root = File(PlatformMailGateway::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(root.isDirectory, "expected the compiled classes directory, got $root")
        val needle = "com/panomc/platform/mail/MailOptions".toByteArray(StandardCharsets.UTF_8)
        val referencing = root.walkTopDown().filter { it.isFile && it.extension == "class" }
            .filter { indexOf(it.readBytes(), needle) >= 0 }
            .map { it.name }.sorted().toList()
        assertTrue(referencing.isNotEmpty())
        assertTrue(referencing.all { it.startsWith("PlatformMailGateway") }, "MailOptions is referenced by $referencing")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
