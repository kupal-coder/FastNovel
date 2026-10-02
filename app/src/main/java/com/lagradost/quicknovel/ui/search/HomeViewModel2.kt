package com.lagradost.quicknovel.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.compose.ActionHandler
import com.lagradost.quicknovel.compose.DefaultEffectContainer
import com.lagradost.quicknovel.compose.DefaultStateContainer
import com.lagradost.quicknovel.compose.EffectContainer
import com.lagradost.quicknovel.compose.StateContainer
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.mainpage.FilterQuery
import com.lagradost.quicknovel.ui.result.ProviderTagFilterTarget
import com.lagradost.quicknovel.ui.result.ProviderTagFilterType
import com.lagradost.quicknovel.ui.result.resolveProviderTagFilter
import com.lagradost.quicknovel.ui.search.HomeEffect.NavigateToMainPage
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.cmap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Immutable
data class TagSearchRequest(
    val label: String,
    val sourceProviderName: String,
    val sourceNovelUrl: String,
)

@Immutable
data class HomeViewModelState(
    val allApis: ImmutableList<MainAPI> = Apis.apis.toImmutableList(),
    val mainPageApis: ImmutableList<MainAPI> = allApis.filter { api -> api.hasMainPage }
        .toImmutableList(),
    val shownMainPageApis: ImmutableList<MainAPI> = mainPageApis,
    val filterNames: ImmutableSet<String> = persistentSetOf(),
    val filterLanguages: ImmutableSet<String> = persistentSetOf(),
    val isConfigureShow: Boolean = false,
    val searchRows: PersistentList<SearchRow> = persistentListOf(),
    val isLoading: Boolean = false,
    val isQueryOpen: Boolean = false,
    val openRow: SearchRow? = null,
    val tagSearchRequest: TagSearchRequest? = null,
    val tagSearchUnsupported: Boolean = false,
)

@Immutable
data class SearchRow(
    val name: String,
    val items: ImmutableList<ImmutableSearchResponse> = persistentListOf(),
    val error: Throwable? = null,
    val tagLoading: Boolean = false,
    val tagFailed: Boolean = false,
    val tagPage: Int = 0,
    val tagHasMore: Boolean = false,
)

@Immutable
sealed class HomeAction {
    data class Search(val query: String) : HomeAction()
    data class SearchTag(val request: TagSearchRequest) : HomeAction()
    object RefreshTagSearch : HomeAction()
    object ClearTagSearch : HomeAction()
    data class RetryTagProvider(val providerName: String) : HomeAction()
    data class LoadMoreTagProvider(val providerName: String) : HomeAction()
    object ConfigureApis : HomeAction()
    object DismissConfigureApis : HomeAction()
    data class Open(val item: MainAPI) : HomeAction()
    data class ConfigureApisNames(val names: ImmutableSet<String>) : HomeAction()
    data class ConfigureApisLanguages(val languages: ImmutableSet<String>) : HomeAction()
    data class ConfigureSelection(
        val names: ImmutableSet<String>,
        val languages: ImmutableSet<String>,
    ) : HomeAction()
    object CloseQuery : HomeAction()
    data class ResultAction(val action: SearchResponseAction) : HomeAction()
    data class OpenRow(val row: SearchRow) : HomeAction()
    object CloseRow : HomeAction()
}

@Immutable
sealed class HomeEffect {
    data class NavigateToMainPage(val api: String, val filter: FilterQuery) : HomeEffect()
    object NavigateBack : HomeEffect()
}

