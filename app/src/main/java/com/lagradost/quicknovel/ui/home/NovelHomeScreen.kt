package com.lagradost.quicknovel.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.discover.DiscoverPost
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.ui.home.HomeCarousel.Community
import com.lagradost.quicknovel.ui.home.HomeCarousel.New
import com.lagradost.quicknovel.ui.home.HomeCarousel.Popular

@Composable
fun NovelHomeScreen(
    state: HomeUiState,
    onOpenDrawer: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onContinueReading: (ContinueReadingItem) -> Unit = {},
    onOpenNovel: (String, String) -> Unit = { _, _ -> },
    onSeeMore: (HomePageTarget) -> Unit = {},
    onSearchTag: (String) -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onRetry: (HomeCarousel) -> Unit = {},
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NovelHomeTokens.accent,
            onPrimary = NovelHomeTokens.accentText,
            background = NovelHomeTokens.background,
            onBackground = NovelHomeTokens.text,
            surface = NovelHomeTokens.surface,
            onSurface = NovelHomeTokens.text,
            surfaceVariant = NovelHomeTokens.elevatedSurface,
            onSurfaceVariant = NovelHomeTokens.mutedText,
            error = NovelHomeTokens.error,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NovelHomeTokens.background)
                .statusBarsPadding(),
        ) {
            HomeTopBar(
                onOpenDrawer = onOpenDrawer,
                onOpenSearch = onOpenSearch,
                onOpenAccount = onOpenAccount,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NovelHomeTokens.pageHorizontal,
                    end = NovelHomeTokens.pageHorizontal,
                    top = 8.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(NovelHomeTokens.sectionGap),
            ) {
                item(key = "tag-search") {
                    TagSearchEntry(onSearchTag)
                }

                state.continueReading?.let { readingItem ->
                    item(key = "continue-reading") {
                        ContinueReadingCard(readingItem, onContinueReading)
                    }
                }

                item(key = "new-novels") {
                    NovelCarousel(
                        title = stringResource(R.string.home_new_novels),
                        state = state.newNovels,
                        pageTarget = state.newPage,
                        emptyMessage = stringResource(R.string.home_new_empty),
                        errorMessage = stringResource(R.string.home_new_error),
                        onOpenNovel = onOpenNovel,
                        onSeeMore = onSeeMore,
                        onRetry = { onRetry(New) },
                    )
                }

                if (state.popularNovels !is HomeCarouselState.Hidden) {
                    item(key = "popular-novels") {
                        NovelCarousel(
                            title = stringResource(R.string.home_popular),
                            state = state.popularNovels,
                            pageTarget = state.popularPage,
                            emptyMessage = stringResource(R.string.home_popular_empty),
                            errorMessage = stringResource(R.string.home_popular_error),
                            onOpenNovel = onOpenNovel,
                            onSeeMore = onSeeMore,
                            onRetry = { onRetry(Popular) },
                        )
                    }
                }

                if (state.communityPosts !is HomeCarouselState.Hidden) {
                    item(key = "community-posts") {
                        CommunityCarousel(
                            state = state.communityPosts,
                            onOpenNovel = onOpenNovel,
                            onSearchTag = onSearchTag,
                            onSeeMore = onOpenDiscover,
                            onRetry = { onRetry(Community) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagSearchEntry(onSearchTag: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val tag = query.trim()
    val submit: () -> Unit = { if (tag.isNotEmpty()) onSearchTag(tag) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.home_tag_search_title),
            color = NovelHomeTokens.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.home_tag_search_hint),
                    color = NovelHomeTokens.mutedText,
                )
            },
            singleLine = true,
            shape = NovelHomeTokens.smallCardShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit, enabled = tag.isNotEmpty()) {
                    Icon(
                        painter = painterResource(R.drawable.search_icon),
                        contentDescription = stringResource(R.string.home_tag_search_submit),
                        tint = NovelHomeTokens.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NovelHomeTokens.text,
                unfocusedTextColor = NovelHomeTokens.text,
                cursorColor = NovelHomeTokens.accent,
                focusedBorderColor = NovelHomeTokens.accent,
                unfocusedBorderColor = NovelHomeTokens.chipSurface,
                focusedContainerColor = NovelHomeTokens.surface,
                unfocusedContainerColor = NovelHomeTokens.surface,
            ),
        )
    }
}

@Composable
private fun HomeTopBar(
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_menu_24),
                contentDescription = stringResource(R.string.home_open_drawer),
                tint = NovelHomeTokens.text,
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            color = NovelHomeTokens.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSearch) {
            Icon(
                painter = painterResource(R.drawable.search_icon),
                contentDescription = stringResource(R.string.home_open_search),
                tint = NovelHomeTokens.text,
                modifier = Modifier.size(21.dp),
            )
        }
        IconButton(onClick = onOpenAccount) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_account_circle_24),
                contentDescription = stringResource(R.string.home_open_account),
                tint = NovelHomeTokens.text,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ContinueReadingCard(
    item: ContinueReadingItem,
    onContinueReading: (ContinueReadingItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.home_continue_reading),
            color = NovelHomeTokens.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(NovelHomeTokens.cardShape)
                .background(NovelHomeTokens.surface)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NovelCover(
                url = item.novel.poster,
                headers = item.novel.posterHeaders,
                description = item.novel.name,
                modifier = Modifier
                    .width(58.dp)
                    .height(80.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.novel.name,
                    color = NovelHomeTokens.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val chapterLabel = when {
                    !item.chapterName.isNullOrBlank() -> stringResource(
                        R.string.home_last_chapter_format,
                        item.chapterName,
                    )
                    item.chapterNumber != null -> stringResource(
                        R.string.home_chapter_number_format,
                        item.chapterNumber,
                    )
                    else -> stringResource(R.string.home_ready_to_continue)
                }
                Text(
                    text = chapterLabel,
                    color = NovelHomeTokens.mutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.home_from_provider_format, item.novel.apiName),
                    color = NovelHomeTokens.mutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PillButton(
                text = stringResource(R.string.home_continue_button),
                onClick = { onContinueReading(item) },
            )
        }
    }
}

