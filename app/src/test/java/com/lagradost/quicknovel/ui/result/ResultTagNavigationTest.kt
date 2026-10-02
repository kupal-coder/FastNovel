package com.lagradost.quicknovel.ui.result

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.providers.LightNovelTranslationsProvider
import com.lagradost.quicknovel.providers.NovelBinProvider
import com.lagradost.quicknovel.providers.RanobeHubProvider
import com.lagradost.quicknovel.providers.SyosetuProvider
import com.lagradost.quicknovel.providers.WattpadProvider
import com.lagradost.quicknovel.providers.WtrLabProvider
import com.lagradost.quicknovel.providers.WuxiaClickProvider
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.mainpage.TagPageLoader
import com.lagradost.quicknovel.ui.mainpage.TagSearchViewModel
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    @Test
    fun matchesGenreMainCategoriesWhenProviderExposesGenresInMainCategories() {
        val wuxiaClick = WuxiaClickProvider()
        val syosetu = SyosetuProvider()

        val wuxiaTarget = resolveDetailTagBrowseTarget(
            api = wuxiaClick,
            displayedTag = "  fantasy ",
            novelUrl = "https://wuxia.click/novel/example",
        )
        assertNotNull(wuxiaTarget)
        assertEquals(DetailTagFilterKind.MAIN_CATEGORY, wuxiaTarget?.filterKind)
        assertEquals("fantasy", wuxiaTarget?.providerFilterValue)

        val wuxiaReq = buildDetailTagMainPageRequest(wuxiaClick, wuxiaTarget!!)
        assertEquals("fantasy", wuxiaReq.mainCategory)
        assertEquals("", wuxiaReq.tag)

        val syosetuTarget = resolveDetailTagBrowseTarget(
            api = syosetu,
            displayedTag = "Action",
            novelUrl = "https://ncode.syosetu.com/n1234ab",
        )
        assertNotNull(syosetuTarget)
        assertEquals(DetailTagFilterKind.MAIN_CATEGORY, syosetuTarget?.filterKind)
        assertEquals("306", syosetuTarget?.providerFilterValue)

        val syosetuReq = buildDetailTagMainPageRequest(syosetu, syosetuTarget!!)
        assertEquals("306", syosetuReq.mainCategory)
        assertEquals("total", syosetuReq.orderBy)
        assertNull(syosetuReq.tag)
    }

    @Test
    fun rejectsNonGenreStatusSortAndLanguageFilters() {
        assertNull(
            resolveDetailTagBrowseTarget(
                api = LightNovelTranslationsProvider(),
                displayedTag = "Ongoing",
            )
        )
        assertNull(
            resolveDetailTagBrowseTarget(
                api = RanobeHubProvider(),
                displayedTag = "Completed",
            )
        )
        assertNull(
            resolveDetailTagBrowseTarget(
                api = WtrLabProvider(),
                displayedTag = "Ongoing",
            )
        )
        assertNull(
            resolveDetailTagBrowseTarget(
                api = WattpadProvider(),
                displayedTag = "English",
            )
        )
    }

    @Test
    fun resolvesAcrossEnabledProvidersAndLanguagesWithEachProvidersOwnFilterValue() {
        val wtrLab = WtrLabProvider()
        val novelBin = NovelBinProvider()
        val syosetu = SyosetuProvider()
        val lnt = LightNovelTranslationsProvider()

        val resolved = resolveEnabledProviderTagTargets(
            apis = listOf(wtrLab, novelBin, syosetu, lnt),
            enabledProviderNames = setOf(wtrLab.name, novelBin.name, syosetu.name, lnt.name),
            enabledLanguages = setOf("en", "ja"),
            rawTagLabel = "Action",
            prioritizeApiName = novelBin.name,
        )

        assertEquals(listOf(novelBin.name, wtrLab.name, syosetu.name), resolved.map { it.first.name })
        assertEquals("action", resolved[0].second.providerFilterValue)
        assertEquals(DetailTagFilterKind.TAG, resolved[0].second.filterKind)
        assertEquals("1", resolved[1].second.providerFilterValue)
        assertEquals(DetailTagFilterKind.TAG, resolved[1].second.filterKind)
        assertEquals("306", resolved[2].second.providerFilterValue)
        assertEquals(DetailTagFilterKind.MAIN_CATEGORY, resolved[2].second.filterKind)
    }

    @Test
    fun tagSearchViewModelHandlesPartialFailureRetryAndPaginationPerProvider() {
        val providerA = object : MainAPI() {
            override val name = "ProviderA"
            override val mainUrl = "https://a.example"
            override val lang = "en"
            override val hasMainPage = true
            override val tags = listOf("Action" to "act-a")
        }
        val providerB = object : MainAPI() {
            override val name = "ProviderB"
            override val mainUrl = "https://b.example"
            override val lang = "en"
            override val hasMainPage = true
            override val tags = listOf("Action" to "act-b")
        }

        var providerBShouldFail = true
        val viewModel = TagSearchViewModel().apply {
            availableApisProvider = { listOf(providerA, providerB) }
            ioDispatcher = Dispatchers.Unconfined
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            pageLoader = TagPageLoader { api, page, _, _, tag ->
                if (api.name == "ProviderB" && providerBShouldFail) {
                    Result.failure(IOException("ProviderB network error"))
                } else {
                    Result.success(
                        listOf(
                            ImmutableSearchResponse(
                                name = "${api.name}-$tag-p$page",
                                url = "${api.mainUrl}/novel-$page",
                                apiName = api.name,
                                timeOfCached = 0L,
                                chaptersRead = 0,
                            )
                        )
                    )
                }
            }
        }

        viewModel.init(tagLabel = "Action")
        viewModel.configureProviderNames(persistentSetOf("ProviderA", "ProviderB"))
        viewModel.configureProviderLanguages(persistentSetOf("en"))

        val afterInit = viewModel.state.value
        assertFalse(afterInit.isUnsupportedTag)
        assertFalse(afterInit.isAllProvidersFailed)
        assertEquals(2, afterInit.sections.size)

        val sectionA = afterInit.sections.first { it.apiName == "ProviderA" }
        val sectionB = afterInit.sections.first { it.apiName == "ProviderB" }
        assertEquals(1, sectionA.items.size)
        assertFalse(sectionA.hasError)
        assertTrue(sectionB.items.isEmpty())
        assertTrue(sectionB.hasError)

        // Paginate ProviderA independently
        viewModel.loadMore("ProviderA")
        val afterPage2 = viewModel.state.value.sections.first { it.apiName == "ProviderA" }
        assertEquals(2, afterPage2.currentPage)
        assertEquals(2, afterPage2.items.size)

        // Retry ProviderB independently and succeed
        providerBShouldFail = false
        viewModel.retryProvider("ProviderB")
        val retriedB = viewModel.state.value.sections.first { it.apiName == "ProviderB" }
        assertFalse(retriedB.hasError)
        assertEquals(1, retriedB.items.size)
        assertEquals("ProviderB-act-b-p1", retriedB.items.single().name)
    }
}
