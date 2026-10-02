package com.lagradost.quicknovel.ui.mainpage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.common.SearchResponseItem

@Composable
fun TagSearchScreen(viewModel: TagSearchViewModel) {
    val state by viewModel.state.collectAsState()
    val searchAction = remember<(SearchResponseAction) -> Unit>(viewModel) {
        { action -> viewModel.onResultAction(action) }
    }

    Scaffold(
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
            ) {
                IconButton(
                    onClick = {
                        CommonActivity.activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = stringResource(R.string.back_to_search),
                        tint = colors.onBackground,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = state.tagLabel,
                    color = colors.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = innerPadding.calculateTopPadding(),
                )
        ) {
            when {
                !state.isConfigured || state.isAllInitialLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.onBackground)
                    }
                }

                state.isUnsupportedTag -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.tag_search_unsupported),
                            color = colors.onBackground,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                state.isAllProvidersFailed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.tag_search_all_failed),
                            color = colors.onBackground,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { viewModel.retryAllFailed() },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(text = stringResource(R.string.reload_error))
                        }
                    }
                }

                state.isAllProvidersEmpty -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.mainpage_tag_no_results),
                            color = colors.onBackground,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(
                            items = state.sections,
                            key = { it.apiName }
                        ) { section ->
                            ProviderTagSectionRow(
                                section = section,
                                onLoadMore = { viewModel.loadMore(section.apiName) },
                                onRetry = { viewModel.retryProvider(section.apiName) },
                                searchAction = searchAction,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderTagSectionRow(
    section: ProviderTagSection,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    searchAction: (SearchResponseAction) -> Unit,
) {
    val rowListState = rememberLazyListState()

    val shouldTriggerLoadMore by remember(
        section.items.size,
        section.canLoadMore,
        section.isLoadingMore,
        section.hasError,
    ) {
        derivedStateOf {
            if (!section.canLoadMore || section.isLoadingMore || section.hasError || section.items.isEmpty()) {
                false
            } else {
                val lastVisible = rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= (section.items.size - 4).coerceAtLeast(0)
            }
        }
    }

    LaunchedEffect(shouldTriggerLoadMore) {
        if (shouldTriggerLoadMore) {
            onLoadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = section.apiName,
            color = colors.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )

        if (section.hasError) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(onClick = onRetry),
            ) {
                Text(
                    text = stringResource(R.string.tag_search_provider_error),
                    color = colors.onBackground,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }

        when {
            section.isInitialLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.onBackground,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            section.items.isEmpty() && !section.hasError -> {
                Text(
                    text = stringResource(R.string.mainpage_tag_no_results),
                    color = colors.onBackground,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }

            section.items.isNotEmpty() -> {
                LazyRow(
                    state = rowListState,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    items(
                        items = section.items,
                        key = { item -> item.randomUuid }
                    ) { response ->
                        SearchResponseItem(
                            response = response,
                            action = searchAction,
                            modifier = Modifier.width(120.dp)
                        )
                    }

                    if (section.isLoadingMore) {
                        item(key = "loading_more_${section.apiName}") {
                            Box(
                                modifier = Modifier
                                    .height(180.dp)
                                    .width(72.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = colors.onBackground,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
