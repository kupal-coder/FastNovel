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
    New,
    Popular,
    Community,
}

data class HomePageTarget(
    val apiName: String,
    val categoryIndex: Int,
    val orderByIndex: Int,
)

data class ContinueReadingItem(
    val novel: ResultCached,
    val chapterName: String?,
    val chapterNumber: Int?,
)

data class HomeUiState(
    val continueReading: ContinueReadingItem? = null,
    val newNovels: HomeCarouselState<SearchResponse> = HomeCarouselState.Loading,
    val popularNovels: HomeCarouselState<SearchResponse> = HomeCarouselState.Hidden,
    val communityPosts: HomeCarouselState<DiscoverPost> = HomeCarouselState.Hidden,
    val newPage: HomePageTarget? = null,
    val popularPage: HomePageTarget? = null,
)
