package com.lagradost.quicknovel.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_CHAPTER
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.auth.SupabaseAuth
import com.lagradost.quicknovel.discover.DiscoverApi
import com.lagradost.quicknovel.discover.DiscoverError
import com.lagradost.quicknovel.discover.DiscoverPost
import com.lagradost.quicknovel.discover.DiscoverResult
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Apis.Companion.apis
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NovelHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutableState = MutableStateFlow(HomeUiState())
    val state = mutableState.asStateFlow()

    private var latestJob: Job? = null
    private var popularJob: Job? = null
    private var communityJob: Job? = null
    private var communityUserId: String? = null

    init {
        refreshContinueReading()
        reloadNewNovels()
    }

    fun onResume() {
        refreshContinueReading()
        refreshCommunityForCurrentSession()
    }

    fun retry(carousel: HomeCarousel) {
        when (carousel) {
            HomeCarousel.New -> reloadNewNovels()
            HomeCarousel.Popular -> retryPopular()
            HomeCarousel.Community -> loadCommunity()
        }
    }

    private fun refreshContinueReading() {
        viewModelScope.launch(Dispatchers.IO) {
            val novel = latestHistoryItem()
            val continueItem = novel?.let {
                val chapterName = context.getKey<String>(EPUB_CURRENT_POSITION_CHAPTER, it.name)
                    ?.takeIf(String::isNotBlank)
                val chapterNumber = context.getKey<Int>(EPUB_CURRENT_POSITION, it.name)
                    ?.takeIf { index -> index >= 0 }
                    ?.let { index -> index + 1 }
                ContinueReadingItem(it, chapterName, chapterNumber)
            }
            mutableState.update { it.copy(continueReading = continueItem) }
        }
    }

    private fun latestHistoryItem(): ResultCached? {
        return context.getKeys(HISTORY_FOLDER)
            .orEmpty()
            .mapNotNull { key -> context.getKey<ResultCached>(key) }
            .maxByOrNull { it.cachedTime }
    }

    private fun recentProviderName(): String? = latestHistoryItem()?.apiName

    private fun enabledProviderNames(): Set<String> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val allNames = apis.map { it.name }.toSet()
        val selectedNames = preferences.getStringSet(
            context.getString(R.string.search_providers_list_key),
            allNames,
        )?.toSet().orEmpty()
        val selectedLanguages = preferences.getStringSet(
            context.getString(R.string.provider_lang_key),
            setOf("en"),
        )?.toSet().orEmpty().ifEmpty { setOf("en") }

        val filtered = apis.asSequence()
            .filter { it.name in selectedNames && it.lang in selectedLanguages }
            .map { it.name }
            .toSet()
        // Match the existing provider selection behavior: never leave the user with no source.
        return filtered.ifEmpty { allNames }
    }

    private fun providerCandidates(): List<com.lagradost.quicknovel.MainAPI> =
        orderHomeProviders(
            providers = Apis.apis,
            enabledProviderNames = enabledProviderNames(),
            preferredProviderName = recentProviderName(),
        )

    private fun reloadNewNovels() {
        latestJob?.cancel()
        popularJob?.cancel()
        mutableState.update {
            it.copy(
                newNovels = HomeCarouselState.Loading,
                popularNovels = HomeCarouselState.Hidden,
                newPage = null,
                popularPage = null,
            )
        }

        latestJob = viewModelScope.launch {
            val candidates = withContext(Dispatchers.IO) { providerCandidates() }
            val first = candidates.firstOrNull()
            val initialNewSelection = first?.let(::latestPageSelection)
            val initialNewTarget = if (first != null && initialNewSelection != null) {
                pageTarget(first, initialNewSelection)
            } else {
                null
            }
            val initialPopularSelection = first?.let(::popularPageSelection)
                ?.takeIf { it != initialNewSelection }
            val initialPopularTarget = if (first != null && initialPopularSelection != null) {
                pageTarget(first, initialPopularSelection)
            } else {
                null
            }
            mutableState.update {
                it.copy(
                    popularNovels = if (initialPopularTarget == null) {
                        HomeCarouselState.Hidden
                    } else {
                        HomeCarouselState.Loading
                    },
                    newPage = initialNewTarget,
                    popularPage = initialPopularTarget,
                )
            }

            val latest = loadLatestFrom(candidates)
            if (latest == null) {
                mutableState.update {
                    it.copy(
                        newNovels = HomeCarouselState.Error,
                        popularNovels = if (it.popularPage == null) {
                            HomeCarouselState.Hidden
                        } else {
                            HomeCarouselState.Error
                        },
                    )
                }
                return@launch
            }

            val popularSelection = popularPageSelection(latest.api)
                ?.takeIf { it != latest.selection }
            val newTarget = pageTarget(latest.api, latest.selection)
            val popularTarget = popularSelection?.let { pageTarget(latest.api, it) }
            mutableState.update {
                it.copy(
                    newNovels = latest.items.toNovelCarouselState(),
                    popularNovels = if (popularTarget == null) {
                        HomeCarouselState.Hidden
                    } else {
                        HomeCarouselState.Loading
                    },
                    newPage = newTarget,
                    popularPage = popularTarget,
                )
            }

            if (popularSelection != null) {
                loadPopular(latest.api, popularSelection)
            }
        }
    }

    private suspend fun loadLatestFrom(
        candidates: List<com.lagradost.quicknovel.MainAPI>,
    ): LatestProviderPage? {
        var firstEmptyPage: LatestProviderPage? = null
        for (api in candidates) {
            val selection = latestPageSelection(api)
            val result = try {
                withContext(Dispatchers.IO) {
                    api.loadMainPage(
                        page = 1,
                        mainCategory = api.mainCategories.getOrNull(selection.categoryIndex)?.second,
                        orderBy = api.orderBys.getOrNull(selection.orderByIndex)?.second,
                        tag = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            } catch (_: NotImplementedError) {
                continue
            }

            val page = LatestProviderPage(api, selection, result.list)
            if (result.list.isNotEmpty()) return page
            if (firstEmptyPage == null) firstEmptyPage = page
        }
        return firstEmptyPage
    }

    private fun loadPopular(
        api: com.lagradost.quicknovel.MainAPI,
        selection: HomePageSelection,
    ) {
        popularJob?.cancel()
        popularJob = viewModelScope.launch {
            val page = try {
                withContext(Dispatchers.IO) {
                    api.loadMainPage(
                        page = 1,
                        mainCategory = api.mainCategories.getOrNull(selection.categoryIndex)?.second,
                        orderBy = api.orderBys.getOrNull(selection.orderByIndex)?.second,
                        tag = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(popularNovels = HomeCarouselState.Error) }
                return@launch
            } catch (_: NotImplementedError) {
                mutableState.update { it.copy(popularNovels = HomeCarouselState.Error) }
                return@launch
            }
            mutableState.update { it.copy(popularNovels = page.list.toNovelCarouselState()) }
        }
    }

    private fun retryPopular() {
        val target = mutableState.value.popularPage ?: return
        val api = apis.firstOrNull { it.name == target.apiName } ?: run {
            mutableState.update { it.copy(popularNovels = HomeCarouselState.Error) }
            return
        }
        val selection = HomePageSelection(target.categoryIndex, target.orderByIndex)
        mutableState.update { it.copy(popularNovels = HomeCarouselState.Loading) }
        loadPopular(api, selection)
    }

    private fun refreshCommunityForCurrentSession() {
        val userId = currentUserId()
        if (userId == null) {
            communityJob?.cancel()
            communityUserId = null
            mutableState.update { it.copy(communityPosts = HomeCarouselState.Hidden) }
            return
        }
        if (communityUserId != userId || mutableState.value.communityPosts is HomeCarouselState.Hidden) {
            communityUserId = userId
            loadCommunity()
        }
    }

    private fun currentUserId(): String? =
        SupabaseAuth.currentUserId(context).takeIf { SupabaseAuth.isLoggedIn(context) }

    private fun loadCommunity() {
        val userId = currentUserId() ?: run {
            communityUserId = null
            mutableState.update { it.copy(communityPosts = HomeCarouselState.Hidden) }
            return
        }

        communityUserId = userId
        communityJob?.cancel()
        mutableState.update { it.copy(communityPosts = HomeCarouselState.Loading) }
        communityJob = viewModelScope.launch {
            when (val result = DiscoverApi.feed(context, offset = 0, tag = null)) {
                is DiscoverResult.Success -> {
                    if (currentUserId() != userId) {
                        refreshCommunityForCurrentSession()
                        return@launch
                    }
                    mutableState.update { it.copy(communityPosts = result.value.toPostCarouselState()) }
                }
                is DiscoverResult.Failure -> {
                    val activeUserId = currentUserId()
                    if (activeUserId != userId) {
                        refreshCommunityForCurrentSession()
                    } else if (result.error == DiscoverError.SignIn) {
                        mutableState.update { it.copy(communityPosts = HomeCarouselState.Hidden) }
                    } else {
                        mutableState.update { it.copy(communityPosts = HomeCarouselState.Error) }
                    }
                }
            }
        }
    }

    private fun pageTarget(
        api: com.lagradost.quicknovel.MainAPI,
        selection: HomePageSelection,
    ) = HomePageTarget(
        apiName = api.name,
        categoryIndex = selection.categoryIndex,
        orderByIndex = selection.orderByIndex,
    )

    private fun List<SearchResponse>.toNovelCarouselState(): HomeCarouselState<SearchResponse> =
        if (isEmpty()) HomeCarouselState.Empty else HomeCarouselState.Loaded(this)

    private fun List<DiscoverPost>.toPostCarouselState(): HomeCarouselState<DiscoverPost> =
        if (isEmpty()) HomeCarouselState.Empty else HomeCarouselState.Loaded(this)

    private data class LatestProviderPage(
        val api: com.lagradost.quicknovel.MainAPI,
        val selection: HomePageSelection,
        val items: List<SearchResponse>,
    )
}
