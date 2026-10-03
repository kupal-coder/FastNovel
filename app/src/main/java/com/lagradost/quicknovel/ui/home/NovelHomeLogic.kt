package com.lagradost.quicknovel.ui.home

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import java.util.Locale
import kotlin.random.Random

/** How many different providers one "Random novels" roll asks, in parallel. */
internal const val RANDOM_PROVIDERS_PER_ROLL = 3

/** How many novels the "Random novels" row keeps at most. */
internal const val RANDOM_NOVELS_LIMIT = 12

/** A category/order pair used by the home requests. */
data class HomePageSelection(
    val categoryIndex: Int,
    val orderByIndex: Int,
)

internal enum class AddedAgeUnit {
    JustNow,
    Minutes,
    Hours,
    Days,
}

internal data class AddedAge(
    val amount: Int,
    val unit: AddedAgeUnit,
)

/** Chooses the provider's newest/recent option, falling back to its default page filters. */
internal fun latestPageSelection(api: MainAPI): HomePageSelection {
    val categoryIndex = api.mainCategories.indexOfFirst { it.first.isLatestOrder() }
        .takeIf { it >= 0 }
        ?: api.mainCategories.indexOfFirst { it.first.isAllCategory() }
            .takeIf { it >= 0 }
        ?: api.mainCategories.indexOfFirst { !it.first.isPopularOrder() }
            .takeIf { it >= 0 }
        ?: -1

    val orderByIndex = api.orderBys.indexOfFirst { it.first.isLatestOrder() }
        .takeIf { it >= 0 }
        ?: api.orderBys.indexOfFirst { !it.first.isPopularOrder() }
            .takeIf { it >= 0 }
        ?: -1

    return HomePageSelection(categoryIndex, orderByIndex)
}

/** Randomly picks up to [count] different enabled providers that expose a main page. */
internal fun pickRandomProviders(
    providers: Iterable<MainAPI>,
    enabledProviderNames: Set<String>,
    count: Int,
    random: Random = Random.Default,
): List<MainAPI> =
    providers.asSequence()
        .filter { it.hasMainPage && it.name in enabledProviderNames }
        .distinctBy { it.name }
        .toList()
        .shuffled(random)
        .take(count)

/** Pages to try for one provider: a random page 1..3, falling back to page 1. */
internal fun randomPageOrder(random: Random = Random.Default): List<Int> {
    val first = random.nextInt(1, 4)
    return if (first == 1) listOf(1) else listOf(first, 1)
}

/** De-duplicates by provider + url, shuffles and caps the progressive "Random novels" results. */
internal fun mergeRandomNovels(
    existing: List<SearchResponse>,
    incoming: List<SearchResponse>,
    limit: Int = RANDOM_NOVELS_LIMIT,
    random: Random = Random.Default,
): List<SearchResponse> =
    (existing + incoming)
        .distinctBy { it.apiName to it.url }
        .shuffled(random)
        .take(limit)

/** Relative-age parts for a provider-supplied timestamp. A missing date stays hidden in the UI. */
internal fun addedAge(
    addedAtMillis: Long?,
    nowMillis: Long,
): AddedAge? {
    if (addedAtMillis == null || addedAtMillis <= 0L) return null

    val elapsedMillis = (nowMillis - addedAtMillis).coerceAtLeast(0L)
    return when {
        elapsedMillis < MILLIS_PER_MINUTE -> AddedAge(0, AddedAgeUnit.JustNow)
        elapsedMillis < MILLIS_PER_HOUR -> AddedAge(
            (elapsedMillis / MILLIS_PER_MINUTE).toInt(),
            AddedAgeUnit.Minutes,
        )
        elapsedMillis < MILLIS_PER_DAY -> AddedAge(
            (elapsedMillis / MILLIS_PER_HOUR).toInt(),
            AddedAgeUnit.Hours,
        )
        else -> AddedAge(
            (elapsedMillis / MILLIS_PER_DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            AddedAgeUnit.Days,
        )
    }
}

private fun String.isLatestOrder(): Boolean {
    val label = lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
    return LATEST_ORDER_TERMS.any { term -> label.contains(term) }
}

private fun String.isPopularOrder(): Boolean {
    val label = lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
    return POPULAR_ORDER_TERMS.any { term -> label.contains(term) }
}

private fun String.isAllCategory(): Boolean {
    val label = lowercase(Locale.ROOT).trim()
    return label in ALL_CATEGORY_LABELS
}

private val LATEST_ORDER_TERMS = listOf(
    "new",
    "latest",
    "recent",
    "update",
    "release",
    "added",
    "published",
    "created",
    "date",
    "recient",
    "actualiz",
    "nuev",
    "neu",
    "нов",
)

private val POPULAR_ORDER_TERMS = listOf(
    "popular",
    "trend",
    "top",
    "best",
    "most read",
    "most viewed",
    "views",
    "reader",
    "click",
    "like",
    "favorite",
    "favourite",
    "rating",
    "vote",
    "rank",
    "hot",
    "all time",
    "weekly",
    "daily",
    "monthly",
)

private val ALL_CATEGORY_LABELS = setOf(
    "all",
    "all novels",
    "all time",
    "any",
    "any status",
    "todos",
    "todas",
    "все",
)

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
