package com.lagradost.quicknovel.ui.mainpage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.common.SearchResponseOperation
import com.lagradost.quicknovel.ui.common.updateItem
import com.lagradost.quicknovel.ui.result.DetailTagBrowseTarget
import com.lagradost.quicknovel.ui.result.DetailTagFilterKind
import com.lagradost.quicknovel.ui.result.buildDetailTagMainPageRequest
import com.lagradost.quicknovel.ui.result.resolveEnabledProviderTagTargets
import com.lagradost.quicknovel.util.Apis
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ProviderTagSection(
    val apiName: String,
    val tagValue: String,
    val filterKind: DetailTagFilterKind,
    val items: PersistentList<ImmutableSearchResponse> = persistentListOf(),
    val currentPage: Int = 0,
    val canLoadMore: Boolean = true,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasError: Boolean = false,
)

@Immutable
data class TagSearchUiState(
    val tagLabel: String = "",
    val isConfigured: Boolean = false,
    val sections: ImmutableList<ProviderTagSection> = persistentListOf(),
) {
    val isUnsupportedTag: Boolean
        get() = isConfigured && sections.isEmpty()

    val isAllInitialLoading: Boolean
        get() = sections.isNotEmpty() && sections.all { it.isInitialLoading }

    val isAllProvidersFailed: Boolean
        get() = sections.isNotEmpty() &&
            sections.none { it.isInitialLoading || it.isLoadingMore } &&
            sections.all { it.hasError && it.items.isEmpty() }

    val isAllProvidersEmpty: Boolean
        get() = sections.isNotEmpty() &&
            sections.none { it.isInitialLoading || it.isLoadingMore || it.hasError } &&
            sections.all { it.items.isEmpty() }
}

internal fun interface TagPageLoader {
    suspend fun loadPage(
        api: MainAPI,
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): Result<List<ImmutableSearchResponse>>
}

class TagSearchViewModel : ViewModel() {
    private val _state = MutableStateFlow(TagSearchUiState())
    val state = _state.asStateFlow()

    private var rawTagLabel: String = ""
    private var sourceApiName: String? = null
    private var excludeNovelUrl: String? = null

    private var enabledNames: ImmutableSet<String> = persistentSetOf()
    private var enabledLanguages: ImmutableSet<String> = persistentSetOf()
    private var hasReceivedNames = false
    private var hasReceivedLanguages = false

    private val providerLookup = LinkedHashMap<String, Pair<MainAPI, DetailTagBrowseTarget>>()
    private val inFlightJobs = HashMap<String, Job>()
    private val repositories = HashMap<String, APIRepository>()

    internal var availableApisProvider: () -> List<MainAPI> = { Apis.apis }
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var coroutineScope: CoroutineScope? = null
    private val activeScope: CoroutineScope
        get() = coroutineScope ?: viewModelScope
    internal var pageLoader: TagPageLoader = TagPageLoader { api, page, mainCategory, orderBy, tag ->
        val repo = synchronized(repositories) {
            repositories.getOrPut(api.name) { APIRepository(api) }
        }
        repo.loadMainPageResult(
            page = page,
            mainCategory = mainCategory,
            orderBy = orderBy,
            tag = tag,
        ).map { it.list }
    }

    fun init(
        tagLabel: String,
        sourceApiName: String? = null,
        excludeNovelUrl: String? = null,
    ) {
        val trimmed = tagLabel.trim()
        val sameContext = this.rawTagLabel == trimmed &&
            this.sourceApiName == sourceApiName &&
            this.excludeNovelUrl == excludeNovelUrl &&
            _state.value.tagLabel == trimmed
        if (sameContext) return

        this.rawTagLabel = trimmed
        this.sourceApiName = sourceApiName
        this.excludeNovelUrl = excludeNovelUrl
        inFlightJobs.values.forEach { it.cancel() }
        inFlightJobs.clear()
        providerLookup.clear()

        _state.update {
            TagSearchUiState(
                tagLabel = trimmed,
                isConfigured = false,
                sections = persistentListOf(),
            )
        }

        if (hasReceivedNames && hasReceivedLanguages) {
            rebuildTargetsAndLoad()
        }
    }

    fun configureProviderNames(names: ImmutableSet<String>) {
        if (hasReceivedNames && enabledNames == names) return
        enabledNames = names
        hasReceivedNames = true
        if (hasReceivedLanguages && rawTagLabel.isNotEmpty()) {
            rebuildTargetsAndLoad()
        }
    }

    fun configureProviderLanguages(languages: ImmutableSet<String>) {
        if (hasReceivedLanguages && enabledLanguages == languages) return
        enabledLanguages = languages
        hasReceivedLanguages = true
        if (hasReceivedNames && rawTagLabel.isNotEmpty()) {
            rebuildTargetsAndLoad()
        }
    }

