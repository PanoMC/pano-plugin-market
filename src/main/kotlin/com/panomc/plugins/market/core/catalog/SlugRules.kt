package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.util.SlugUtil

/**
 * Product slug rules (01 section 2.2, 04 section 5). Pure: the uniqueness check against the database belongs to
 * `CatalogService`.
 *
 * - A slug is what [SlugUtil.slugify] makes of the request's `slug`, else of the `name`: lower-case ASCII letters,
 *   digits and single dashes, at most 240 characters.
 * - `checkout`, `order` and `cart` are reserved (they are storefront paths): such a slug is refused, never renamed.
 * - A soft-deleted product keeps its row under the slug `<slug>--d<id>`; `slugify` can never produce two dashes in a
 *   row, so no live slug can collide with an archived one and the original slug is free again.
 */
object SlugRules {
    /** Storefront paths a product must not shadow. */
    val RESERVED: Set<String> = setOf("checkout", "order", "cart")

    /** Longest slug column value. */
    const val COLUMN_LENGTH = 255

    /** The slug a request asks for: [requested] when it slugifies to something, else the slug of [name]. May be blank. */
    fun resolve(requested: String?, name: String?): String {
        val fromRequest = requested?.takeIf { it.isNotBlank() }?.let { SlugUtil.slugify(it) }.orEmpty()

        if (fromRequest.isNotEmpty()) return fromRequest

        return name?.let { SlugUtil.slugify(it) }.orEmpty()
    }

    fun isReserved(slug: String): Boolean = slug in RESERVED

    /** `null` when [slug] may be stored, else the `fieldErrors.slug` code: `REQUIRED` or `RESERVED_SLUG`. */
    fun check(slug: String): String? = when {
        slug.isBlank() -> "REQUIRED"
        isReserved(slug) -> "RESERVED_SLUG"
        else -> null
    }

    /** The slug a soft-deleted product is stored under; always within [COLUMN_LENGTH] and never a live slug. */
    fun archivedSlug(slug: String, id: Long): String {
        val suffix = "--d$id"

        return slug.take(COLUMN_LENGTH - suffix.length) + suffix
    }

    fun isArchivedSlug(slug: String): Boolean = ARCHIVED.containsMatchIn(slug)

    private val ARCHIVED = Regex("--d[0-9]+$")
}