@Composable
private fun NovelCarousel(
    title: String,
    state: HomeCarouselState<SearchResponse>,
    pageTarget: HomePageTarget?,
    emptyMessage: String,
    errorMessage: String,
    onOpenNovel: (String, String) -> Unit,
    onSeeMore: (HomePageTarget) -> Unit,
    onRetry: () -> Unit,
) {
    if (state is HomeCarouselState.Hidden) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = title,
            onSeeMore = pageTarget?.let { target -> { onSeeMore(target) } },
        )
        when (state) {
            HomeCarouselState.Loading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeTokens.cardGap)) {
                    items(3) { index -> NovelSkeletonCard(key = index) }
                }
            }
            is HomeCarouselState.Loaded -> {
                if (state.items.isEmpty()) {
                    HomeMessage(emptyMessage)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeTokens.cardGap)) {
                        items(state.items, key = { "${it.apiName}:${it.url}" }) { novel ->
                            NovelCard(novel) { onOpenNovel(novel.url, novel.apiName) }
                        }
                    }
                }
            }
            HomeCarouselState.Empty -> HomeMessage(emptyMessage)
            HomeCarouselState.Error -> HomeError(errorMessage, onRetry)
            HomeCarouselState.Hidden -> Unit
        }
    }
}

@Composable
private fun CommunityCarousel(
    state: HomeCarouselState<DiscoverPost>,
    onOpenNovel: (String, String) -> Unit,
    onSearchTag: (String) -> Unit,
    onSeeMore: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state is HomeCarouselState.Hidden) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = stringResource(R.string.home_from_community),
            onSeeMore = onSeeMore,
        )
        when (state) {
            HomeCarouselState.Loading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeTokens.cardGap)) {
                    items(2) { index -> CommunitySkeletonCard(key = index) }
                }
            }
            is HomeCarouselState.Loaded -> {
                if (state.items.isEmpty()) {
                    HomeMessage(stringResource(R.string.home_community_empty))
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeTokens.cardGap)) {
                        items(state.items, key = { it.id }) { post ->
                            CommunityPostCard(
                                post = post,
                                onClick = { onOpenNovel(post.novelUrl, post.provider) },
                                onSearchTag = onSearchTag,
                            )
                        }
                    }
                }
            }
            HomeCarouselState.Empty -> HomeMessage(stringResource(R.string.home_community_empty))
            HomeCarouselState.Error -> HomeError(
                stringResource(R.string.home_community_error),
                onRetry,
            )
            HomeCarouselState.Hidden -> Unit
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onSeeMore: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = NovelHomeTokens.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (onSeeMore != null) {
            PillButton(
                text = stringResource(R.string.home_see_more),
                onClick = onSeeMore,
                subtle = true,
            )
        }
    }
}

