package com.lagradost.quicknovel.ui.home

import com.lagradost.quicknovel.MainAPI
import java.util.Locale

/** A category/order pair used by both the home request and its See More destination. */
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

/**
 * Put the most recently read provider first, then keep the configured provider order. Only
 * providers with a main page are eligible for the Home carousels.
 */
internal fun orderHomeProviders(
    providers: Iterable<MainAPI>,
    enabledProviderNames: Set<String>,
    preferredProviderName: String?,
): List<MainAPI> {
    val eligible = providers.filter { it.hasMainPage && it.name in enabledProviderNames }
    val preferred = eligible.firstOrNull { it.name == preferredProviderName }
        ?: return eligible
    return listOf(preferred) + eligible.filterNot { it.name == preferred.name }
}

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

/** Returns a provider's popular/ranking query, or null if it exposes no such ordering. */
internal fun popularPageSelection(api: MainAPI): HomePageSelection? {
    val categoryIndex = api.mainCategories.indexOfFirst { it.first.isPopularOrder() }
        .takeIf { it >= 0 }
    val orderByIndex = api.orderBys.indexOfFirst { it.first.isPopularOrder() }
        .takeIf { it >= 0 }

    if (categoryIndex == null && orderByIndex == null) return null

    return HomePageSelection(
        categoryIndex = categoryIndex ?: latestPageSelection(api).categoryIndex,
        orderByIndex = orderByIndex ?: if (categoryIndex != null) -1 else latestPageSelection(api).orderByIndex,
    )
}

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
