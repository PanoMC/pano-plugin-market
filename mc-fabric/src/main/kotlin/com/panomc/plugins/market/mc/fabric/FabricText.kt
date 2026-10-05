package com.panomc.plugins.market.mc.fabric

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.net.URI

/**
 * Legacy `§` text as a Minecraft chat component. Only `ChatFormatting`, `Style.with...` and `Component.literal` are used:
 * the few calls that every 26.x release has. A Minecraft that moved one of them (a `LinkageError`, which would get past
 * every `catch (Exception)` and crash the server thread) costs the colours of one line, never the server: the text is
 * delivered plain.
 */
object FabricText {
    private val COLORS = mapOf(
        '0' to ChatFormatting.BLACK, '1' to ChatFormatting.DARK_BLUE, '2' to ChatFormatting.DARK_GREEN,
        '3' to ChatFormatting.DARK_AQUA, '4' to ChatFormatting.DARK_RED, '5' to ChatFormatting.DARK_PURPLE,
        '6' to ChatFormatting.GOLD, '7' to ChatFormatting.GRAY, '8' to ChatFormatting.DARK_GRAY,
        '9' to ChatFormatting.BLUE, 'a' to ChatFormatting.GREEN, 'b' to ChatFormatting.AQUA,
        'c' to ChatFormatting.RED, 'd' to ChatFormatting.LIGHT_PURPLE, 'e' to ChatFormatting.YELLOW,
        'f' to ChatFormatting.WHITE
    )

    fun component(text: String, openUrl: String? = null): Component = try {
        styled(text, openUrl)
    } catch (_: LinkageError) {
        Component.literal(LegacyText.plain(text))
    }

    private fun styled(text: String, openUrl: String?): Component {
        val root: MutableComponent = Component.empty()
        for (run in LegacyText.parse(text)) {
            var style = Style.EMPTY
            run.color?.let { c -> COLORS[c]?.let { style = style.withColor(it) } }
            if (run.bold) style = style.withBold(true)
            if (run.italic) style = style.withItalic(true)
            if (run.underlined) style = style.withUnderlined(true)
            if (run.strikethrough) style = style.withStrikethrough(true)
            if (run.obfuscated) style = style.withObfuscated(true)
            root.append(Component.literal(run.text).withStyle(style))
        }
        if (LegacyText.isWebUrl(openUrl)) {
            val uri = try {
                URI.create(openUrl!!)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (uri != null) root.withStyle(Style.EMPTY.withClickEvent(ClickEvent.OpenUrl(uri)))
        }
        return root
    }
}
