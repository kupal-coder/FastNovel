package com.lagradost.quicknovel.ui.home

import com.lagradost.quicknovel.MainAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelHomeLogicTest {
    @Test
    fun providerOrderingFiltersDisabledAndUnsupportedAndPrefersRecentSource() {
        val first = provider("First", hasMainPage = true)
        val recent = provider("Recent", hasMainPage = true)
        val disabled = provider("Disabled", hasMainPage = true)
        val unsupported = provider("No browse page", hasMainPage = false)

        val ordered = orderHomeProviders(
            providers = listOf(first, recent, disabled, unsupported),
            enabledProviderNames = setOf("First", "Recent", "No browse page"),
            preferredProviderName = "Recent",
        )

        assertEquals(listOf("Recent", "First"), ordered.map { it.name })
    }

    @Test
    fun latestSelectionUsesNewestFiltersAndFallsBackToAllAndDefaults() {
        val newest = provider(
            name = "Newest",
            categories = listOf("All", "Completed"),
            orders = listOf("Popular", "Latest update"),
        )
        val fallback = provider(
            name = "Fallback",
            categories = listOf("Ongoing", "All novels"),
        )

        assertEquals(HomePageSelection(0, 1), latestPageSelection(newest))
        assertEquals(HomePageSelection(1, -1), latestPageSelection(fallback))
    }

    @Test
    fun popularSelectionIsOptionalAndUsesProviderSupportedFilter() {
        val popularCategory = provider(
            name = "Category ranking",
            categories = listOf("All", "Most popular"),
        )
        val popularOrder = provider(
            name = "Order ranking",
            categories = listOf("All"),
            orders = listOf("Latest", "Top rated"),
        )
        val noRanking = provider(
            name = "No ranking",
            categories = listOf("All"),
            orders = listOf("Latest"),
        )
        val onlyPopular = provider(
            name = "Only popular",
            categories = listOf("Popular"),
            orders = listOf("Popular"),
        )

        assertEquals(HomePageSelection(1, -1), popularPageSelection(popularCategory))
        assertEquals(HomePageSelection(0, 1), popularPageSelection(popularOrder))
        assertNull(popularPageSelection(noRanking))
        assertEquals(HomePageSelection(-1, -1), latestPageSelection(onlyPopular))
        assertEquals(HomePageSelection(0, -1), popularPageSelection(onlyPopular))
    }

    @Test
    fun relativeAgeHandlesMissingFutureAndUnitBoundaries() {
        val now = 300_000_000L

        assertNull(addedAge(null, now))
        assertNull(addedAge(0L, now))
        assertEquals(AddedAge(0, AddedAgeUnit.JustNow), addedAge(now + 1L, now))
        assertEquals(AddedAge(0, AddedAgeUnit.JustNow), addedAge(now, now))
        assertEquals(AddedAge(1, AddedAgeUnit.Minutes), addedAge(now - 60_000L, now))
        assertEquals(AddedAge(1, AddedAgeUnit.Hours), addedAge(now - 3_600_000L, now))
        assertEquals(AddedAge(2, AddedAgeUnit.Days), addedAge(now - 2 * 86_400_000L, now))
    }

    private fun provider(
        name: String,
        hasMainPage: Boolean = true,
        categories: List<String> = emptyList(),
        orders: List<String> = emptyList(),
    ): MainAPI {
        val providerName = name
        val supportsMainPage = hasMainPage
        val providerCategories = categories.map { it to it }
        val providerOrders = orders.map { it to it }
        return object : MainAPI() {
            override val name: String = providerName
            override val hasMainPage: Boolean = supportsMainPage
            override val mainCategories: List<Pair<String, String>> = providerCategories
            override val orderBys: List<Pair<String, String>> = providerOrders
        }
    }
}
