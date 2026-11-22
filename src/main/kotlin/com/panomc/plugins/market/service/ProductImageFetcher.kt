package com.panomc.plugins.market.service

import com.panomc.plugins.market.util.ImageUtil
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Stores a product image from a provider's catalogue ([CatalogImporter]); `null` = not stored (the product is saved without it). */
interface ProductImageFetcher {
    suspend fun fetch(url: String): String?

    /** Removes a file [fetch] stored when the product it was for could not be saved. */
    fun discard(fileName: String)
}

/**
 * Downloads an image over HTTPS into the store's uploads folder, like an admin upload: at most [MAX_BYTES], no redirect
 * followed, the host must resolve to public addresses only (a catalogue URL never reaches the server's own network), and the
 * format comes from the bytes ([ImageUtil.detectImageExtension]), never from the URL or the Content-Type.
 */
class RemoteProductImageFetcher(
    private val http: WebClient,
    private val uploadsDir: File,
    private val timeoutMs: Long = 15_000
) : ProductImageFetcher {
    private val logger = LoggerFactory.getLogger(RemoteProductImageFetcher::class.java)

    override suspend fun fetch(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.userInfo != null) return null
        if (!publicOnly(host)) {
            logger.warn("Catalog image not fetched: $host resolves to a private address")
            return null
        }

        val response = try {
            http.getAbs(url).timeout(timeoutMs).followRedirects(false).send().coAwait()
        } catch (e: Exception) {
            logger.warn("Catalog image not fetched from $host: ${e.javaClass.simpleName}")
            return null
        }
        if (response.statusCode() != 200) return null
        val declared = response.getHeader("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_BYTES) return null
        val body = response.body() ?: return null
        if (body.length() == 0 || body.length() > MAX_BYTES) return null

        return withContext(Dispatchers.IO) {
            uploadsDir.mkdirs()
            val temp = File.createTempFile("catalog-image-", ".tmp", uploadsDir)
            try {
                temp.writeBytes(body.bytes)
                val extension = ImageUtil.detectImageExtension(temp) ?: return@withContext null
                val fileName = "product-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}-import.$extension"
                val dest = File(uploadsDir, fileName)
                temp.copyTo(dest, true)
                ImageUtil.generateThumbnail(dest, File(uploadsDir, "thumbnails"))
                fileName
            } finally {
                temp.delete()
            }
        }
    }

    override fun discard(fileName: String) {
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) return
        File(uploadsDir, fileName).delete()
        File(File(uploadsDir, "thumbnails"), fileName).delete()
    }

    private suspend fun publicOnly(host: String): Boolean = withContext(Dispatchers.IO) {
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull().orEmpty()
        addresses.isNotEmpty() && addresses.none { isInternal(it) }
    }

    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024

        fun isInternal(address: InetAddress): Boolean =
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                // IPv6 unique local (fc00::/7) and IPv4 shared / carrier-grade NAT (100.64.0.0/10)
                (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc) ||
                (address.address.size == 4 && (address.address[0].toInt() and 0xff) == 100 && (address.address[1].toInt() and 0xc0) == 64)
    }
}
