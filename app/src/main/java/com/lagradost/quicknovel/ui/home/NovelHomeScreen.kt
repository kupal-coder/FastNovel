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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.compose.CloudStreamPrimaryColor
import com.lagradost.quicknovel.compose.CloudStreamTheme
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.compose.CloudStreamThemeMode
import com.lagradost.quicknovel.discover.DiscoverPost
import com.lagradost.quicknovel.ui.discover.VoteControl
import com.lagradost.quicknovel.util.ResultCached

/**
 * Home is hosted inside CloudStreamTheme by [NovelHomeFragment], exactly like the other Compose
 * screens, so every color below comes from the theme mode and accent picked in Settings.
 */
@Composable
fun NovelHomeScreen(
    state: HomeUiState,
    onOpenDrawer: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onContinueReading: (ContinueReadingItem) -> Unit = {},
    onOpenNovel: (String, String) -> Unit = { _, _ -> },
    onOpenPost: (DiscoverPost) -> Unit = {},
    onSeeMorePopular: () -> Unit = {},
    onSearchTag: (String) -> Unit = {},
    onShuffle: () -> Unit = {},
    onVote: (DiscoverPost, Int) -> Unit = { _, _ -> },
    onRetry: (HomeCarousel) -> Unit = {},
    onConsumeNotice: () -> Unit = {},
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(context.getString(it))
            onConsumeNotice()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    start = NovelHomeStyle.pageHorizontal,
                    end = NovelHomeStyle.pageHorizontal,
                    top = 8.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(NovelHomeStyle.sectionGap),
            ) {
                item(key = "tag-search") {
                    TagSearchEntry(onSearchTag)
                }

                state.continueReading?.let { readingItem ->
                    item(key = "continue-reading") {
                        ContinueReadingCard(readingItem, onContinueReading)
                    }
                }

                item(key = "popular") {
                    PopularSection(
                        signedIn = state.signedIn,
                        posts = state.popularPosts,
                        onOpenPost = onOpenPost,
                        onSearchTag = onSearchTag,
                        onVote = onVote,
                        onSeeMore = onSeeMorePopular,
                        onSignIn = onSignIn,
                        onRetry = { onRetry(HomeCarousel.Popular) },
                    )
                }

                item(key = "random-novels") {
                    RandomSection(
                        random = state.randomNovels,
                        onOpenNovel = onOpenNovel,
                        onShuffle = onShuffle,
                        onRetry = { onRetry(HomeCarousel.Random) },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
            color = colors.onBackground,
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
                    color = colors.onSurfaceVariant,
                )
            },
            singleLine = true,
            shape = NovelHomeStyle.smallCardShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit, enabled = tag.isNotEmpty()) {
                    Icon(
                        painter = painterResource(R.drawable.search_icon),
                        contentDescription = stringResource(R.string.home_tag_search_submit),
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.onBackground,
                unfocusedTextColor = colors.onBackground,
                cursorColor = colors.primary,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.primary.copy(alpha = 0.16f),
                focusedContainerColor = colors.surfaceVariant,
                unfocusedContainerColor = colors.surfaceVariant,
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
                tint = colors.icon,
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            color = colors.onBackground,
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
                tint = colors.icon,
                modifier = Modifier.size(21.dp),
            )
        }
        IconButton(onClick = onOpenAccount) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_account_circle_24),
                contentDescription = stringResource(R.string.home_open_account),
                tint = colors.icon,
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
            color = colors.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(NovelHomeStyle.cardShape)
                .background(colors.surfaceVariant)
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
                    color = colors.onBackground,
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
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.home_from_provider_format, item.novel.apiName),
                    color = colors.onSurfaceVariant,
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
private fun PopularSection(
    signedIn: Boolean,
    posts: HomeCarouselState<DiscoverPost>,
    onOpenPost: (DiscoverPost) -> Unit,
    onSearchTag: (String) -> Unit,
    onVote: (DiscoverPost, Int) -> Unit,
    onSeeMore: () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = stringResource(R.string.home_popular),
            onSeeMore = if (signedIn) onSeeMore else null,
        )
        if (!signedIn) {
            PopularSignInCard(onSignIn)
            return@Column
        }
        when (posts) {
            HomeCarouselState.Loading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeStyle.cardGap)) {
                    items(2) { PopularSkeletonCard() }
                }
            }
            is HomeCarouselState.Loaded -> {
                if (posts.items.isEmpty()) {
                    HomeMessage(stringResource(R.string.home_popular_empty))
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeStyle.cardGap)) {
                        items(posts.items, key = { it.id }) { post ->
                            PopularPostCard(
                                post = post,
                                onClick = { onOpenPost(post) },
                                onSearchTag = onSearchTag,
                                onVote = { value -> onVote(post, value) },
                            )
                        }
                    }
                }
            }
            HomeCarouselState.Empty -> HomeMessage(stringResource(R.string.home_popular_empty))
            HomeCarouselState.Error -> HomeError(
                stringResource(R.string.home_popular_error),
                onRetry,
            )
            HomeCarouselState.Hidden -> Unit
        }
    }
}

