package com.lagradost.quicknovel.ui.mainpage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.BackHandler
import com.lagradost.quicknovel.compose.BaseSearchBar
import com.lagradost.quicknovel.compose.CloudStreamTheme
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.compose.Colors
import com.lagradost.quicknovel.compose.SingleSelectDialog
import com.lagradost.quicknovel.ui.common.SearchList
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.search.SearchRow
import com.mihon.common.preference.AndroidPreferenceStore
import com.mihon.presentation.settings.collectAsState
import kotlinx.coroutines.launch

@Composable
fun MainScreenDialog(
    dialog: MainPageDialog,
    action: (MainPageAction) -> Unit
) {
    val title = when (dialog.type) {
        DialogType.Tags -> stringResource(R.string.filter_dialog_genre)
        DialogType.Category -> stringResource(R.string.filter_dialog_general)
        DialogType.OrderBy -> stringResource(R.string.filter_dialog_order_by)
    }

    SingleSelectDialog(
        entries = dialog.options,
        dismiss = {
            action(MainPageAction.Dismiss)
        },
        title = title,
        selectedIndex = dialog.selected,
        confirm = { selected ->
            action(MainPageAction.SelectDialog(dialog.type, selected))
        })


    /*BaseDialog(
        dismiss = {
            action(MainPageAction.Dismiss)
        },
        title = { Text(text = title) },
        items = dialog.options,
        selected = dialog.selected,
        onSelect = { selected ->
            action(MainPageAction.SelectDialog(dialog.type, selected))
        })*/
}

@Composable
fun MainPageScreen(state: MainPageState, action: (MainPageAction) -> Unit) {
    if (state.openQuery) {
        BackHandler {
            action(MainPageAction.Back)
        }
    }

    if (state.dialog != null) {
        MainScreenDialog(state.dialog, action)
    }

    val listState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(snapAnimationSpec = null)
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 5
        }
    }

    val searchAction = remember<(SearchResponseAction) -> Unit>(action) {
        { responseAction ->
            action(MainPageAction.ResultAction(responseAction))
        }
    }

    val context = LocalContext.current
    val store = AndroidPreferenceStore(context)
    val searchIsRow = store.getBoolean(stringResource(R.string.search_list_view_key), false)
    val searchIsRowState by searchIsRow.collectAsState()
    val items = if (state.openQuery) state.query.items else state.filter.items
    val loading = if (state.openQuery) state.query.loading else state.filter.loading
    val error = if (state.openQuery) state.query.error else state.filter.error
    val confirmedOffline = error != null && context.hasConfirmedNoInternet()
    val errorText = stringResource(
        if (confirmedOffline) R.string.username_error_no_internet else R.string.error_loading
    )
    val retry = {
        if (state.openQuery) {
            action(MainPageAction.Search(state.query.query))
        } else {
            action(MainPageAction.Expand)
        }
    }
    val emptyText = stringResource(
        if (!state.openQuery && state.filterVisual.tag != null) {
            R.string.mainpage_tag_no_results
        } else {
            R.string.no_data
        }
    )
    val waitingForExcludedNovelPage = !state.openQuery && items.isEmpty() &&
        state.filter.query.page > 0 && state.filter.hasMore

    Scaffold(
        topBar = {
            MainPageSearchBar(
                openQuery = state.openQuery,
                loading = loading,
                url = state.filter.url,
                action = action,
                query = state.filterVisual,
                scrollBehavior = scrollBehavior,
                apiName = state.apiName,
                hasTags = state.hasTags,
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // This fixes the double padding from the bottom nav bar
                .padding(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = innerPadding.calculateTopPadding()
                ),
        ) {
            if (items.isNotEmpty()) {
                if (error != null && !loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = errorText,
                            color = colors.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = retry) {
                            Text(stringResource(R.string.reload_error))
                        }
                    }
                }
                SearchList(
                    lazyGridState = listState,
                    isRow = searchIsRowState,
                    items = items,
                    modifier = Modifier.fillMaxSize().weight(1f),
                    searchAction = searchAction,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading || waitingForExcludedNovelPage -> {
                            CircularProgressIndicator(color = colors.onBackground)
                        }

                        error != null -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Text(
                                    text = errorText,
                                    color = colors.onBackground,
                                    textAlign = TextAlign.Center,
                                )
                                Button(onClick = retry) {
                                    Text(stringResource(R.string.reload_error))
                                }
                            }
                        }

                        else -> {
                            Text(
                                text = emptyText,
                                color = colors.onBackground,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                }
            }
        }

        LaunchedEffect(
            shouldLoadMore.value,
            state.openQuery,
            state.filter.items.size,
            state.filter.query.page,
            state.filter.loading,
            state.filter.error,
            state.filter.hasMore,
        ) {
            if (!state.openQuery && shouldLoadMore.value && state.filter.hasMore &&
                state.filter.error == null && !state.filter.loading &&
                (state.filter.items.isNotEmpty() || state.filter.query.page > 0)
            ) {
                action(MainPageAction.Expand)
            }
        }
    }
}