@Composable
private fun NovelCard(
    novel: SearchResponse,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(146.dp)
            .clip(NovelHomeTokens.cardShape)
            .background(NovelHomeTokens.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        NovelCover(
            url = novel.posterUrl,
            headers = novel.posterHeaders,
            description = novel.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp),
        )
        Text(
            text = novel.name,
            color = NovelHomeTokens.text,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 34.dp),
        )
        novel.latestChapter?.takeIf { it.isNotBlank() }?.let { chapter ->
            Text(
                text = stringResource(R.string.latest_format, chapter),
                color = NovelHomeTokens.mutedText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.home_from_provider_format, novel.apiName),
            color = NovelHomeTokens.mutedText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CommunityPostCard(
    post: DiscoverPost,
    onClick: () -> Unit,
    onSearchTag: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(244.dp)
            .clip(NovelHomeTokens.cardShape)
            .background(NovelHomeTokens.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelCover(
                url = post.coverUrl,
                description = post.novelTitle,
                modifier = Modifier
                    .width(58.dp)
                    .height(78.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = post.novelTitle,
                    color = NovelHomeTokens.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.home_community_author_format, post.authorName),
                    color = NovelHomeTokens.mutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = post.body,
            color = NovelHomeTokens.mutedText,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        addedAge(post.createdAt, System.currentTimeMillis())?.let { AddedAgeLabel(it) }
        post.tags.firstOrNull()?.let { tag ->
            TagChip(label = tag, onClick = { onSearchTag(tag) })
        }
    }
}

@Composable
private fun NovelSkeletonCard(key: Int) {
    Column(
        modifier = Modifier
            .width(146.dp)
            .clip(NovelHomeTokens.cardShape)
            .background(NovelHomeTokens.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .clip(NovelHomeTokens.coverShape)
                .background(if (key % 2 == 0) NovelHomeTokens.elevatedSurface else NovelHomeTokens.chipSurface),
        )
        SkeletonLine(width = 112.dp)
        SkeletonLine(width = 86.dp)
    }
}

@Composable
private fun CommunitySkeletonCard(key: Int) {
    Column(
        modifier = Modifier
            .width(244.dp)
            .clip(NovelHomeTokens.cardShape)
            .background(NovelHomeTokens.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(78.dp)
                    .clip(NovelHomeTokens.coverShape)
                    .background(if (key == 0) NovelHomeTokens.elevatedSurface else NovelHomeTokens.chipSurface),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonLine(width = 130.dp)
                SkeletonLine(width = 92.dp)
            }
        }
        SkeletonLine(width = 190.dp)
        SkeletonLine(width = 160.dp)
    }
}

@Composable
private fun SkeletonLine(width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(NovelHomeTokens.elevatedSurface),
    )
}

@Composable
private fun AddedAgeLabel(age: AddedAge) {
    val label = when (age.unit) {
        AddedAgeUnit.JustNow -> stringResource(R.string.home_posted_just_now)
        AddedAgeUnit.Minutes -> pluralStringResource(
            R.plurals.home_posted_minutes_ago,
            age.amount,
            age.amount,
        )
        AddedAgeUnit.Hours -> pluralStringResource(
            R.plurals.home_posted_hours_ago,
            age.amount,
            age.amount,
        )
        AddedAgeUnit.Days -> pluralStringResource(
            R.plurals.home_posted_days_ago,
            age.amount,
            age.amount,
        )
    }
    Text(
        text = label,
        color = NovelHomeTokens.mutedText,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TagChip(
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = label,
        color = NovelHomeTokens.accent,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(NovelHomeTokens.chipShape)
            .background(NovelHomeTokens.chipSurface)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick).semantics { role = Role.Button }
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = NovelHomeTokens.chipHorizontalPadding,
                vertical = NovelHomeTokens.chipVerticalPadding,
            ),
    )
}

