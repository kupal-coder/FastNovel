package com.lagradost.quicknovel.ui.result

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.auth.LoginActivity
import com.lagradost.quicknovel.compose.CloudStreamTheme
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.compose.loadPrimaryColor
import com.lagradost.quicknovel.compose.loadThemeMode

@Composable
internal fun NovelCommentsHost(providerName: String, novelUrl: String) {
    val context = LocalContext.current
    CloudStreamTheme(
        mode = context.loadThemeMode(),
        primaryColor = context.loadPrimaryColor(),
    ) {
        val viewModel: NovelCommentsViewModel = viewModel(
            key = "community-comments:$providerName:$novelUrl",
            factory = NovelCommentsViewModel.provideFactory(context.applicationContext, providerName, novelUrl),
        )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val loginLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            viewModel.refreshSession(authenticationReturned = result.resultCode == Activity.RESULT_OK)
        }
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(lifecycleOwner, viewModel) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSession()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(viewModel) {
            viewModel.refreshSession()
            viewModel.loadFirstPage()
        }

        NovelCommentsSection(
            state = state,
            onRating = viewModel::onRatingSelected,
            onDraft = viewModel::onDraftChanged,
            onSubmit = viewModel::submit,
            onEdit = viewModel::edit,
            onDelete = viewModel::requestDelete,
            onDismissDelete = viewModel::dismissDeleteConfirmation,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelEdit = viewModel::cancelEdit,
            onLoadMore = viewModel::loadMore,
            onRetryLoad = viewModel::retryLoad,
            onSignIn = { loginLauncher.launch(Intent(context, LoginActivity::class.java)) },
        )
    }
}

