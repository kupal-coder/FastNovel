package com.lagradost.quicknovel.ui.home

import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.util.ResultCached

data class ContinueReadingItem(
    val novel: ResultCached,
    val chapterName: String?,
    val chapterNumber: Int?,
)

/** Progressive results of one "Random novels" roll across the picked providers. */
data class RandomNovelsState(
    val loading: Boolean = true,
    val items: List<SearchResponse> = emptyList(),
    /** Providers that errored or timed out; only surfaced when no provider answered with novels. */
    val failedSources: List<String> = emptyList(),
)

data class HomeUiState(
    val continueReading: ContinueReadingItem? = null,
    val randomNovels: RandomNovelsState = RandomNovelsState(),
)
