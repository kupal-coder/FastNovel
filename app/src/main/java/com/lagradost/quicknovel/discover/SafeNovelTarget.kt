package com.lagradost.quicknovel.discover

import com.lagradost.quicknovel.util.Apis
import java.net.URI
import java.util.Locale

/**
 * Discover posts carry a provider name and a novel URL written by other users. Before any
 * loadResult(...) coming from a post, require a known provider and an https URL whose host is
 * the provider's own mainUrl host or a subdomain of it.
 */
fun isSafeNovelTarget(provider: String?, url: String?): Boolean {
    val api = Apis.getApiFromNameNull(provider) ?: return false
    return isSafeNovelUrl(api.mainUrl, url)
}

/** The URL half of [isSafeNovelTarget], kept pure so it is testable without the provider list. */
internal fun isSafeNovelUrl(providerMainUrl: String?, url: String?): Boolean {
    if (providerMainUrl.isNullOrBlank() || url.isNullOrBlank()) return false
    val providerHost = hostOf(providerMainUrl, requireHttps = false) ?: return false
    val host = hostOf(url, requireHttps = true) ?: return false
    return host == providerHost || host.endsWith(".$providerHost")
}

/** Lowercased host of an absolute URL; null when malformed or, optionally, not https. */
private fun hostOf(url: String, requireHttps: Boolean): String? {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    if (requireHttps && !"https".equals(uri.scheme, ignoreCase = true)) return null
    return uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
}
