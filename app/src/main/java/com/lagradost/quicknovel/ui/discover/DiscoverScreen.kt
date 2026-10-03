package com.lagradost.quicknovel.ui.discover

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.compose.RoundedShape
import com.lagradost.quicknovel.discover.DiscoverError
import com.lagradost.quicknovel.discover.DiscoverPost
import com.lagradost.quicknovel.discover.DiscoverSort
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: DiscoverState,
    action: (DiscoverAction) -> Unit,
    openNovel: (DiscoverPost) -> Unit,
    signIn: () -> Unit,
    settings: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); now = System.currentTimeMillis() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(context.getString(it)); action(DiscoverAction.ClearNotice) }
    }

    state.writer?.let { WritePostSheet(it, action) }
    state.confirmation?.let { post ->
        val own = post.userId == state.userId
        AlertDialog(
            containerColor = colors.background,
            onDismissRequest = { action(DiscoverAction.DismissConfirmation) },
            title = { Text(stringResource(if (own) R.string.delete else R.string.discover_report)) },
            text = {
                Column {
                    Text(stringResource(if (own) R.string.discover_delete_confirm else R.string.discover_report_confirm, post.novelTitle))
                    state.actionError?.let { Text(stringResource(it.text), color = MaterialTheme.colorScheme.error) }
                    if (state.actionBusy) CircularProgressIndicator(Modifier.padding(top = 12.dp).size(24.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = !state.actionBusy, onClick = { action(DiscoverAction.Confirm) }) {
                    Text(stringResource(if (state.actionError != null) R.string.discover_retry else if (own) R.string.delete else R.string.discover_report))
                }
            },
            dismissButton = {
                TextButton(enabled = !state.actionBusy, onClick = { action(DiscoverAction.DismissConfirmation) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_discover)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.gate == DiscoverGate.Ready) {
                FloatingActionButton(onClick = { action(DiscoverAction.Write) },
                    containerColor = colors.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Icon(painterResource(R.drawable.ic_baseline_edit_24), stringResource(R.string.discover_write))
                }
            }
        },
    ) { padding ->
        // The XML NavHost already ends above the bottom bar, like the other Compose tabs.
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (state.gate) {
                DiscoverGate.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                DiscoverGate.SignedOut -> DiscoverMessage(
                    stringResource(state.error?.text ?: R.string.discover_sign_in_required),
                    stringResource(R.string.sign_in), signIn)
                DiscoverGate.Username -> DiscoverMessage(
                    stringResource(R.string.discover_username_required), stringResource(R.string.title_settings), settings)
                DiscoverGate.Error -> DiscoverErrorView(state.error ?: DiscoverError.Generic,
                    { action(DiscoverAction.Retry) }, signIn, settings)
                DiscoverGate.Ready -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.sort == DiscoverSort.Top,
                            onClick = { action(DiscoverAction.Sort(DiscoverSort.Top)) },
                            label = { Text(stringResource(R.string.discover_sort_top)) })
                        FilterChip(selected = state.sort == DiscoverSort.New,
                            onClick = { action(DiscoverAction.Sort(DiscoverSort.New)) },
                            label = { Text(stringResource(R.string.discover_sort_new)) })
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = state.selectedTag == null, onClick = { action(DiscoverAction.Filter(null)) },
                                label = { Text(stringResource(R.string.discover_all)) })
                        }
                        items(state.popularTags, key = { it }) { tag ->
                            FilterChip(selected = state.selectedTag == tag, onClick = { action(DiscoverAction.Filter(tag)) },
                                label = { Text(tagLabel(tag)) })
                        }
                    }
                    state.selectedTag?.let {
                        Text(stringResource(R.string.discover_filtered_by, tagLabel(it)), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = colors.onSurfaceVariant)
                    }
                    val list = rememberLazyListState()
                    LaunchedEffect(state.selectedTag, state.sort) { list.scrollToItem(0) }
                    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { action(DiscoverAction.Refresh) },
                        modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            (state.error ?: state.tagsError)?.let { error ->
                                item { DiscoverErrorView(error, { action(DiscoverAction.Retry) }, signIn, settings) }
                            }
                            if (state.posts.isEmpty() && !state.refreshing && state.error == null) {
                                item { Text(stringResource(R.string.discover_empty), textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp)) }
                            }
                            items(state.posts, key = { it.id }) { post ->
                                DiscoverPostCard(post, post.userId == state.userId, state.selectedTag, now, action, { openNovel(post) })
                            }
                            item {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    when {
                                        state.loadingMore -> CircularProgressIndicator(Modifier.size(28.dp))
                                        state.moreError != null -> DiscoverErrorView(state.moreError,
                                            { action(DiscoverAction.LoadMore) }, signIn, settings)
                                        state.hasMore -> Button(onClick = { action(DiscoverAction.LoadMore) }) {
                                            Text(stringResource(R.string.discover_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverPostCard(
    post: DiscoverPost,
    own: Boolean,
    selectedTag: String?,
    now: Long,
    action: (DiscoverAction) -> Unit,
    open: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = open, shape = RoundedShape(), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NovelCover(post.novelTitle, post.coverUrl)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(post.novelTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(post.provider, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    post.rating?.let { RatingStars(it) }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_baseline_more_vert_24), stringResource(R.string.discover_post_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (own) R.string.delete else R.string.discover_report)) },
                            onClick = { menu = false; action(DiscoverAction.Ask(post)) })
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                post.tags.forEach { tag ->
                    FilterChip(selected = tag == selectedTag, onClick = { action(DiscoverAction.Filter(tag)) },
                        label = { Text(tagLabel(tag)) })
                }
            }
            // Deliberately plain text: unlike ReviewItem, no HTML or clickable link annotations.
            Text(post.body, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VoteControl(score = post.score, myVote = post.myVote,
                    onVote = { value -> action(DiscoverAction.Vote(post, value)) })
                Text(post.authorName, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(relativeTime(LocalContext.current, post.createdAt, now), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun NovelCover(title: String, cover: String?) {
    AsyncImage(model = cover, contentDescription = title, contentScale = ContentScale.Crop,
        error = painterResource(R.drawable.ic_baseline_menu_book_24),
        fallback = painterResource(R.drawable.ic_baseline_menu_book_24),
        modifier = Modifier.width(60.dp).height(90.dp).clip(RoundedShape()).background(colors.surfaceVariant))
}

@Composable
private fun RatingStars(rating: Int) {
    val description = stringResource(R.string.discover_rating_value, rating)
    Row(Modifier.padding(top = 4.dp).clearAndSetSemantics { contentDescription = description }) {
        repeat(5) { index ->
            Icon(painterResource(R.drawable.ic_baseline_star_24), null, Modifier.size(18.dp),
                tint = if (index < rating) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun DiscoverMessage(message: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, textAlign = TextAlign.Center)
        Button(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun DiscoverErrorView(error: DiscoverError, retry: () -> Unit, signIn: () -> Unit, settings: () -> Unit) {
    val (label, click) = when (error) {
        DiscoverError.SignIn -> R.string.sign_in to signIn
        DiscoverError.Username -> R.string.title_settings to settings
        else -> R.string.discover_retry to retry
    }
    DiscoverMessage(stringResource(error.text), stringResource(label), click)
}

internal fun tagLabel(tag: String): String = tag.split(' ').joinToString(" ") { word ->
    word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun relativeTime(context: Context, createdAt: Long, now: Long): String {
    if (createdAt <= 0) return context.getString(R.string.unknown)
    val minutes = ((now - createdAt).coerceAtLeast(0) / 60_000)
    return when {
        minutes == 0L -> context.getString(R.string.discover_time_now)
        minutes < 60 -> context.getString(R.string.discover_time_minutes, minutes)
        minutes < 1440 -> context.getString(R.string.discover_time_hours, minutes / 60)
        else -> context.getString(R.string.discover_time_days, minutes / 1440)
    }
}
