package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.spi.common.LocalizedText
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProviderTextsTest {
    @Test
    fun `a key text of a gateway is read from the gateway's own locale files, an unknown locale or key falls back to English`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("locales"))
        Files.writeString(dir.resolve("locales/tr.json"), """{"descriptor":{"description":"iyzico ile kartla ödeyin","hint":"Güvenli ödeme"}}""")
        Files.writeString(dir.resolve("locales/ru.json"), """{"descriptor":{"description":"Оплата картой через iyzico"}}""")

        val loader = URLClassLoader(arrayOf(dir.toUri().toURL()), null)
        val description = LocalizedText.key("plugins.pano-plugin-market-iyzico.descriptor.description", "Pay by card with iyzico")
        val hint = LocalizedText.key("plugins.pano-plugin-market-iyzico.descriptor.hint", "Secure payment")
        val missing = LocalizedText.key("plugins.pano-plugin-market-iyzico.descriptor.name", "iyzico")

        assertEquals("iyzico ile kartla ödeyin", ProviderTexts.resolveIn(description, loader, "tr"))
        assertEquals("iyzico ile kartla ödeyin", ProviderTexts.resolveIn(description, loader, "tr-TR"), "same language")
        assertEquals("Оплата картой через iyzico", ProviderTexts.resolveIn(description, loader, "ru"))
        assertEquals("Secure payment", ProviderTexts.resolveIn(hint, loader, "ru"), "no Russian text: the English fallback")
        assertEquals("Pay by card with iyzico", ProviderTexts.resolveIn(description, loader, "de"))
        assertEquals("iyzico", ProviderTexts.resolveIn(missing, loader, "tr"))
        assertEquals("Pay by card with iyzico", ProviderTexts.resolveIn(description, null, "tr"))
    }

    @Test
    fun `a literal text resolves as before`() {
        val text = LocalizedText.of("Bank transfer", "tr" to "Havale")

        assertEquals("Havale", ProviderTexts.resolve(text, this, "tr"))
        assertEquals("Bank transfer", ProviderTexts.resolve(text, this, "ru"))
    }
}
