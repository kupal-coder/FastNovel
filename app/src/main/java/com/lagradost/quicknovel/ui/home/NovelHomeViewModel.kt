package com.lagradost.quicknovel.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_CHAPTER
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One provider's answer to a "Random novels" roll. */
private sealed interface RandomProviderOutcome {
    data class Items(val items: List<SearchResponse>) : RandomProviderOutcome
    data object Empty : RandomProviderOutcome
    data object Failed : RandomProviderOutcome
}

private const val RANDOM_PROVIDER_TIMEOUT_MS = 12_000L

class NovelHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutableState = MutableStateFlow(HomeUiState())
    val state = mutableState.asStateFlow()

    private var randomJob: Job? = null

    init {
        refreshContinueReading()
        loadRandomNovels()
    }

    fun onResume() {
        refreshContinueReading()
    }

    fun retry() = loadRandomNovels()

    /** The header "Shuffle" pill re-rolls providers, pages and the row order. */
    fun shuffle() = loadRandomNovels()

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

    private fun enabledProviderNames(): Set<String> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val allNames = Apis.apis.map { it.name }.toSet()
        val selectedNames = preferences.getStringSet(
            context.getString(R.string.search_providers_list_key),
            allNames,
        )?.toSet().orEmpty()
        val selectedLanguages = preferences.getStringSet(
            context.getString(R.string.provider_lang_key),
            setOf("en"),
        )?.toSet().orEmpty().ifEmpty { setOf("en") }

        val filtered = Apis.apis.asSequence()
            .filter { it.name in selectedNames && it.lang in selectedLanguages }
            .map { it.name }
            .toSet()
        // Match the existing provider selection behavior: never leave the user with no source.
        return filtered.ifEmpty { allNames }
    }

    // Random novels: up to three random providers, asked in parallel, shown progressively.

    private fun loadRandomNovels() {
        randomJob?.cancel()
        mutableState.update { it.copy(randomNovels = RandomNovelsState(loading = true)) }
        randomJob = viewModelScope.launch {
            val picks = withContext(Dispatchers.IO) {
                pickRandomProviders(
                    providers = Apis.apis,
                    enabledProviderNames = enabledProviderNames(),
                    count = RANDOM_PROVIDERS_PER_ROLL,
                )
            }
            if (picks.isEmpty()) {
                mutableState.update { it.copy(randomNovels = RandomNovelsState(loading = false)) }
                return@launch
            }
            coroutineScope {
                picks.forEach { api ->
                    launch {
                        // A timeout counts as a failure for that provider, not for the row.
                        val outcome = withTimeoutOrNull(RANDOM_PROVIDER_TIMEOUT_MS) {
                            randomProviderNovels(api)
                        } ?: RandomProviderOutcome.Failed
                        when (outcome) {
                            is RandomProviderOutcome.Items -> mutableState.update { home ->
                                home.copy(
                                    randomNovels = home.randomNovels.copy(
                                        items = mergeRandomNovels(home.randomNovels.items, outcome.items),
                                    ),
                                )
                            }
                            RandomProviderOutcome.Failed -> mutableState.update { home ->
                                home.copy(
                                    randomNovels = home.randomNovels.copy(
                                        failedSources = home.randomNovels.failedSources + api.name,
                                    ),
                                )
                            }
                            RandomProviderOutcome.Empty -> Unit
                        }
                    }
                }
            }
            mutableState.update { home ->
                val random = home.randomNovels
                home.copy(
                    randomNovels = random.copy(
                        loading = false,
                        failedSources = if (random.items.isEmpty()) random.failedSources else emptyList(),
                    ),
                )
            }
        }
    }

    private suspend fun randomProviderNovels(api: MainAPI): RandomProviderOutcome =
        withContext(Dispatchers.IO) {
            val selection = latestPageSelection(api)
            var sawFailure = false
            for (page in randomPageOrder()) {
                val items = try {
                    api.loadMainPage(
                        page = page,
                        mainCategory = api.mainCategories.getOrNull(selection.categoryIndex)?.second,
                        orderBy = api.orderBys.getOrNull(selection.orderByIndex)?.second,
                        tag = null,
                    ).list
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                if (items == null) {
                    sawFailure = true
                    continue
                }
                if (items.isNotEmpty()) return@withContext RandomProviderOutcome.Items(items)
            }
            if (sawFailure) RandomProviderOutcome.Failed else RandomProviderOutcome.Empty
        }
}