class HomeViewModel2 : ViewModel(),
    StateContainer<HomeViewModelState> by DefaultStateContainer(HomeViewModelState()),
    ActionHandler<HomeAction>,
    EffectContainer<HomeEffect> by DefaultEffectContainer() {

    /*companion object {
        // Todo do this with injection of perf instead?
        fun provideFactory(selection: PreferenceData<Set<String>>) = viewModelFactory {
            initializer {
                val selection = selection.get()
                val state = HomeViewModelState()
                HomeViewModel2(
                    state.copy(shownMainPageApis = state.shownMainPageApis.filter { api ->
                        selection.contains(
                            api.name
                        )
                    }.toPersistentList())
                )
            }
        }
    }*/

    fun filterApis(
        apis: ImmutableList<MainAPI>,
        names: ImmutableSet<String>,
        languages: ImmutableSet<String>
    ): ImmutableList<MainAPI> {
        if (names.isEmpty() || languages.isEmpty()) return persistentListOf()

        return apis.filter { api ->
            languages.contains(api.lang) && names.contains(api.name)
        }.toPersistentList()
    }

    override fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.ConfigureApis -> updateState { copy(isConfigureShow = true) }
            is HomeAction.Open -> viewModelScope.launch {
                postEffect { NavigateToMainPage(action.item.name, FilterQuery()) }
            }
            is HomeAction.Search -> search(action.query)
            is HomeAction.SearchTag -> searchTag(action.request)
            HomeAction.RefreshTagSearch -> state.value.tagSearchRequest?.let(::searchTag)
            HomeAction.ClearTagSearch -> {
                if (state.value.tagSearchRequest != null) {
                    cancelSearches()
                    updateState {
                        copy(
                            isQueryOpen = false,
                            isLoading = false,
                            searchRows = persistentListOf(),
                            tagSearchRequest = null,
                            tagSearchUnsupported = false,
                        )
                    }
                }
            }
            is HomeAction.RetryTagProvider -> loadNextTagPage(action.providerName, retry = true)
            is HomeAction.LoadMoreTagProvider -> loadNextTagPage(action.providerName, retry = false)
            HomeAction.DismissConfigureApis -> updateState { copy(isConfigureShow = false) }
            is HomeAction.ConfigureApisNames -> updateState {
                copy(
                    filterNames = action.names,
                    shownMainPageApis = filterApis(mainPageApis, action.names, filterLanguages)
                )
            }
            is HomeAction.ConfigureApisLanguages -> updateState {
                copy(
                    filterLanguages = action.languages,
                    shownMainPageApis = filterApis(mainPageApis, filterNames, action.languages)
                )
            }
            is HomeAction.ConfigureSelection -> updateState {
                copy(
                    filterNames = action.names,
                    filterLanguages = action.languages,
                    shownMainPageApis = filterApis(mainPageApis, action.names, action.languages)
                )
            }
            HomeAction.CloseQuery -> {
                if (state.value.tagSearchRequest != null) {
                    cancelSearches()
                    viewModelScope.launch { postEffect { HomeEffect.NavigateBack } }
                } else {
                    cancelSearches()
                    updateState { copy(isQueryOpen = false, isLoading = false, searchRows = persistentListOf()) }
                }
            }
            is HomeAction.ResultAction -> resultAction(action.action)
            HomeAction.CloseRow -> updateState { copy(openRow = null) }
            is HomeAction.OpenRow -> updateState { copy(openRow = action.row) }
        }
    }

    private fun resultAction(action: SearchResponseAction) {
        action.doAction()
    }

    @Volatile
    private var searchGeneration = 0L
    private var activeSearchJob: Job? = null
    private val providerPageJobs = mutableMapOf<String, Job>()
    private var providerTagFilters: Map<String, Pair<MainAPI, ProviderTagFilterTarget>> = emptyMap()

    private fun cancelSearches() {
        searchGeneration += 1
        activeSearchJob?.cancel()
        activeSearchJob = null
        providerPageJobs.values.forEach(Job::cancel)
        providerPageJobs.clear()
        providerTagFilters = emptyMap()
    }

    private fun search(query: String) {
        cancelSearches()
        val generation = searchGeneration
        val current = state.value
        val searchApis = filterApis(current.allApis, current.filterNames, current.filterLanguages)
        updateState {
            copy(
                isQueryOpen = true,
                isLoading = true,
                searchRows = persistentListOf(),
                openRow = null,
                tagSearchRequest = null,
                tagSearchUnsupported = false,
            )
        }

        activeSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                searchApis.cmap { api ->
                    APIRepository(api).searchResult(query).onSuccess { value ->
                        val row = SearchRow(name = api.name, items = value.toImmutableList())
                        updateState {
                            if (generation != searchGeneration || tagSearchRequest != null) this
                            else if (value.isEmpty()) {
                                copy(searchRows = searchRows.adding(row))
                            } else {
                                val hasResults = searchRows.count { it.items.isNotEmpty() }
                                copy(searchRows = searchRows.addingAt(hasResults, row))
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        updateState {
                            if (generation != searchGeneration || tagSearchRequest != null) this
                            else copy(searchRows = searchRows.adding(SearchRow(name = api.name, error = error)))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Provider errors are kept per row; never surface raw exception/server text.
            } finally {
                updateState {
                    if (generation != searchGeneration) this else copy(isLoading = false)
                }
            }
        }
    }

    private fun searchTag(request: TagSearchRequest) {
        cancelSearches()
        val generation = searchGeneration
        val current = state.value
        val enabledApis = filterApis(current.allApis, current.filterNames, current.filterLanguages)
        val filters = enabledApis.mapNotNull { api ->
            if (!api.hasMainPage) return@mapNotNull null
            val filter = resolveProviderTagFilter(request.label, api.tags, api.mainCategories)
                ?: return@mapNotNull null
            api to filter
        }
        providerTagFilters = filters.associateBy { it.first.name }

        updateState {
            copy(
                isQueryOpen = true,
                isLoading = filters.isNotEmpty(),
                searchRows = filters.map { (api, _) ->
                    SearchRow(name = api.name, tagLoading = true)
                }.toPersistentList(),
                openRow = null,
                tagSearchRequest = request,
                tagSearchUnsupported = filters.isEmpty(),
            )
        }

        if (filters.isEmpty()) return

        activeSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                filters.cmap { (api, filter) ->
                    loadTagPage(request, api, filter, page = 1, generation = generation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A provider's failure is recorded independently by loadTagPage.
            } finally {
                updateState {
                    if (generation != searchGeneration) this
                    else copy(isLoading = searchRows.any { it.tagLoading })
                }
            }
        }
    }

    private fun loadNextTagPage(providerName: String, retry: Boolean) {
        val request = state.value.tagSearchRequest ?: return
        val row = state.value.searchRows.firstOrNull { it.name == providerName } ?: return
        val providerFilter = providerTagFilters[providerName] ?: return
        if (row.tagLoading || (!retry && !row.tagHasMore)) return

        val generation = searchGeneration
        val page = row.tagPage + 1
        updateTagRow(providerName, generation) { copy(tagLoading = true, tagFailed = false) }
        providerPageJobs.remove(providerName)?.cancel()
        providerPageJobs[providerName] = viewModelScope.launch(Dispatchers.IO) {
            loadTagPage(request, providerFilter.first, providerFilter.second, page, generation)
        }
    }

    private suspend fun loadTagPage(
        request: TagSearchRequest,
        api: MainAPI,
        filter: ProviderTagFilterTarget,
        page: Int,
        generation: Long,
    ) {
        try {
            val response = APIRepository(api).loadMainPageResult(
                page = page,
                mainCategory = filter.providerFilterValue.takeIf {
                    filter.type == ProviderTagFilterType.MainCategory
                },
                orderBy = null,
                tag = filter.providerFilterValue.takeIf {
                    filter.type == ProviderTagFilterType.Tag
                },
            )

            if (generation != searchGeneration || state.value.tagSearchRequest != request) return
            response.onFailure { error ->
                if (error is CancellationException) throw error
                updateTagRow(providerName = api.name, generation = generation) {
                    copy(tagLoading = false, tagFailed = true)
                }
            }.onSuccess { result ->
                val sourceUrl = request.sourceNovelUrl.takeIf { api.name == request.sourceProviderName }
                    ?.let(api::fixUrl)
                val incoming = result.list.filterNot { item ->
                    sourceUrl != null && api.fixUrl(item.url) == sourceUrl
                }
                updateTagRow(providerName = api.name, generation = generation) {
                    val seenUrls = items.mapTo(mutableSetOf()) { it.url }
                    val uniqueIncoming = incoming.filter { seenUrls.add(it.url) }
                    copy(
                        items = (items + uniqueIncoming).toPersistentList(),
                        tagLoading = false,
                        tagFailed = false,
                        tagPage = page,
                        tagHasMore = result.list.isNotEmpty(),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (generation == searchGeneration && state.value.tagSearchRequest == request) {
                updateTagRow(providerName = api.name, generation = generation) {
                    copy(tagLoading = false, tagFailed = true)
                }
            }
        }
    }

    private fun updateTagRow(
        providerName: String,
        generation: Long,
        transform: SearchRow.() -> SearchRow,
    ) {
        updateState {
            if (generation != searchGeneration || tagSearchRequest == null) this
            else {
                val updatedRows = searchRows.map { row ->
                    if (row.name == providerName) row.transform() else row
                }.toPersistentList()
                copy(
                    searchRows = updatedRows,
                    isLoading = updatedRows.any { it.tagLoading },
                )
            }
        }
    }
}
