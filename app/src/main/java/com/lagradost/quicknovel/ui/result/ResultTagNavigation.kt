package com.lagradost.quicknovel.ui.result

import java.util.Locale

internal data class DetailTagBrowseTarget(
    val apiName: String,
    val tagIndex: Int,
    val providerFilterValue: String,
    val novelUrl: String,
)

/** Resolves a displayed detail tag only when it points to one provider filter. */
internal fun resolveDetailTagBrowseTarget(
    apiName: String?,
    displayedTag: String,
    providerTags: List<Pair<String, String>>,
    novelUrl: String?,
): DetailTagBrowseTarget? {
    val resolvedApiName = apiName?.takeIf { it.isNotBlank() } ?: return null
    val resolvedNovelUrl = novelUrl?.takeIf { it.isNotBlank() } ?: return null
    if (displayedTag.isBlank()) return null

    val exactMatches = providerTags.withIndex().filter { it.value.first == displayedTag }
    val matches = if (exactMatches.isNotEmpty()) {
        exactMatches
    } else {
        val normalizedTag = normalizeTagLabel(displayedTag)
        providerTags.withIndex().filter { normalizeTagLabel(it.value.first) == normalizedTag }
    }

    val filterValues = matches.map { it.value.second }.distinct()
    if (filterValues.size != 1) return null

    val match = matches.firstOrNull() ?: return null
    return DetailTagBrowseTarget(
        apiName = resolvedApiName,
        tagIndex = match.index,
        providerFilterValue = match.value.second,
        novelUrl = resolvedNovelUrl,
    )
}

private val tagWhitespace = Regex("[\\s\\p{Z}]+")

private fun normalizeTagLabel(value: String): String =
    value.trim().replace(tagWhitespace, " ").lowercase(Locale.ROOT)
