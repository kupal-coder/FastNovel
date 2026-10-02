package com.lagradost.quicknovel.ui.result

import com.lagradost.quicknovel.MainAPI
import java.util.Locale

enum class DetailTagFilterKind {
    TAG,
    MAIN_CATEGORY,
}

internal data class DetailTagBrowseTarget(
    val apiName: String,
    val tagIndex: Int,
    val providerFilterValue: String,
    val novelUrl: String,
    val filterKind: DetailTagFilterKind = DetailTagFilterKind.TAG,
)

internal data class DetailTagMainPageRequest(
    val mainCategory: String?,
    val orderBy: String?,
    val tag: String?,
)

private val UNFILTERED_TAG_LABELS = setOf(
    "all",
    "todos",
    "tous",
    "todas",
    "الكل",
    "все",
    "cualquiera",
    "any",
    "any status",
    "other",
    "non-genre",
    "unknown",
)

private val NON_GENRE_FILTER_LABELS = UNFILTERED_TAG_LABELS + setOf(
    // Publication status labels
    "ongoing",
    "completed",
    "complete",
    "completed novel",
    "hiatus",
    "on hiatus",
    "on hold",
    "dropped",
    "cancelled",
    "canceled",
    "inactive",
    "stub",
    "finished",
    "active",
    "завершено",
    "в процессе",
    "مستمر",
    "مكتمل",
    "متوقف",
    // Sort / ranking / period labels
    "popular",
    "popular this week",
    "most popular",
    "trending",
    "readers",
    "rising",
    "favorites",
    "favourites",
    "read",
    "subscribed",
    "comments",
    "views",
    "name",
    "date added",
    "latest",
    "latest release",
    "hot",
    "hot novel",
    "new",
    "most viewed",
    "top rated",
    "most commented",
    "most liked",
    "all time",
)

private val NON_GENRE_MAIN_CATEGORY_PROVIDERS = setOf(
    "Wattpad",
    "Anna's Archive",
)

private val tagWhitespace = Regex("[\\s\\p{Z}]+")

/** Resolves a displayed detail tag only when it points to one unique provider genre filter. */
internal fun resolveDetailTagBrowseTarget(
    apiName: String?,
    displayedTag: String,
    providerTags: List<Pair<String, String>>,
    novelUrl: String?,
    providerCategories: List<Pair<String, String>> = emptyList(),
): DetailTagBrowseTarget? {
    val resolvedApiName = apiName?.takeIf { it.isNotBlank() } ?: return null
    val resolvedNovelUrl = novelUrl?.takeIf { it.isNotBlank() } ?: return null
    val trimmedDisplayed = displayedTag.trim()
    if (trimmedDisplayed.isEmpty()) return null

    val normalizedTag = normalizeTagLabel(trimmedDisplayed)
    if (normalizedTag in NON_GENRE_FILTER_LABELS) return null

    val tagCandidates = buildFilterCandidates(providerTags)
    when (val tagOutcome = resolveUniqueMatchOutcome(tagCandidates, displayedTag, trimmedDisplayed, normalizedTag)) {
        is MatchOutcome.Unique -> {
            return DetailTagBrowseTarget(
                apiName = resolvedApiName,
                tagIndex = tagOutcome.candidate.index,
                providerFilterValue = tagOutcome.candidate.value,
                novelUrl = resolvedNovelUrl,
                filterKind = DetailTagFilterKind.TAG,
            )
        }
        MatchOutcome.Ambiguous -> return null
        MatchOutcome.None -> Unit
    }

    if (resolvedApiName in NON_GENRE_MAIN_CATEGORY_PROVIDERS) return null

    val categoryCandidates = buildFilterCandidates(providerCategories)
    return when (
        val categoryOutcome = resolveUniqueMatchOutcome(
            categoryCandidates,
            displayedTag,
            trimmedDisplayed,
            normalizedTag,
        )
    ) {
        is MatchOutcome.Unique -> DetailTagBrowseTarget(
            apiName = resolvedApiName,
            tagIndex = categoryOutcome.candidate.index,
            providerFilterValue = categoryOutcome.candidate.value,
            novelUrl = resolvedNovelUrl,
            filterKind = DetailTagFilterKind.MAIN_CATEGORY,
        )
        MatchOutcome.Ambiguous, MatchOutcome.None -> null
    }
}

internal fun resolveDetailTagBrowseTarget(
    api: MainAPI?,
    displayedTag: String?,
    novelUrl: String? = "https://novel.local",
): DetailTagBrowseTarget? {
    if (api == null || !api.hasMainPage || displayedTag == null) return null
    return resolveDetailTagBrowseTarget(
        apiName = api.name,
        displayedTag = displayedTag,
        providerTags = api.tags,
        novelUrl = novelUrl ?: "https://novel.local",
        providerCategories = api.mainCategories,
    )
}