@Composable
private fun HomeMessage(message: String) {
    Surface(
        color = NovelHomeTokens.surface,
        shape = NovelHomeTokens.smallCardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = NovelHomeTokens.mutedText,
            fontSize = 13.sp,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun HomeError(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NovelHomeTokens.smallCardShape)
            .background(NovelHomeTokens.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            color = NovelHomeTokens.mutedText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        PillButton(
            text = stringResource(R.string.home_retry),
            onClick = onRetry,
            subtle = true,
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    subtle: Boolean = false,
) {
    Text(
        text = text,
        color = if (subtle) NovelHomeTokens.accent else NovelHomeTokens.accentText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(NovelHomeTokens.pillShape)
            .background(if (subtle) NovelHomeTokens.chipSurface else NovelHomeTokens.accent)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}

@Composable
private fun NovelCover(
    url: String?,
    description: String,
    modifier: Modifier,
    headers: Map<String, String>? = null,
) {
    val context = LocalContext.current
    val request = remember(context, url, headers) {
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                headers?.forEach { (key, value) -> headerBuilder[key] = value }
            }.build())
            .crossfade(true)
            .build()
    }
    Box(
        modifier = modifier
            .clip(NovelHomeTokens.coverShape)
            .background(NovelHomeTokens.elevatedSurface),
    ) {
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "Home · loading", showBackground = true, backgroundColor = 0xFF071321)
@Composable
private fun NovelHomeLoadingPreview() {
    NovelHomeScreen(
        state = HomeUiState(
            newNovels = HomeCarouselState.Loading,
            popularNovels = HomeCarouselState.Loading,
            communityPosts = HomeCarouselState.Loading,
        ),
    )
}

@Preview(name = "Home · loaded", showBackground = true, backgroundColor = 0xFF071321)
@Composable
private fun NovelHomeLoadedPreview() {
    val sample = SearchResponse(
        name = "The Lanterns Beyond the Northern Sea",
        url = "https://example.invalid/novel",
        latestChapter = "Chapter 42",
        apiName = "Preview provider",
        rating = 5,
    )
    NovelHomeScreen(
        state = HomeUiState(
            continueReading = ContinueReadingItem(
                novel = ResultCached(
                    source = "https://example.invalid/novel",
                    name = "A Quiet Reincarnation",
                    apiName = "Preview provider",
                    id = 1,
                    author = null,
                    poster = null,
                    tags = listOf("Fantasy"),
                    rating = 5,
                    totalChapters = 42,
                    cachedTime = 0L,
                ),
                chapterName = "Chapter 18",
                chapterNumber = 18,
            ),
            newNovels = HomeCarouselState.Loaded(listOf(sample, sample.copy(name = "A Quiet Reincarnation"))),
            popularNovels = HomeCarouselState.Loaded(listOf(sample.copy(name = "A Study in Starlight"))),
            newPage = HomePageTarget("Preview provider", -1, -1),
            popularPage = HomePageTarget("Preview provider", -1, 1),
            communityPosts = HomeCarouselState.Loaded(
                listOf(
                    DiscoverPost(
                        id = "preview-post",
                        userId = "preview-user",
                        authorName = "Reader",
                        provider = "Preview provider",
                        novelUrl = "https://example.invalid/novel",
                        novelTitle = "A community favorite",
                        coverUrl = null,
                        body = "A warm, thoughtful recommendation for readers who like a slow-burn mystery.",
                        rating = 5,
                        tags = listOf("Mystery"),
                        createdAt = 0L,
                    ),
                ),
            ),
        ),
    )
}

@Preview(name = "Home · empty", showBackground = true, backgroundColor = 0xFF071321)
@Composable
private fun NovelHomeEmptyPreview() {
    NovelHomeScreen(
        state = HomeUiState(
            newNovels = HomeCarouselState.Empty,
            popularNovels = HomeCarouselState.Hidden,
            communityPosts = HomeCarouselState.Empty,
        ),
    )
}

@Preview(name = "Home · error", showBackground = true, backgroundColor = 0xFF071321)
@Composable
private fun NovelHomeErrorPreview() {
    NovelHomeScreen(
        state = HomeUiState(
            newNovels = HomeCarouselState.Error,
            popularNovels = HomeCarouselState.Error,
            communityPosts = HomeCarouselState.Error,
        ),
    )
}
