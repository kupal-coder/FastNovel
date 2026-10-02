package com.lagradost.quicknovel.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultTagNavigationTest {
    @Test
    fun exactDisplayMatchUsesProviderFilterValueBeforeNormalizedAlternatives() {
        val target = resolveDetailTagBrowseTarget(
            apiName = "NovelBin",
            displayedTag = "Xianxia",
            providerTags = listOf(
                " xianxia " to "normalized-filter",
                "Xianxia" to "exact-provider-filter",
            ),
            novelUrl = "https://novel.example/book",
        )

        assertEquals(1, target?.tagIndex)
        assertEquals("exact-provider-filter", target?.providerFilterValue)
    }

    @Test
    fun normalizedDisplayMatchIgnoresCaseAndCollapsesWhitespace() {
        val target = resolveDetailTagBrowseTarget(
            apiName = "NovelBin",
            displayedTag = "  XIANXIA\t  WORLD ",
            providerTags = listOf("Xianxia   World" to "provider-filter-value"),
            novelUrl = "https://novel.example/book",
        )

        assertEquals(0, target?.tagIndex)
        assertEquals("provider-filter-value", target?.providerFilterValue)
    }

    @Test
    fun normalizedDisplayMatchWithDifferentProviderFiltersIsUnsupported() {
        val target = resolveDetailTagBrowseTarget(
            apiName = "NovelBin",
            displayedTag = "XIANXIA",
            providerTags = listOf(
                "Xianxia" to "first-filter",
                " Xianxia " to "second-filter",
            ),
            novelUrl = "https://novel.example/book",
        )

        assertNull(target)
    }

    @Test
    fun selectedTagBuildsNavigationTargetForTheCurrentProviderAndNovel() {
        val target = resolveDetailTagBrowseTarget(
            apiName = "NovelBin",
            displayedTag = "  xianxia ",
            providerTags = listOf("Xianxia" to "xianxia"),
            novelUrl = "https://novel.example/book",
        )

        assertEquals(
            DetailTagBrowseTarget(
                apiName = "NovelBin",
                tagIndex = 0,
                providerFilterValue = "xianxia",
                novelUrl = "https://novel.example/book",
            ),
            target,
        )
    }

    @Test
    fun providerUsesItsOwnTagValueAndTagMatchPrecedesCategoryMatch() {
        val target = resolveProviderTagFilter(
            displayedTag = "Xianxia",
            providerTags = listOf("Xianxia" to "provider-tag"),
            mainCategories = listOf("Xianxia" to "provider-category"),
        )

        assertEquals(ProviderTagFilterType.Tag, target?.type)
        assertEquals("provider-tag", target?.providerFilterValue)
    }

    @Test
    fun exactGenreCategoryMayBeUsedWhenNoProviderTagMatches() {
        val target = resolveProviderTagFilter(
            displayedTag = "Xianxia",
            providerTags = emptyList(),
            mainCategories = listOf("Xianxia" to "category-xianxia"),
        )

        assertEquals(ProviderTagFilterType.MainCategory, target?.type)
        assertEquals("category-xianxia", target?.providerFilterValue)
    }

    @Test
    fun unrelatedOrFuzzyCategoryIsNotUsed() {
        assertNull(
            resolveProviderTagFilter(
                displayedTag = "Xianxia",
                providerTags = emptyList(),
                mainCategories = listOf("Popular this week" to "popular"),
            )
        )
        assertNull(
            resolveProviderTagFilter(
                displayedTag = "Xianxia",
                providerTags = emptyList(),
                mainCategories = listOf("Xianxia-like" to "xianxia-like"),
            )
        )
    }

    @Test
    fun ambiguousTagDoesNotFallThroughToCategory() {
        assertNull(
            resolveProviderTagFilter(
                displayedTag = "Xianxia",
                providerTags = listOf("Xianxia" to "one", "Xianxia" to "two"),
                mainCategories = listOf("Xianxia" to "category"),
            )
        )
    }

    @Test
    fun noEmptyAllFilterIsMatched() {
        assertNull(
            resolveProviderTagFilter(
                displayedTag = "All",
                providerTags = listOf(" all " to "all-filter"),
                mainCategories = listOf("All" to "all-category"),
            )
        )
    }

    @Test
    fun missingProviderOrFilterDoesNotCreateNavigationTarget() {
        assertNull(
            resolveDetailTagBrowseTarget(
                apiName = null,
                displayedTag = "Xianxia",
                providerTags = listOf("Xianxia" to "xianxia"),
                novelUrl = "https://novel.example/book",
            )
        )
        assertNull(
            resolveDetailTagBrowseTarget(
                apiName = "NovelBin",
                displayedTag = "Xianxia",
                providerTags = emptyList(),
                novelUrl = "https://novel.example/book",
            )
        )
    }
}