internal fun resolveEnabledProviderTagTargets(
    apis: Iterable<MainAPI>,
    enabledProviderNames: Set<String>,
    enabledLanguages: Set<String>,
    rawTagLabel: String?,
    novelUrl: String? = "https://novel.local",
    prioritizeApiName: String? = null,
): List<Pair<MainAPI, DetailTagBrowseTarget>> {
    if (enabledProviderNames.isEmpty() || enabledLanguages.isEmpty()) return emptyList()
    val tagLabel = rawTagLabel?.trim().orEmpty()
    if (tagLabel.isEmpty()) return emptyList()

    val enabledApis = apis.filter { api ->
        api.hasMainPage && api.name in enabledProviderNames && api.lang in enabledLanguages
    }
    if (enabledApis.isEmpty()) return emptyList()

    val orderedApis = if (prioritizeApiName.isNullOrBlank()) {
        enabledApis
    } else {
        val primary = enabledApis.filter { it.name == prioritizeApiName }
        val rest = enabledApis.filterNot { it.name == prioritizeApiName }
        primary + rest
    }

    val effectiveNovelUrl = novelUrl?.takeIf { it.isNotBlank() } ?: "https://novel.local"
    return orderedApis.mapNotNull { api ->
        val target = resolveDetailTagBrowseTarget(
            api = api,
            displayedTag = tagLabel,
            novelUrl = effectiveNovelUrl,
        ) ?: return@mapNotNull null
        api to target
    }
}

internal fun buildDetailTagMainPageRequest(
    api: MainAPI,
    target: DetailTagBrowseTarget,
): DetailTagMainPageRequest {
    val defaultCategory = api.mainCategories.firstOrNull()?.second
    val defaultOrderBy = api.orderBys.firstOrNull()?.second
    val defaultTag = api.tags.firstOrNull()?.second

    return when (target.filterKind) {
        DetailTagFilterKind.TAG -> DetailTagMainPageRequest(
            mainCategory = defaultCategory,
            orderBy = defaultOrderBy,
            tag = target.providerFilterValue,
        )
        DetailTagFilterKind.MAIN_CATEGORY -> DetailTagMainPageRequest(
            mainCategory = target.providerFilterValue,
            orderBy = defaultOrderBy,
            tag = defaultTag,
        )
    }
}

private data class IndexedFilterCandidate(
    val index: Int,
    val rawLabel: String,
    val trimmedLabel: String,
    val normalizedLabel: String,
    val value: String,
)

private sealed interface MatchOutcome {
    data class Unique(val candidate: IndexedFilterCandidate) : MatchOutcome
    data object Ambiguous : MatchOutcome
    data object None : MatchOutcome
}

private fun buildFilterCandidates(
    entries: List<Pair<String, String>>,
): List<IndexedFilterCandidate> {
    if (entries.isEmpty()) return emptyList()
    return entries.withIndex().mapNotNull { (index, pair) ->
        val rawLabel = pair.first
        val trimmedLabel = rawLabel.trim()
        val trimmedValue = pair.second.trim()
        val normalizedLabel = normalizeTagLabel(trimmedLabel)
        if (
            trimmedLabel.isEmpty() ||
            trimmedValue.isEmpty() ||
            normalizedLabel in NON_GENRE_FILTER_LABELS ||
            trimmedValue.equals("all", ignoreCase = true) ||
            trimmedValue == "0"
        ) {
            null
        } else {
            IndexedFilterCandidate(
                index = index,
                rawLabel = rawLabel,
                trimmedLabel = trimmedLabel,
                normalizedLabel = normalizedLabel,
                value = trimmedValue,
            )
        }
    }
}

private fun resolveUniqueMatchOutcome(
    candidates: List<IndexedFilterCandidate>,
    rawDisplayedTag: String,
    trimmedDisplayedTag: String,
    normalizedDisplayedTag: String,
): MatchOutcome {
    if (candidates.isEmpty()) return MatchOutcome.None

    val exactRawMatches = candidates.filter { it.rawLabel == rawDisplayedTag }
    val exactTrimmedMatches = if (exactRawMatches.isNotEmpty()) {
        exactRawMatches
    } else {
        candidates.filter { it.trimmedLabel == trimmedDisplayedTag }
    }

    val matches = if (exactTrimmedMatches.isNotEmpty()) {
        exactTrimmedMatches
    } else {
        candidates.filter { it.normalizedLabel == normalizedDisplayedTag }
    }

    if (matches.isEmpty()) return MatchOutcome.None

    val distinctValues = matches.map { it.value }.distinct()
    if (distinctValues.size != 1) return MatchOutcome.Ambiguous

    return MatchOutcome.Unique(matches.first())
}

internal fun normalizeTagLabel(value: String): String =
    value.trim().replace(tagWhitespace, " ").lowercase(Locale.ROOT)
