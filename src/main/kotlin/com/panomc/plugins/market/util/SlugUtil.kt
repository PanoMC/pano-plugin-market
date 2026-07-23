package com.panomc.plugins.market.util

import java.util.Locale

object SlugUtil {
    // Base slugs are capped BEFORE any "-copy-N" suffix so suffixed slugs still fit VARCHAR(255).
    private const val MAX_LENGTH = 240

    private val turkishCharMap = mapOf(
        'ç' to "c", 'ğ' to "g", 'ı' to "i", 'ö' to "o", 'ş' to "s", 'ü' to "u",
        'Ç' to "c", 'Ğ' to "g", 'İ' to "i", 'I' to "i", 'Ö' to "o", 'Ş' to "s", 'Ü' to "u"
    )

    fun slugify(text: String): String {
        val transliterated = buildString {
            text.forEach { char ->
                append(turkishCharMap[char] ?: char)
            }
        }

        return transliterated
            .lowercase(Locale.ENGLISH)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_LENGTH)
            .trim('-')
    }

    fun copySlug(slug: String, copyNumber: Int): String {
        val base = slug.take(MAX_LENGTH).trim('-')

        return if (copyNumber <= 1) "$base-copy" else "$base-copy-$copyNumber"
    }
}
