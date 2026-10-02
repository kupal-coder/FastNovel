package com.lagradost.quicknovel.ui.result

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lagradost.quicknovel.auth.SupabaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class NovelCommentsLoadError {
    Network,
    Service,
}

internal enum class NovelCommentsWriteError {
    Validation,
    SignIn,
    SessionExpired,
    Network,
    Service,
}

internal enum class NovelCommentsNotice {
    Posted,
    Updated,
    Deleted,
}

internal data class NovelCommentsState(
    val comments: List<CommunityComment> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val offset: Int = 0,
    val loadError: NovelCommentsLoadError? = null,
    val isSignedIn: Boolean = false,
    val userId: String? = null,
    val sessionExpired: Boolean = false,
    val draft: String = "",
    val rating: Int? = null,
    val editingCommentId: String? = null,
    val isSubmitting: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteConfirmationId: String? = null,
    val writeError: NovelCommentsWriteError? = null,
    val notice: NovelCommentsNotice? = null,
)

internal class NovelCommentsViewModel(
    private val context: Context,
    private val providerName: String,
    private val novelUrl: String,
) : ViewModel() {
    companion object {
        fun provideFactory(
            context: Context,
            providerName: String,
            novelUrl: String,
        ) = viewModelFactory {
            initializer {
                NovelCommentsViewModel(context.applicationContext, providerName, novelUrl)
            }
        }
    }

    private val mutableState = MutableStateFlow(NovelCommentsState())
    val state = mutableState.asStateFlow()

    private var readJob: Job? = null
    private var mutationJob: Job? = null
    private var readGeneration = 0L

    fun refreshSession(authenticationReturned: Boolean = false) {
        val loggedIn = SupabaseAuth.isLoggedIn(context)
        val userId = if (loggedIn) SupabaseAuth.currentUserId(context) else null
        mutableState.update { current ->
            when {
                authenticationReturned -> current.copy(
                    isSignedIn = loggedIn && userId != null,
                    userId = userId,
                    sessionExpired = false,
                    editingCommentId = if (current.userId != userId) null else current.editingCommentId,
                    writeError = null,
                )
                !loggedIn || userId == null -> current.copy(
                    isSignedIn = false,
                    userId = null,
                    sessionExpired = current.sessionExpired ||
                        current.writeError == NovelCommentsWriteError.SessionExpired,
                )
                current.userId != userId -> current.copy(
                    isSignedIn = true,
                    userId = userId,
                    sessionExpired = false,
                    editingCommentId = null,
                    writeError = null,
                )
                current.sessionExpired -> current
                else -> current.copy(isSignedIn = true, userId = userId)
            }
        }
    }

    fun loadFirstPage(retainComment: CommunityComment? = null) {
        readJob?.cancel()
        val generation = ++readGeneration
        mutableState.update {
            it.copy(
                isLoading = true,
                isLoadingMore = false,
                loadError = null,
                offset = 0,
                hasMore = false,
            )
        }
        readJob = viewModelScope.launch {
            try {
                when (val result = NovelCommentsApi.loadPage(
                    providerName = providerName,
                    novelUrl = novelUrl,
                    offset = 0,
                )) {
                    is NovelCommentsResult.Success -> {
                        if (generation != readGeneration) return@launch
                        mutableState.update { current ->
                            val fetchedIds = result.value.mapTo(mutableSetOf()) { it.id }
                            val retained = retainComment?.takeIf { it.id !in fetchedIds }
                            current.copy(
                                comments = (result.value + listOfNotNull(retained))
                                    .sortedWith(
                                        compareByDescending<CommunityComment> { it.createdAt }
                                            .thenByDescending { it.id }
                                    ),
                                isLoading = false,
                                isLoadingMore = false,
                                offset = result.value.size,
                                hasMore = result.value.size == NovelCommentsApi.PAGE_SIZE,
                                loadError = null,
                            )
                        }
                    }
                    is NovelCommentsResult.Failure -> {
                        if (generation != readGeneration) return@launch
                        mutableState.update {
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                loadError = result.reason.toLoadError(),
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == readGeneration) {
                    mutableState.update {
                        it.copy(isLoading = false, isLoadingMore = false, loadError = NovelCommentsLoadError.Service)
                    }
                }
            }
        }
    }

    fun loadMore() {
        val current = state.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore || current.loadError != null) return
        val requestedOffset = current.offset
        val generation = readGeneration
        mutableState.update { it.copy(isLoadingMore = true, loadError = null) }
        readJob = viewModelScope.launch {
            try {
                when (val result = NovelCommentsApi.loadPage(
                    providerName = providerName,
                    novelUrl = novelUrl,
                    offset = requestedOffset,
                )) {
                    is NovelCommentsResult.Success -> {
                        if (generation != readGeneration) return@launch
                        mutableState.update { latest ->
                            val seen = latest.comments.mapTo(mutableSetOf()) { it.id }
                            val appended = result.value.filter { seen.add(it.id) }
                            latest.copy(
                                comments = latest.comments + appended,
                                isLoadingMore = false,
                                offset = requestedOffset + result.value.size,
                                hasMore = result.value.size == NovelCommentsApi.PAGE_SIZE,
                                loadError = null,
                            )
                        }
                    }
                    is NovelCommentsResult.Failure -> {
                        if (generation != readGeneration) return@launch
                        mutableState.update {
                            it.copy(
                                isLoadingMore = false,
                                loadError = result.reason.toLoadError(),
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == readGeneration) {
                    mutableState.update {
                        it.copy(isLoadingMore = false, loadError = NovelCommentsLoadError.Service)
                    }
                }
            }
        }
    }

    fun retryLoad() {
        val current = state.value
        if (current.offset > 0 && current.hasMore) {
            mutableState.update { it.copy(loadError = null) }
            loadMore()
        } else {
            loadFirstPage()
        }
    }

    fun onDraftChanged(value: String) {
        val trimmedLength = value.codePointCount(0, value.length)
        if (trimmedLength > NovelCommentsApi.MAX_COMMENT_CHARACTERS) {
            mutableState.update { it.copy(writeError = NovelCommentsWriteError.Validation, notice = null) }
        } else {
            mutableState.update {
                it.copy(draft = value, writeError = null, notice = null)
            }
        }
    }

    fun onRatingSelected(value: Int) {
        if (value !in 1..5) return
        mutableState.update { it.copy(rating = value, writeError = null, notice = null) }
    }

    fun edit(comment: CommunityComment) {
        val current = state.value
        if (current.isSubmitting || current.isDeleting) return
        if (!current.isSignedIn || current.sessionExpired || current.userId == null) {
            mutableState.update { it.copy(writeError = NovelCommentsWriteError.SignIn, notice = null) }
            return
        }
        if (comment.userId != current.userId) return
        mutableState.update {
            it.copy(
                draft = comment.comment,
                rating = comment.rating,
                editingCommentId = comment.id,
                writeError = null,
                notice = null,
            )
        }
    }

    fun cancelEdit() {
        mutableState.update {
            it.copy(
                draft = "",
                rating = null,
                editingCommentId = null,
                writeError = null,
                notice = null,
            )
        }
    }

    fun submit() {
        val current = state.value
        if (current.isSubmitting || current.isDeleting) return
        if (!isNovelCommentValid(current.rating, current.draft)) {
            mutableState.update { it.copy(writeError = NovelCommentsWriteError.Validation, notice = null) }
            return
        }
        if (current.sessionExpired) {
            mutableState.update { it.copy(writeError = NovelCommentsWriteError.SessionExpired, isSignedIn = false) }
            return
        }
        if (!current.isSignedIn || current.userId == null || !SupabaseAuth.isLoggedIn(context)) {
            mutableState.update { it.copy(writeError = NovelCommentsWriteError.SignIn, isSignedIn = false) }
            return
        }

        val rating = current.rating ?: return
        val commentText = current.draft.trim()
        val editingId = current.editingCommentId
        val retainedComment = current.comments.firstOrNull { it.id == editingId }
            ?.copy(rating = rating, comment = commentText)
        mutableState.update {
            it.copy(isSubmitting = true, writeError = null, notice = null)
        }
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            when (val result = NovelCommentsApi.upsert(
                context = context,
                providerName = providerName,
                novelUrl = novelUrl,
                rating = rating,
                comment = commentText,
            )) {
                is NovelCommentsResult.Success -> {
                    mutableState.update {
                        it.copy(
                            isSubmitting = false,
                            draft = "",
                            rating = null,
                            editingCommentId = null,
                            writeError = null,
                            notice = if (editingId == null) NovelCommentsNotice.Posted else NovelCommentsNotice.Updated,
                        )
                    }
                    loadFirstPage(retainComment = retainedComment)
                }
                is NovelCommentsResult.Failure -> applyWriteFailure(result.reason)
            }
        }
    }

    fun requestDelete(comment: CommunityComment) {
        val current = state.value
        if (current.isSubmitting || current.isDeleting) return
        if (!current.isSignedIn || current.sessionExpired || current.userId == null) {
            mutableState.update { it.copy(writeError = NovelCommentsWriteError.SignIn, notice = null) }
            return
        }
        if (comment.userId != current.userId) return
        mutableState.update {
            it.copy(deleteConfirmationId = comment.id, writeError = null, notice = null)
        }
    }

    fun dismissDeleteConfirmation() {
        if (state.value.isDeleting) return
        mutableState.update { it.copy(deleteConfirmationId = null) }
    }

    fun confirmDelete() {
        val current = state.value
        val commentId = current.deleteConfirmationId ?: return
        if (current.isDeleting || current.isSubmitting) return
        val comment = current.comments.firstOrNull { it.id == commentId } ?: return
        if (!current.isSignedIn || current.sessionExpired || current.userId == null) {
            mutableState.update {
                it.copy(
                    deleteConfirmationId = null,
                    writeError = NovelCommentsWriteError.SignIn,
                    notice = null,
                )
            }
            return
        }
        if (comment.userId != current.userId) {
            mutableState.update { it.copy(deleteConfirmationId = null) }
            return
        }

        mutableState.update { it.copy(isDeleting = true, writeError = null, notice = null) }
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            when (val result = NovelCommentsApi.delete(context, commentId)) {
                is NovelCommentsResult.Success -> {
                    mutableState.update {
                        it.copy(
                            isDeleting = false,
                            deleteConfirmationId = null,
                            draft = if (it.editingCommentId == commentId) "" else it.draft,
                            rating = if (it.editingCommentId == commentId) null else it.rating,
                            editingCommentId = if (it.editingCommentId == commentId) null else it.editingCommentId,
                            writeError = null,
                            notice = NovelCommentsNotice.Deleted,
                        )
                    }
                    loadFirstPage()
                }
                is NovelCommentsResult.Failure -> {
                    mutableState.update { it.copy(deleteConfirmationId = null) }
                    applyWriteFailure(result.reason)
                }
            }
        }
    }

    private fun applyWriteFailure(reason: NovelCommentsFailure) {
        mutableState.update { current ->
            val writeError = when (reason) {
                NovelCommentsFailure.SignIn -> NovelCommentsWriteError.SignIn
                NovelCommentsFailure.SessionExpired -> NovelCommentsWriteError.SessionExpired
                NovelCommentsFailure.Network -> NovelCommentsWriteError.Network
                NovelCommentsFailure.Service -> NovelCommentsWriteError.Service
            }
            current.copy(
                isSubmitting = false,
                isDeleting = false,
                isSignedIn = if (reason == NovelCommentsFailure.SignIn || reason == NovelCommentsFailure.SessionExpired) false else current.isSignedIn,
                sessionExpired = reason == NovelCommentsFailure.SessionExpired || current.sessionExpired,
                writeError = writeError,
            )
        }
    }
}

private fun NovelCommentsFailure.toLoadError(): NovelCommentsLoadError = when (this) {
    NovelCommentsFailure.Network -> NovelCommentsLoadError.Network
    else -> NovelCommentsLoadError.Service
}
