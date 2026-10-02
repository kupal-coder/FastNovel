package com.lagradost.quicknovel.ui.result

import androidx.annotation.StringRes
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.comments.CommentLoadResult
import com.lagradost.quicknovel.comments.NovelComment
import com.lagradost.quicknovel.comments.NovelCommentsApi

data class ResultCommentsUiState(
    val providerName: String = "",
    val novelUrl: String = "",
    val comments: List<NovelComment> = emptyList(),
    val hasMore: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSubmitting: Boolean = false,
    @StringRes val loadErrorRes: Int? = null,
    val draftRating: Int = 0,
    val draftComment: String = "",
    val isEditingExisting: Boolean = false,
    @StringRes val formStatusRes: Int? = null,
    val formStatusIsError: Boolean = false,
) {
    fun findOwnComment(currentUserId: String?): NovelComment? {
        if (currentUserId.isNullOrBlank()) return null
        return comments.firstOrNull { it.userId == currentUserId }
    }
}

internal fun applyDraftRatingUpdate(
    state: ResultCommentsUiState,
    rating: Int,
): ResultCommentsUiState {
    val clamped = rating.coerceIn(0, NovelCommentsApi.MAX_RATING)
    val clearValidation = state.formStatusRes == R.string.novel_comments_validation_error &&
        NovelCommentsApi.isValidSubmission(clamped, state.draftComment)
    return state.copy(
        draftRating = clamped,
        formStatusRes = if (clearValidation) null else state.formStatusRes,
    )
}

internal fun applyDraftCommentUpdate(
    state: ResultCommentsUiState,
    text: String,
): ResultCommentsUiState {
    if (state.draftComment == text) return state
    val clearValidation = state.formStatusRes == R.string.novel_comments_validation_error &&
        NovelCommentsApi.isValidSubmission(state.draftRating, text)
    return state.copy(
        draftComment = text,
        formStatusRes = if (clearValidation) null else state.formStatusRes,
    )
}

internal fun applyInitialLoadCompletion(
    state: ResultCommentsUiState,
    providerName: String,
    novelUrl: String,
    result: CommentLoadResult,
): ResultCommentsUiState {
    if (state.providerName.isNotEmpty() &&
        (state.providerName != providerName || state.novelUrl != novelUrl)
    ) {
        return state
    }
    return when (result) {
        is CommentLoadResult.Success -> state.copy(
            providerName = providerName,
            novelUrl = novelUrl,
            comments = result.page.comments,
            hasMore = result.page.hasMore,
            isInitialLoading = false,
            isLoadingMore = false,
            loadErrorRes = null,
        )

        CommentLoadResult.NetworkError -> state.copy(
            providerName = providerName,
            novelUrl = novelUrl,
            isInitialLoading = false,
            isLoadingMore = false,
            loadErrorRes = R.string.novel_comments_load_network_error,
        )

        CommentLoadResult.ServiceUnavailable -> state.copy(
            providerName = providerName,
            novelUrl = novelUrl,
            isInitialLoading = false,
            isLoadingMore = false,
            loadErrorRes = R.string.novel_comments_service_unavailable,
        )
    }
}

internal fun mergeUpsertedComment(
    existingComments: List<NovelComment>,
    upserted: NovelComment,
): List<NovelComment> {
    val existingIndex = existingComments.indexOfFirst {
        it.id == upserted.id ||
            (it.userId == upserted.userId &&
                it.providerName == upserted.providerName &&
                it.novelUrl == upserted.novelUrl)
    }
    if (existingIndex >= 0) {
        return existingComments.mapIndexed { index, item ->
            if (index == existingIndex) upserted else item
        }
    }
    return listOf(upserted) + existingComments
}

internal fun mergePaginatedComments(
    existingComments: List<NovelComment>,
    nextPageComments: List<NovelComment>,
): List<NovelComment> {
    if (nextPageComments.isEmpty()) return existingComments
    val result = ArrayList<NovelComment>(existingComments.size + nextPageComments.size)
    val seenIds = HashSet<String>()
    val seenUserKeys = HashSet<String>()

    for (item in existingComments) {
        if (seenIds.add(item.id) && seenUserKeys.add("${item.providerName}|${item.novelUrl}|${item.userId}")) {
            result.add(item)
        }
    }
    for (item in nextPageComments) {
        if (seenIds.add(item.id) && seenUserKeys.add("${item.providerName}|${item.novelUrl}|${item.userId}")) {
            result.add(item)
        }
    }
    return result
}