@Composable
private fun PopularSignInCard(onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NovelHomeStyle.smallCardShape)
            .background(colors.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.home_popular_sign_in),
            color = colors.onBackground,
            fontSize = 13.sp,
        )
        PillButton(
            text = stringResource(R.string.sign_in),
            onClick = onSignIn,
        )
    }
}

@Composable
private fun RandomSection(
    random: RandomNovelsState,
    onOpenNovel: (String, String) -> Unit,
    onShuffle: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_random),
                color = colors.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                text = stringResource(R.string.home_random_shuffle),
                onClick = onShuffle,
                subtle = true,
            )
        }
        when {
            random.items.isNotEmpty() -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeStyle.cardGap)) {
                    items(random.items, key = { "${it.apiName}:${it.url}" }) { novel ->
                        NovelCard(novel) { onOpenNovel(novel.url, novel.apiName) }
                    }
                }
            }
            random.loading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NovelHomeStyle.cardGap)) {
                    items(3) { NovelSkeletonCard() }
                }
            }
            random.failedSources.isNotEmpty() -> HomeError(
                stringResource(
                    R.string.home_random_error_format,
                    random.failedSources.joinToString(", "),
                ),
                onRetry,
            )
            else -> HomeMessage(stringResource(R.string.home_random_empty))
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
            color = colors.onBackground,
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
            .clip(NovelHomeStyle.cardShape)
            .background(colors.surfaceVariant)
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
            color = colors.onBackground,
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
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.home_from_provider_format, novel.apiName),
            color = colors.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PopularPostCard(
    post: DiscoverPost,
    onClick: () -> Unit,
    onSearchTag: (String) -> Unit,
    onVote: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(244.dp)
            .clip(NovelHomeStyle.cardShape)
            .background(colors.surfaceVariant)
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
            Text(
                text = post.novelTitle,
                color = colors.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        post.tags.firstOrNull()?.let { tag ->
            TagChip(label = tag, onClick = { onSearchTag(tag) })
        }
        Text(
            text = post.body,
            color = colors.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.home_community_author_format, post.authorName),
            color = colors.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                addedAge(post.createdAt, System.currentTimeMillis())?.let { AddedAgeLabel(it) }
            }
            VoteControl(
                score = post.score,
                myVote = post.myVote,
                onVote = onVote,
            )
        }
    }
}

@Composable
private fun NovelSkeletonCard() {
    Column(
        modifier = Modifier
            .width(146.dp)
            .clip(NovelHomeStyle.cardShape)
            .background(colors.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .clip(NovelHomeStyle.coverShape),
        )
        SkeletonLine(width = 112.dp)
        SkeletonLine(width = 86.dp)
    }
}

@Composable
private fun PopularSkeletonCard() {
    Column(
        modifier = Modifier
            .width(244.dp)
            .clip(NovelHomeStyle.cardShape)
            .background(colors.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(
                modifier = Modifier
                    .width(58.dp)
                    .height(78.dp)
                    .clip(NovelHomeStyle.coverShape),
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
private fun SkeletonBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(colors.onBackground.copy(alpha = 0.08f)),
    )
}

@Composable
private fun SkeletonLine(width: Dp) {
    SkeletonBlock(
        modifier = Modifier
            .width(width)
            .height(10.dp)
            .clip(RoundedCornerShape(50)),
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
        color = colors.onSurfaceVariant,
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
        color = colors.onBackground,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(NovelHomeStyle.chipShape)
            .background(colors.primary.copy(alpha = 0.16f))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick).semantics { role = Role.Button }
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = NovelHomeStyle.chipHorizontalPadding,
                vertical = NovelHomeStyle.chipVerticalPadding,
            ),
    )
}

