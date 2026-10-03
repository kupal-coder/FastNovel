package com.lagradost.quicknovel.ui.home

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelHomeLogicTest {
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

    @Test
    fun randomProviderPicksAreEnabledMainPageProvidersWithoutDuplicatesAndCapped() {
        val providers = listOf(
            provider("A"),
            provider("B"),
            provider("C"),
            provider("D"),
            provider("E"),
            provider("Disabled"),
            provider("No browse page", hasMainPage = false),
        )
        val enabled = setOf("A", "B", "C", "D", "E", "No browse page")
        val eligible = setOf("A", "B", "C", "D", "E")

        for (seed in 0L until 20L) {
            val picks = pickRandomProviders(providers, enabled, RANDOM_PROVIDERS_PER_ROLL, Random(seed))
            assertEquals(RANDOM_PROVIDERS_PER_ROLL, picks.size)
            assertEquals(picks.size, picks.map { it.name }.distinct().size)
            assertTrue(picks.all { it.name in eligible })
        }

        // Fewer eligible providers than the cap: return them all, still without duplicates.
        val few = pickRandomProviders(providers, setOf("A", "B", "No browse page"), 3, Random(0))
        assertEquals(setOf("A", "B"), few.map { it.name }.toSet())

        // No eligible provider at all.
        assertEquals(0, pickRandomProviders(providers, emptySet(), 3, Random(0)).size)
    }

    @Test
    fun randomPageOrderStaysWithinThreePagesAndFallsBackToPageOne() {
        val firstPages = mutableSetOf<Int>()
        for (seed in 0L until 50L) {
            val pages = randomPageOrder(Random(seed))
            val first = pages.first()
            assertTrue(first in 1..3)
            firstPages.add(first)
            if (first == 1) {
                assertEquals(listOf(1), pages)
            } else {
                assertEquals(listOf(first, 1), pages)
            }
        }
        // The whole 1..3 range really is reachable.
        assertEquals(setOf(1, 2, 3), firstPages)
    }

    @Test
    fun randomNovelMergingDeduplicatesByProviderAndUrlAndCapsAtTwelve() {
        val existing = (1..8).map { novel("N$it", "https://example.invalid/$it", "ProviderA") }
        val incoming = listOf(
            // Same provider + url as an existing item: dropped, the first occurrence wins.
            novel("Duplicate", "https://example.invalid/1", "ProviderA"),
            // Same url from another provider is a different novel: kept.
            novel("Same url other provider", "https://example.invalid/1", "ProviderB"),
            novel("N9", "https://example.invalid/9", "ProviderA"),
            // Duplicate inside the incoming batch: dropped.
            novel("N9 copy", "https://example.invalid/9", "ProviderA"),
        ) + (10..14).map { novel("N$it", "https://example.invalid/$it", "ProviderA") }

        val merged = mergeRandomNovels(existing, incoming, RANDOM_NOVELS_LIMIT, Random(7))

        // 8 existing + 7 distinct incoming = 15 distinct, capped at 12.
        assertEquals(RANDOM_NOVELS_LIMIT, merged.size)
        assertEquals(merged.size, merged.map { it.apiName to it.url }.distinct().size)
        assertTrue(merged.none { it.name == "Duplicate" })
        assertTrue(merged.none { it.name == "N9 copy" })
    }

    @Test
    fun randomNovelMergingKeepsEverythingUnderTheCap() {
        val existing = listOf(novel("A", "https://example.invalid/a", "Provider"))
        val incoming = listOf(novel("B", "https://example.invalid/b", "Provider"))

        val merged = mergeRandomNovels(existing, incoming, RANDOM_NOVELS_LIMIT, Random(1))

        assertEquals(2, merged.size)
        assertEquals(setOf("A", "B"), merged.map { it.name }.toSet())
    }

    private fun novel(name: String, url: String, apiName: String) =
        SearchResponse(name = name, url = url, apiName = apiName)

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
