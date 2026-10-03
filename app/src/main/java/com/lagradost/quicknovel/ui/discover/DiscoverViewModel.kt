package com.lagradost.quicknovel.ui.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.auth.LibrarySync
import com.lagradost.quicknovel.auth.LibrarySync.LibraryNovel
import com.lagradost.quicknovel.auth.SupabaseAuth
import com.lagradost.quicknovel.discover.DiscoverApi
import com.lagradost.quicknovel.discover.DiscoverError
import com.lagradost.quicknovel.discover.DiscoverPost
import com.lagradost.quicknovel.discover.DiscoverResult
import com.lagradost.quicknovel.discover.DiscoverSort
import com.lagradost.quicknovel.discover.VoteError
import com.lagradost.quicknovel.discover.discoverVoteDelta
import com.lagradost.quicknovel.discover.isDiscoverBodyValid
import com.lagradost.quicknovel.discover.normalizeDiscoverTags
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.util.Apis.Companion.getApiFromNameOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class DiscoverGate { Loading, SignedOut, Username, Ready, Error }

/** One-shot handoff so other tabs can open Discover with a chosen sort (Home "See more" -> Top). */
object DiscoverNavigation {
    @Volatile
    private var pendingSort: DiscoverSort? = null

    fun requestSort(sort: DiscoverSort) {
        pendingSort = sort
    }

    fun consumePendingSort(): DiscoverSort? = pendingSort.also { pendingSort = null }
}

data class WritePostState(
    val loadingLibrary: Boolean = true,
    val novels: List<LibraryNovel> = emptyList(),
    val novel: LibraryNovel? = null,
    val tags: List<String> = emptyList(),
    val providerTags: List<String> = emptyList(),
    val loadingTags: Boolean = false,
    val body: String = "",
    val rating: Int? = null,
    val posting: Boolean = false,
    val error: DiscoverError? = null,
) {
    val canPost: Boolean get() = novel != null && !posting && !loadingTags && isDiscoverBodyValid(body)
}

data class DiscoverState(
    val gate: DiscoverGate = DiscoverGate.Loading,
    val userId: String? = null,
    val sort: DiscoverSort = DiscoverSort.New,
    val posts: List<DiscoverPost> = emptyList(),
    val popularTags: List<String> = emptyList(),
    val selectedTag: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val error: DiscoverError? = null,
    val tagsError: DiscoverError? = null,
    val moreError: DiscoverError? = null,
    val writer: WritePostState? = null,
    val confirmation: DiscoverPost? = null,
    val actionBusy: Boolean = false,
    val actionError: DiscoverError? = null,
    val notice: Int? = null,
)

sealed interface DiscoverAction {
    data object Refresh : DiscoverAction
    data object Retry : DiscoverAction
    data object LoadMore : DiscoverAction
    data class Filter(val tag: String?) : DiscoverAction
    data class Sort(val sort: DiscoverSort) : DiscoverAction
    data class Vote(val post: DiscoverPost, val value: Int) : DiscoverAction
    data object UnsafeNovel : DiscoverAction
    data object Write : DiscoverAction
    data object CloseWriter : DiscoverAction
    data object RetryLibrary : DiscoverAction
    data class PickNovel(val novel: LibraryNovel) : DiscoverAction
    data class Body(val body: String) : DiscoverAction
    data class Rating(val rating: Int?) : DiscoverAction
    data class AddTag(val tag: String) : DiscoverAction
    data class RemoveTag(val tag: String) : DiscoverAction
    data object Post : DiscoverAction
    data class Ask(val post: DiscoverPost) : DiscoverAction
    data object DismissConfirmation : DiscoverAction
    data object Confirm : DiscoverAction
    data object ClearNotice : DiscoverAction
}

class DiscoverViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutableState = MutableStateFlow(DiscoverState())
    val state = mutableState.asStateFlow()
    private var profileJob: Job? = null
    private var feedJob: Job? = null
    private var popularJob: Job? = null
    private var libraryJob: Job? = null
    private var tagJob: Job? = null
    private var mutationJob: Job? = null

    private fun sessionId(): String? =
        SupabaseAuth.currentUserId(context).takeIf { SupabaseAuth.isLoggedIn(context) }

    /** Recheck after LoginActivity or Settings returns, without losing the selected filter. */
    fun onResume(pendingSort: DiscoverSort? = null) {
        if (pendingSort != null && pendingSort != state.value.sort) {
            mutableState.update { it.copy(sort = pendingSort) }
        }
        val userId = sessionId()
        val sort = state.value.sort
        profileJob?.cancel()
        if (userId != state.value.userId || userId == null) {
            feedJob?.cancel()
            popularJob?.cancel()
            libraryJob?.cancel()
            tagJob?.cancel()
            mutationJob?.cancel()
            mutableState.value = DiscoverState(
                userId = userId,
                gate = if (userId == null) DiscoverGate.SignedOut else DiscoverGate.Loading,
                sort = sort,
            )
        }
        if (userId == null) return
        if (state.value.gate != DiscoverGate.Ready) {
            mutableState.update { it.copy(gate = DiscoverGate.Loading, error = null) }
        }
        profileJob = viewModelScope.launch {
            val result = DiscoverApi.profile(context)
            if (sessionId() != userId) { onResume(); return@launch }
            when (result) {
                is DiscoverResult.Success -> {
                    if (result.value.isNullOrBlank()) {
                        mutableState.update { it.copy(gate = DiscoverGate.Username, writer = null) }
                    } else {
                        mutableState.update { it.copy(gate = DiscoverGate.Ready, error = null) }
                        refresh()
                    }
                }
                is DiscoverResult.Failure -> if (!accountError(result.error)) {
                    mutableState.update {
                        it.copy(
                            gate = if (it.gate == DiscoverGate.Ready) it.gate else DiscoverGate.Error,
                            error = result.error,
                        )
                    }
                }
            }
        }
    }

    private fun accountError(error: DiscoverError): Boolean {
        val gate = when (error) {
            DiscoverError.SignIn -> DiscoverGate.SignedOut
            DiscoverError.Username -> DiscoverGate.Username
            else -> return false
        }
        mutableState.update {
            it.copy(gate = gate, posts = emptyList(), writer = null, confirmation = null,
                refreshing = false, loadingMore = false, actionBusy = false, error = error)
        }
        return true
    }

    private fun currentQuery(userId: String, tag: String?, sort: DiscoverSort): Boolean =
        sessionId() == userId && state.value.userId == userId &&
                state.value.selectedTag == tag && state.value.sort == sort &&
                state.value.gate == DiscoverGate.Ready

    private fun refresh(clear: Boolean = false) {
        val userId = state.value.userId ?: return
        val tag = state.value.selectedTag
        val sort = state.value.sort
        if (!currentQuery(userId, tag, sort)) { onResume(); return }
        feedJob?.cancel()
        mutableState.update {
            it.copy(refreshing = true, loadingMore = false, error = null, moreError = null,
                posts = if (clear) emptyList() else it.posts, hasMore = false)
        }
        feedJob = viewModelScope.launch {
            val result = DiscoverApi.feed(context, 0, tag, sort)
            if (sessionId() != userId) { onResume(); return@launch }
            if (!currentQuery(userId, tag, sort)) return@launch
            when (result) {
                is DiscoverResult.Success -> mutableState.update {
                    it.copy(posts = result.value, offset = result.value.size,
                        hasMore = result.value.size == DiscoverApi.PAGE_SIZE, refreshing = false)
                }
                is DiscoverResult.Failure -> if (!accountError(result.error)) {
                    mutableState.update { it.copy(refreshing = false, error = result.error) }
                }
            }
        }
        // An active filter must not replace/reorder the popular chip list.
        if (tag == null) refreshPopularTags(userId, sort) else popularJob?.cancel()
    }

    private fun refreshPopularTags(userId: String, sort: DiscoverSort) {
        popularJob?.cancel()
        mutableState.update { it.copy(tagsError = null) }
        popularJob = viewModelScope.launch {
            val result = DiscoverApi.popularTags(context)
            if (sessionId() != userId) { onResume(); return@launch }
            if (!currentQuery(userId, null, sort)) return@launch
            when (result) {
                is DiscoverResult.Success -> mutableState.update { it.copy(popularTags = result.value) }
                is DiscoverResult.Failure -> if (!accountError(result.error)) {
                    mutableState.update { it.copy(tagsError = result.error) }
                }
            }
        }
    }

    private fun loadMore() {
        val before = state.value
        val userId = before.userId ?: return
        if (!before.hasMore || before.refreshing || before.loadingMore ||
            !currentQuery(userId, before.selectedTag, before.sort)) return
        mutableState.update { it.copy(loadingMore = true, moreError = null) }
        feedJob = viewModelScope.launch {
            val result = DiscoverApi.feed(context, before.offset, before.selectedTag, before.sort)
            if (sessionId() != userId) { onResume(); return@launch }
            if (!currentQuery(userId, before.selectedTag, before.sort)) return@launch
            when (result) {
                is DiscoverResult.Success -> mutableState.update {
                    it.copy(posts = (it.posts + result.value).distinctBy { post -> post.id },
                        offset = before.offset + result.value.size, loadingMore = false,
                        hasMore = result.value.size == DiscoverApi.PAGE_SIZE)
                }
                is DiscoverResult.Failure -> if (!accountError(result.error)) {
                    mutableState.update { it.copy(loadingMore = false, moreError = result.error) }
                }
            }
        }
    }

    /** Optimistic Reddit-style vote: apply first, roll back with a snackbar on failure. */
    private fun vote(post: DiscoverPost, targetVote: Int) {
        if (targetVote !in -1..1) return
        val userId = state.value.userId
        if (userId == null || !SupabaseAuth.isLoggedIn(context)) {
            // Signed out: show the sign-in prompt instead of voting.
            mutableState.update { it.copy(notice = VoteError.SignIn.text) }
            return
        }
        val previous = post.myVote
        val delta = discoverVoteDelta(previous, targetVote)
        updatePost(post.id) { it.copy(score = it.score + delta, myVote = targetVote) }
        viewModelScope.launch {
            val error = DiscoverApi.setVote(context, post.id, targetVote)
            if (sessionId() != userId) return@launch
            if (error != null) {
                updatePost(post.id) { it.copy(score = it.score - delta, myVote = previous) }
                mutableState.update { it.copy(notice = error.text) }
            }
        }
    }

    private fun updatePost(postId: String, transform: (DiscoverPost) -> DiscoverPost) {
        mutableState.update { state ->
            state.copy(posts = state.posts.map { if (it.id == postId) transform(it) else it })
        }
    }

    private fun openWriter(reload: Boolean = false) {
        if (state.value.gate != DiscoverGate.Ready || state.value.writer?.posting == true ||
            (state.value.writer != null && !reload)) return
        libraryJob?.cancel()
        mutableState.update { it.copy(writer = (it.writer ?: WritePostState()).copy(loadingLibrary = true, error = null)) }
        libraryJob = viewModelScope.launch {
            try {
                val novels = withContext(Dispatchers.IO) { LibrarySync.localNovels(context) }
                mutableState.update { it.copy(writer = it.writer?.copy(loadingLibrary = false, novels = novels)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(writer = it.writer?.copy(loadingLibrary = false, error = DiscoverError.Generic)) }
            }
        }
    }

    private fun pickNovel(novel: LibraryNovel) {
        val writer = state.value.writer ?: return
        if (writer.posting || novel !in writer.novels) return
        tagJob?.cancel()
        val api = getApiFromNameOrNull(novel.provider)
        val options = api?.tags.orEmpty().filterNot {
            it.first.equals("All", ignoreCase = true) || it.second.isBlank() || it.second.equals("all", ignoreCase = true)
        }.mapNotNull { normalizeDiscoverTags(listOf(it.first)).firstOrNull() }.distinct()
        mutableState.update {
            it.copy(writer = writer.copy(novel = novel, tags = normalizeDiscoverTags(novel.tags.orEmpty()),
                providerTags = options, loadingTags = novel.tags == null && api != null, error = null))
        }
        if (novel.tags != null || api == null) return
        tagJob = viewModelScope.launch {
            val tags = try {
                withTimeoutOrNull(15_000) {
                    when (val result = api.load(novel.url)) {
                        is Resource.Success -> result.value.tags
                        else -> null
                    }
                }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // Changing novels or dismissing the sheet must not apply an old prefill.
            mutableState.update {
                val current = it.writer
                if (current == null || current.novel != novel) it else it.copy(
                    writer = current.copy(tags = normalizeDiscoverTags(tags), loadingTags = false))
            }
        }
    }

    private fun post() {
        val writer = state.value.writer ?: return
        val novel = writer.novel ?: return
        val userId = state.value.userId ?: return
        if (!writer.canPost || !currentQuery(userId, state.value.selectedTag)) return
        mutableState.update { it.copy(writer = writer.copy(posting = true, error = null)) }
        mutationJob = viewModelScope.launch {
            val result = DiscoverApi.create(context, novel, writer.body, writer.rating, writer.tags)
            if (sessionId() != userId) { onResume(); return@launch }
            when (result) {
                is DiscoverResult.Success -> {
                    mutableState.update { it.copy(writer = null, notice = R.string.discover_posted) }
                    refresh()
                }
                is DiscoverResult.Failure -> if (!accountError(result.error)) {
                    mutableState.update { it.copy(writer = it.writer?.copy(posting = false, error = result.error)) }
                }
            }
        }
    }

    private fun confirm() {
        val before = state.value
        val post = before.confirmation ?: return
        val userId = before.userId ?: return
        if (before.actionBusy || !currentQuery(userId, before.selectedTag)) return
        val own = post.userId == userId
        mutableState.update { it.copy(actionBusy = true, actionError = null) }
        mutationJob = viewModelScope.launch {
            val result = if (own) DiscoverApi.delete(context, post.id) else DiscoverApi.report(context, post.id)
            if (sessionId() != userId) { onResume(); return@launch }
            when (result) {
                is DiscoverResult.Success -> {
                    mutableState.update { it.copy(confirmation = null, actionBusy = false,
                        notice = if (own) R.string.discover_deleted else R.string.discover_reported) }
                    if (own) refresh()
                }
                is DiscoverResult.Failure -> if (!accountError(result.error)) {
                    mutableState.update { it.copy(actionBusy = false, actionError = result.error) }
                }
            }
        }
    }

    fun onAction(action: DiscoverAction) {
        when (action) {
            DiscoverAction.Refresh -> refresh()
            DiscoverAction.Retry -> if (state.value.gate == DiscoverGate.Ready) refresh() else onResume()
            DiscoverAction.LoadMore -> loadMore()
            is DiscoverAction.Filter -> {
                val tag = normalizeDiscoverTags(listOfNotNull(action.tag)).firstOrNull()
                mutableState.update { it.copy(selectedTag = tag.takeUnless { selected -> selected == it.selectedTag }, tagsError = null) }
                refresh(clear = true)
            }
            is DiscoverAction.Sort -> if (state.value.sort != action.sort) {
                mutableState.update { it.copy(sort = action.sort) }
                refresh(clear = true)
            }
            is DiscoverAction.Vote -> vote(action.post, action.value)
            DiscoverAction.UnsafeNovel ->
                mutableState.update { it.copy(notice = R.string.discover_cannot_open_novel) }
            DiscoverAction.Write -> openWriter()
            DiscoverAction.RetryLibrary -> openWriter(reload = true)
            DiscoverAction.CloseWriter -> if (state.value.writer?.posting != true) {
                libraryJob?.cancel()
                tagJob?.cancel()
                mutableState.update { it.copy(writer = null) }
            }
            is DiscoverAction.PickNovel -> pickNovel(action.novel)
            is DiscoverAction.Body -> editWriter { it.copy(body = action.body, error = null) }
            is DiscoverAction.Rating -> editWriter { it.copy(rating = action.rating?.takeIf { rating -> rating in 1..5 }) }
            is DiscoverAction.AddTag -> editWriter {
                if (it.loadingTags || action.tag !in it.providerTags) it
                else it.copy(tags = normalizeDiscoverTags(it.tags + action.tag))
            }
            is DiscoverAction.RemoveTag -> editWriter { it.copy(tags = normalizeDiscoverTags(it.tags - action.tag)) }
            DiscoverAction.Post -> post()
            is DiscoverAction.Ask -> mutableState.update { it.copy(confirmation = action.post, actionError = null) }
            DiscoverAction.DismissConfirmation -> if (!state.value.actionBusy) {
                mutableState.update { it.copy(confirmation = null, actionError = null) }
            }
            DiscoverAction.Confirm -> confirm()
            DiscoverAction.ClearNotice -> mutableState.update { it.copy(notice = null) }
        }
    }

    private fun editWriter(edit: (WritePostState) -> WritePostState) {
        mutableState.update { state ->
            state.copy(writer = state.writer?.let { if (it.posting) it else edit(it) })
        }
    }
}