@Composable
private fun HomeMessage(message: String) {
    Surface(
        color = colors.surfaceVariant,
        shape = NovelHomeStyle.smallCardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = colors.onSurfaceVariant,
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
            .clip(NovelHomeStyle.smallCardShape)
            .background(colors.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            color = colors.ongoing,
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
        color = if (subtle) colors.onBackground else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(NovelHomeStyle.pillShape)
            .background(if (subtle) colors.primary.copy(alpha = 0.16f) else colors.primary)
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
            .clip(NovelHomeStyle.coverShape)
            .background(colors.onBackground.copy(alpha = 0.08f)),
    ) {
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun previewPost(
    id: String = "preview-post",
    score: Int = 1234,
    myVote: Int = 1,
) = DiscoverPost(
    id = id,
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
    score = score,
    myVote = myVote,
)

private fun previewNovel(name: String) = SearchResponse(
    name = name,
    url = "https://example.invalid/novel",
    latestChapter = "Chapter 42",
    apiName = "Preview provider",
    rating = 5,
)

private fun previewState(
    signedIn: Boolean = true,
    popularPosts: HomeCarouselState<DiscoverPost> = HomeCarouselState.Loaded(
        listOf(previewPost(), previewPost(id = "preview-post-2", score = 12, myVote = 0)),
    ),
    randomNovels: RandomNovelsState = RandomNovelsState(
        loading = false,
        items = listOf(
            previewNovel("The Lanterns Beyond the Northern Sea"),
            previewNovel("A Study in Starlight"),
        ),
    ),
) = HomeUiState(
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
    signedIn = signedIn,
    popularPosts = popularPosts,
    randomNovels = randomNovels,
)

@Composable
private fun NovelHomePreview(mode: CloudStreamThemeMode, state: HomeUiState) {
    CloudStreamTheme(mode = mode, primaryColor = CloudStreamPrimaryColor.NORMAL) {
        NovelHomeScreen(state = state)
    }
}

@Preview(name = "Home · loaded · Light", showBackground = true)
@Composable
private fun NovelHomeLoadedLightPreview() =
    NovelHomePreview(CloudStreamThemeMode.Light, previewState())

@Preview(name = "Home · loaded · Amoled", showBackground = true)
@Composable
private fun NovelHomeLoadedAmoledPreview() =
    NovelHomePreview(CloudStreamThemeMode.AmoledLight, previewState())

@Preview(name = "Home · signed out · Light", showBackground = true)
@Composable
private fun NovelHomeSignedOutLightPreview() =
    NovelHomePreview(
        CloudStreamThemeMode.Light,
        previewState(signedIn = false, popularPosts = HomeCarouselState.Hidden),
    )

@Preview(name = "Home · signed out · Amoled", showBackground = true)
@Composable
private fun NovelHomeSignedOutAmoledPreview() =
    NovelHomePreview(
        CloudStreamThemeMode.AmoledLight,
        previewState(signedIn = false, popularPosts = HomeCarouselState.Hidden),
    )

@Preview(name = "Home · loading · Light", showBackground = true)
@Composable
private fun NovelHomeLoadingLightPreview() =
    NovelHomePreview(
        CloudStreamThemeMode.Light,
        previewState(
            popularPosts = HomeCarouselState.Loading,
            randomNovels = RandomNovelsState(loading = true),
        ),
    )

@Preview(name = "Home · loading · Amoled", showBackground = true)
@Composable
private fun NovelHomeLoadingAmoledPreview() =
    NovelHomePreview(
        CloudStreamThemeMode.AmoledLight,
        previewState(
            popularPosts = HomeCarouselState.Loading,
            randomNovels = RandomNovelsState(loading = true),
        ),
    )

@Preview(name = "Home · error · Light", showBackground = true)
@Composable
private fun NovelHomeErrorLightPreview() =
    NovelHomePreview(
        CloudStreamThemeMode.Light,
        previewState(
            popularPosts = HomeCarouselState.Error,
            randomNovels = RandomNovelsState(
                loading = false,
                failedSources = listOf("Preview provider"),
            ),
        ),
    )

@Preview(name = "Home · error · Amoled", showBackground = true)
@Composable
private fun NovelHomeErrorAmoledPreview() =
    NovelHomePreview(
        CloudStreamThemeMode.AmoledLight,
        previewState(
            popularPosts = HomeCarouselState.Error,
            randomNovels = RandomNovelsState(
                loading = false,
                failedSources = listOf("Preview provider"),
            ),
        ),
    )