    private fun rebuildTargetsAndLoad() {
        val resolved = resolveEnabledProviderTagTargets(
            apis = availableApisProvider(),
            enabledProviderNames = enabledNames,
            enabledLanguages = enabledLanguages,
            rawTagLabel = rawTagLabel,
            novelUrl = excludeNovelUrl,
            prioritizeApiName = sourceApiName,
        )

        val resolvedNames = resolved.map { it.first.name }.toSet()
        val removedNames = inFlightJobs.keys.filter { it !in resolvedNames }
        for (removed in removedNames) {
            inFlightJobs.remove(removed)?.cancel()
        }

        providerLookup.clear()
        for (pair in resolved) {
            providerLookup[pair.first.name] = pair
        }

        val existingByApi = _state.value.sections.associateBy { it.apiName }
        val sectionsToLoad = ArrayList<String>()

        val newSections = resolved.map { (api, target) ->
            val existing = existingByApi[api.name]
            if (
                existing != null &&
                existing.tagValue == target.providerFilterValue &&
                existing.filterKind == target.filterKind
            ) {
                existing
            } else {
                sectionsToLoad.add(api.name)
                ProviderTagSection(
                    apiName = api.name,
                    tagValue = target.providerFilterValue,
                    filterKind = target.filterKind,
                    items = persistentListOf(),
                    currentPage = 0,
                    canLoadMore = true,
                    isInitialLoading = true,
                    isLoadingMore = false,
                    hasError = false,
                )
            }
        }.toPersistentList()

        _state.update {
            it.copy(
                tagLabel = rawTagLabel,
                isConfigured = true,
                sections = newSections,
            )
        }

        for (apiName in sectionsToLoad) {
            loadProviderNextPage(apiName, forceRetry = true)
        }
    }

    fun loadMore(apiName: String) {
        loadProviderNextPage(apiName, forceRetry = false)
    }

    fun retryProvider(apiName: String) {
        loadProviderNextPage(apiName, forceRetry = true)
    }

    fun retryAllFailed() {
        val failedNames = _state.value.sections
            .filter { it.hasError }
            .map { it.apiName }
        for (apiName in failedNames) {
            loadProviderNextPage(apiName, forceRetry = true)
        }
    }

    private fun loadProviderNextPage(apiName: String, forceRetry: Boolean) {
        val (api, target) = providerLookup[apiName] ?: return
        val currentSection = _state.value.sections.firstOrNull { it.apiName == apiName } ?: return

        if (inFlightJobs[apiName]?.isActive == true) return
        if (!forceRetry && (!currentSection.canLoadMore || currentSection.hasError || currentSection.isInitialLoading || currentSection.isLoadingMore)) {
            return
        }

        val nextPage = currentSection.currentPage + 1
        val isInitial = nextPage == 1 && currentSection.items.isEmpty()

        updateSection(apiName) { section ->
            section.copy(
                isInitialLoading = isInitial,
                isLoadingMore = !isInitial,
                hasError = false,
            )
        }

        val request = buildDetailTagMainPageRequest(api, target)
        val excluded = excludeNovelUrl?.trim()?.trimEnd('/')

        val job = activeScope.launch(ioDispatcher) {
            val result = pageLoader.loadPage(
                api = api,
                page = nextPage,
                mainCategory = request.mainCategory,
                orderBy = request.orderBy,
                tag = request.tag,
            )

            result.onSuccess { fetchedList ->
                val filteredItems = fetchedList.filterNot { item ->
                    !excluded.isNullOrEmpty() && item.url.trim().trimEnd('/') == excluded
                }
                updateSection(apiName) { section ->
                    val existingUrls = section.items
                        .map { it.url.trim().trimEnd('/') }
                        .toHashSet()
                    val uniqueNewItems = filteredItems.filter { item ->
                        val key = item.url.trim().trimEnd('/')
                        key.isEmpty() || existingUrls.add(key)
                    }
                    val merged = (section.items + uniqueNewItems).toPersistentList()
                    val canContinue = fetchedList.isNotEmpty() &&
                        (nextPage == 1 || uniqueNewItems.isNotEmpty())
                    section.copy(
                        items = merged,
                        currentPage = nextPage,
                        canLoadMore = canContinue,
                        isInitialLoading = false,
                        isLoadingMore = false,
                        hasError = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@launch
                updateSection(apiName) { section ->
                    section.copy(
                        isInitialLoading = false,
                        isLoadingMore = false,
                        hasError = true,
                    )
                }
            }
        }
        inFlightJobs[apiName] = job
    }

    private fun updateSection(
        apiName: String,
        transform: (ProviderTagSection) -> ProviderTagSection,
    ) {
        _state.update { current ->
            val updated = current.sections.map { section ->
                if (section.apiName == apiName) transform(section) else section
            }.toPersistentList()
            current.copy(sections = updated)
        }
    }

    fun onResultAction(action: SearchResponseAction) {
        when (action.operation) {
            SearchResponseOperation.Stream -> {
                viewModelScope.launch {
                    updateSection(action.response.apiName) { section ->
                        section.copy(
                            items = section.items.updateItem(action.response) {
                                copy(generating = true)
                            }.toPersistentList()
                        )
                    }
                    BookDownloader2.stream(action.response)
                    updateSection(action.response.apiName) { section ->
                        section.copy(
                            items = section.items.updateItem(action.response) {
                                copy(generating = false)
                            }.toPersistentList()
                        )
                    }
                }
            }
            else -> {
                action.doAction()
            }
        }
    }
}
