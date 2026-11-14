package com.panomc.plugins.market.util

import java.util.UUID

object StoreBackground {
    const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
    private val namePattern = Regex("^store-background-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|gif|webp)$")

    fun isFileName(name: String): Boolean = namePattern.matches(name)

    fun isCurrentFile(name: String, current: String): Boolean = isFileName(name) && name == current

    fun newFileName(extension: String): String {
        require(extension in setOf("png", "jpg", "gif", "webp"))
        return "store-background-${UUID.randomUUID()}.$extension"
    }
}