private fun Context.hasConfirmedNoInternet(): Boolean {
    return try {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return true
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return true
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: SecurityException) {
        false
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResponseDialog(
    dialog: SearchRow,
    action: (SearchResponseAction) -> Unit,
    dismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        sheetState = sheetState,
        containerColor = colors.background,
        onDismissRequest = dismiss,
        modifier = Modifier.fillMaxSize(),
        dragHandle = { },
        shape = RectangleShape
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clickable(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            dismiss()
                        }
                    }
                )
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = dialog.name,
                color = colors.onBackground,
                fontSize = 20.sp,
            )
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                tint = colors.onBackground,
                contentDescription = null,
            )
        }

        if (dialog.error != null) {
            Text(
                color = colors.onBackground,
                fontSize = 14.sp,
                lineHeight = 15.sp,
                text = dialog.error.toString(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (dialog.items.isEmpty()) {
            Text(
                color = colors.onBackground,
                fontSize = 14.sp,
                lineHeight = 15.sp,
                text = stringResource(R.string.no_data),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SearchList(
                isRow = false,
                items = dialog.items,
                modifier = Modifier
                    .fillMaxSize(),
                searchAction = action,
            )
        }
    }
}


@Composable
fun MainPageSearchBar(
    openQuery: Boolean,
    loading: Boolean,
    url: String,
    query: FilterQueryVisual,
    action: (MainPageAction) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    apiName: String,
    hasTags: Boolean,
) {
    val clearTagLabel = stringResource(R.string.mainpage_clear_tag)
    val context = LocalContext.current
    val store = AndroidPreferenceStore(context)
    val searchIsRow = store.getBoolean(stringResource(R.string.search_list_view_key), false)
    val searchIsRowState by searchIsRow.collectAsState()

    BaseSearchBar(
        placeholder = "${stringResource(R.string.search)} $apiName…",
        onQueryChange = { _ ->
            // action(MainPageAction.Search(query))
        },
        onSearch = { query ->
            action(MainPageAction.Search(query))
        },
        leadingIcon = {
            if (openQuery) {
                IconButton(onClick = {
                    action(MainPageAction.Back)
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = stringResource(R.string.back_to_search),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.search_icon),
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        scrollBehavior = scrollBehavior,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(34.dp)
                            .padding(5.dp),
                        color = colors.onBackground, strokeWidth = 3.0.dp
                    )
                } else if (!openQuery) {
                    IconButton(onClick = {
                        action(MainPageAction.OpenInBrowser(url))
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_public_24),
                            contentDescription = stringResource(R.string.open_in_browser),
                            tint = colors.onBackground
                        )
                    }
                }

                IconButton(onClick = {
                    searchIsRow.set(!searchIsRowState)
                }) {
                    Icon(
                        painter = painterResource(if (searchIsRowState) R.drawable.ic_baseline_grid_view_24 else R.drawable.ic_baseline_list_24),
                        contentDescription = stringResource(if (searchIsRowState) R.string.grid_view else R.string.list_view),
                        modifier = Modifier.size(24.dp),
                        tint = colors.onBackground
                    )
                }
            }

        },
    ) {
        if (!openQuery) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp, start = 5.dp, end = 5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                query.category?.let {
                    SelectButton(it) {
                        action(MainPageAction.OpenDialog(DialogType.Category))
                    }
                }
                query.orderBy?.let {
                    SelectButton(it) {
                        action(MainPageAction.OpenDialog(DialogType.OrderBy))
                    }
                }
                if (hasTags) {
                    SelectButton(query.tag ?: stringResource(R.string.filter_dialog_genre)) {
                        action(MainPageAction.OpenDialog(DialogType.Tags, clearTagLabel))
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.SelectButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        Modifier
            .weight(1.0f)
            .padding(horizontal = 5.dp),
        colors = Colors.blackButton,
    ) {
        Text(text)
        Spacer(Modifier.width(10.dp))
        Icon(
            painter = painterResource(R.drawable.arrow_drop_down_24px), contentDescription = text
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsScreenPreview() {
    CloudStreamTheme {
        MainPageScreen(
            state = MainPageState(apiName = "hello world"),
            action = {})
    }
}
