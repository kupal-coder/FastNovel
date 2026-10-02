package com.lagradost.quicknovel.ui.result

import java.util.Locale

internal enum class ProviderTagFilterType {
    Tag,
    MainCategory,
}

internal data class ProviderTagFilterTarget(
    val type: ProviderTagFilterType,
    val index: Int,
    val providerFilterValue: String,
)

internal data class DetailTagBrowseTarget(
    val apiName: String,
    val tagIndex: Int,
    val providerFilterValue: String,
    val novelUrl: String,
)

private sealed interface LabelFilterMatch {
    data object None : LabelFilterMatch
    data object Ambiguous : LabelFilterMatch
    data class Unique(val index: Int, val value: String) : LabelFilterMatch
}

/**
 * Resolves a displayed genre label only when one provider-owned filter matches it. Exact labels
 * take precedence; otherwise matching is limited to case/whitespace normalization. A matching
 * main category is considered only when no provider tag matched, and only for the same label.
 */
internal fun resolveProviderTagFilter(
    displayedTag: String,
    providerTags: List<Pair<String, String>>,
    mainCategories: List<Pair<String, String>>,
): ProviderTagFilterTarget? {
    if (displayedTag.isBlank()) return null

    when (val tagMatch = matchProviderFilter(displayedTag, providerTags)) {
        is LabelFilterMatch.Unique -> return ProviderTagFilterTarget(
            type = ProviderTagFilterType.Tag,
            index = tagMatch.index,
            providerFilterValue = tagMatch.value,
        )

        LabelFilterMatch.Ambiguous -> return null
        LabelFilterMatch.None -> Unit
    }

    val categoryMatch = matchProviderFilter(displayedTag, mainCategories)
    return (categoryMatch as? LabelFilterMatch.Unique)?.let { match ->
        ProviderTagFilterTarget(
            type = ProviderTagFilterType.MainCategory,
            index = match.index,
            providerFilterValue = match.value,
        )
    }
}

/** Existing single-provider target, now backed by the same resolver used for cross-provider tags. */
internal fun resolveDetailTagBrowseTarget(
    apiName: String?,
    displayedTag: String,
    providerTags: List<Pair<String, String>>,
    novelUrl: String?,
): DetailTagBrowseTarget? {
    val resolvedApiName = apiName?.takeIf { it.isNotBlank() } ?: return null
    val resolvedNovelUrl = novelUrl?.takeIf { it.isNotBlank() } ?: return null
    val match = resolveProviderTagFilter(displayedTag, providerTags, emptyList())
        ?.takeIf { it.type == ProviderTagFilterType.Tag }
        ?: return null

    return DetailTagBrowseTarget(
        apiName = resolvedApiName,
        tagIndex = match.index,
        providerFilterValue = match.providerFilterValue,
        novelUrl = resolvedNovelUrl,
    )
}

private fun matchProviderFilter(
    displayedTag: String,
    providerFilters: List<Pair<String, String>>,
): LabelFilterMatch {
    val eligible = providerFilters.withIndex().filter { (_, pair) ->
        pair.first.isNotBlank() && pair.second.isNotBlank() &&
            normalizeTagLabel(pair.first) != "all"
    }
    val exact = eligible.filter { it.value.first == displayedTag }
    val candidates = if (exact.isNotEmpty()) {
        exact
    } else {
        val normalizedTag = normalizeTagLabel(displayedTag)
        eligible.filter { normalizeTagLabel(it.value.first) == normalizedTag }
    }

    return when (candidates.size) {
        0 -> LabelFilterMatch.None
        1 -> LabelFilterMatch.Unique(candidates.single().index, candidates.single().value.second)
        else -> LabelFilterMatch.Ambiguous
    }
}

private val tagWhitespace = Regex("[\\s\\p{Z}]+")

private fun normalizeTagLabel(value: String): String =
    value.trim().replace(tagWhitespace, " ").lowercase(Locale.ROOT)
