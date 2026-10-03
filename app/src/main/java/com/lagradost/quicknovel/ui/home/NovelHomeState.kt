package com.lagradost.quicknovel.ui.home

import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.discover.DiscoverPost
import com.lagradost.quicknovel.util.ResultCached

sealed interface HomeCarouselState<out T> {
    data object Loading : HomeCarouselState<Nothing>
    data class Loaded<T>(val items: List<T>) : HomeCarouselState<T>
    data object Empty : HomeCarouselState<Nothing>
    data object Error : HomeCarouselState<Nothing>
    data object Hidden : HomeCarouselState<Nothing>
}

enum class HomeCarousel {
    Popular,
    Random,
}

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
    val signedIn: Boolean = false,
    val popularPosts: HomeCarouselState<DiscoverPost> = HomeCarouselState.Hidden,
    val randomNovels: RandomNovelsState = RandomNovelsState(),
    /** One-shot snackbar message (@StringRes), consumed by the screen. */
    val notice: Int? = null,
)