@Composable
private fun NovelCommentsSection(
    state: NovelCommentsState,
    onRating: (Int) -> Unit,
    onDraft: (String) -> Unit,
    onSubmit: () -> Unit,
    onEdit: (CommunityComment) -> Unit,
    onDelete: (CommunityComment) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelEdit: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoad: () -> Unit,
    onSignIn: () -> Unit,
) {
    if (state.deleteConfirmationId != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.community_delete_title)) },
            text = { Text(stringResource(R.string.community_delete_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.isDeleting) {
                    if (state.isDeleting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.community_deleting))
                        }
                    } else {
                        Text(stringResource(R.string.delete))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete, enabled = !state.isDeleting) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.community_comments_title),
            color = colors.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )

        if (!state.isSignedIn || state.sessionExpired) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainer, CircleShape)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (state.sessionExpired) R.string.community_session_expired
                        else R.string.community_sign_in_required
                    ),
                    color = colors.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSignIn) {
                    Text(stringResource(R.string.sign_in))
                }
            }
        }

        when {
            state.isLoading && state.comments.isEmpty() -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.community_loading_comments), color = colors.onSurfaceVariant)
                }
            }
            state.loadError != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            if (state.loadError == NovelCommentsLoadError.Network) {
                                R.string.community_load_error_network
                            } else {
                                R.string.community_load_error_service
                            }
                        ),
                        color = colors.onBackground,
                    )
                    TextButton(onClick = onRetryLoad) {
                        Text(stringResource(R.string.tag_search_retry))
                    }
                }
            }
            state.comments.isEmpty() -> {
                Text(
                    text = stringResource(R.string.community_empty),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        state.comments.forEach { comment ->
            CommunityCommentItem(
                comment = comment,
                isAuthor = state.isSignedIn && !state.sessionExpired && !state.isSubmitting &&
                    !state.isDeleting && state.userId == comment.userId,
                onEdit = { onEdit(comment) },
                onDelete = { onDelete(comment) },
            )
        }

        if (state.isLoading && state.comments.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.community_loading_comments), color = colors.onSurfaceVariant)
            }
        }
        if (state.isLoadingMore) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.community_loading_more), color = colors.onSurfaceVariant)
            }
        } else if (state.hasMore && state.loadError == null) {
            TextButton(onClick = onLoadMore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.community_load_more))
            }
        }

        val editingExisting = state.editingCommentId != null || state.comments.any {
            state.userId != null && it.userId == state.userId
        }
        Text(
            text = stringResource(R.string.community_your_rating),
            color = colors.onBackground,
            fontWeight = FontWeight.Medium,
        )
        RatingSelector(
            rating = state.rating,
            enabled = !state.isSubmitting && !state.isDeleting,
            onRating = onRating,
        )
        state.rating?.let { rating ->
            Text(
                text = stringResource(R.string.community_selected_rating, rating),
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.community_comment_hint)) },
            minLines = 3,
            maxLines = 6,
            enabled = !state.isSubmitting && !state.isDeleting,
            supportingText = {
                Text(
                    stringResource(
                        R.string.community_comment_count,
                        state.draft.codePointCount(0, state.draft.length),
                    )
                )
            },
        )

        state.writeError?.takeUnless { error ->
            (!state.isSignedIn && error == NovelCommentsWriteError.SignIn) ||
                (state.sessionExpired && error == NovelCommentsWriteError.SessionExpired)
        }?.let { error ->
            val message = when (error) {
                NovelCommentsWriteError.Validation -> R.string.community_validation_error
                NovelCommentsWriteError.SignIn -> R.string.community_sign_in_required
                NovelCommentsWriteError.SessionExpired -> R.string.community_session_expired
                NovelCommentsWriteError.Network -> R.string.community_submit_error_network
                NovelCommentsWriteError.Service -> R.string.community_submit_error_service
            }
            Text(
                text = stringResource(message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
            )
        }

        state.notice?.let { notice ->
            val message = when (notice) {
                NovelCommentsNotice.Posted -> R.string.community_posted
                NovelCommentsNotice.Updated -> R.string.community_updated
                NovelCommentsNotice.Deleted -> R.string.community_deleted
            }
            Text(stringResource(message), color = colors.primary, fontWeight = FontWeight.Medium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.editingCommentId != null) {
                TextButton(onClick = onCancelEdit, enabled = !state.isSubmitting && !state.isDeleting) {
                    Text(stringResource(R.string.community_cancel_edit))
                }
            }
            TextButton(onClick = onSubmit, enabled = !state.isSubmitting && !state.isDeleting) {
                if (state.isSubmitting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.community_submitting))
                    }
                } else {
                    Text(
                        stringResource(
                            if (editingExisting) R.string.community_update_comment
                            else R.string.community_post_comment
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingSelector(rating: Int?, enabled: Boolean, onRating: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { value ->
            val label = stringResource(R.string.community_rate_star, value)
            IconButton(
                onClick = { onRating(value) },
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = label
                    selected = rating == value
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_star_24),
                    contentDescription = null,
                    tint = if (rating != null && value <= rating) colors.primary
                    else colors.onSurfaceVariant.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@Composable
private fun CommunityCommentItem(
    comment: CommunityComment,
    isAuthor: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val username = stringResource(R.string.community_default_name)
    val ratingDescription = stringResource(R.string.community_rating_value, comment.rating)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(12.dp))
            .border(1.dp, colors.onBackground.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_default_avatar),
                contentDescription = stringResource(R.string.community_default_avatar),
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant),
            )
            Text(
                text = username,
                color = colors.onBackground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (isAuthor) {
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_edit_24),
                        contentDescription = stringResource(R.string.community_edit_comment),
                        tint = colors.onBackground,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_delete_outline_24),
                        contentDescription = stringResource(R.string.community_delete_comment),
                        tint = colors.onBackground,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.community_star_filled).repeat(comment.rating) +
                stringResource(R.string.community_star_empty).repeat(5 - comment.rating),
            color = colors.primary,
            fontSize = 18.sp,
            modifier = Modifier.semantics { contentDescription = ratingDescription },
        )
        Text(
            text = comment.comment,
            color = colors.onBackground,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
    }
}
