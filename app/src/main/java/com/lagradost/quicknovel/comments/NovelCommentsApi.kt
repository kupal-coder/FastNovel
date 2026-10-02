package com.lagradost.quicknovel.comments

import android.content.Context
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.auth.SupabaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class NovelComment(
    val id: String,
    val providerName: String,
    val novelUrl: String,
    val userId: String,
    val username: String,
    val avatarUrl: String?,
    val rating: Int,
    val comment: String,
    val createdAt: String,
    val updatedAt: String,
)

data class NovelCommentsPage(
    val comments: List<NovelComment>,
    val hasMore: Boolean,
)

sealed interface CommentLoadResult {
    data class Success(val page: NovelCommentsPage) : CommentLoadResult
    data object NetworkError : CommentLoadResult
    data object ServiceUnavailable : CommentLoadResult
}

sealed interface CommentWriteResult {
    data class Success(val comment: NovelComment?) : CommentWriteResult
    data object ValidationError : CommentWriteResult
    data object NotSignedIn : CommentWriteResult
    data object SessionExpired : CommentWriteResult
    data object NetworkError : CommentWriteResult
    data object ServiceUnavailable : CommentWriteResult
}

sealed interface CommentDeleteResult {
    data object Success : CommentDeleteResult
    data object NotSignedIn : CommentDeleteResult
    data object SessionExpired : CommentDeleteResult
    data object NetworkError : CommentDeleteResult
    data object ServiceUnavailable : CommentDeleteResult
}

internal data class CommentHttpResponse(
    val code: Int,
    val body: String,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

internal fun interface CommentHttpTransport {
    fun request(
        method: String,
        endpoint: String,
        payload: JSONObject?,
        bearerToken: String?,
        prefer: String?,
    ): CommentHttpResponse
}

object NovelCommentsApi {
    const val PAGE_SIZE = 20
    const val MIN_RATING = 1
    const val MAX_RATING = 5
    const val MAX_COMMENT_LENGTH = 2000

    private const val UPSERT_PREFER = "resolution=merge-duplicates,return=representation"

    private val usernameCache = ConcurrentHashMap<String, String>()

    private val defaultTransport = CommentHttpTransport { method, endpoint, payload, bearerToken, prefer ->
        val response = SupabaseAuth.requestJson(
            method = method,
            endpoint = endpoint,
            payload = payload,
            bearerToken = bearerToken,
            prefer = prefer,
        )
        CommentHttpResponse(code = response.code, body = response.body)
    }

    private val baseUrl: String get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')

    fun canonicalNovelUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val withoutFragment = trimmed.substringBefore('#').trim()
        return if (withoutFragment.length > "https://a".length) {
            withoutFragment.trimEnd('/')
        } else {
            withoutFragment
        }
    }

    fun isValidSubmission(rating: Int, comment: String): Boolean {
        val trimmed = comment.trim()
        return rating in MIN_RATING..MAX_RATING && trimmed.length in 1..MAX_COMMENT_LENGTH
    }

    fun canModifyComment(comment: NovelComment, currentUserId: String?): Boolean {
        return !currentUserId.isNullOrBlank() && comment.userId == currentUserId
    }

    internal fun buildGetCommentsUrl(
        baseUrl: String,
        providerName: String,
        novelUrl: String,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val encodedProvider = encode(providerName.trim())
        val encodedUrl = encode(canonicalNovelUrl(novelUrl))
        return "$cleanBase/rest/v1/novel_comments" +
            "?select=id,provider_name,novel_url,user_id,rating,comment,created_at,updated_at" +
            "&provider_name=eq.$encodedProvider" +
            "&novel_url=eq.$encodedUrl" +
            "&order=created_at.desc" +
            "&limit=$limit" +
            "&offset=${offset.coerceAtLeast(0)}"
    }

    internal fun buildUpsertCommentUrl(baseUrl: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        return "$cleanBase/rest/v1/novel_comments?on_conflict=provider_name,novel_url,user_id"
    }

    internal fun buildDeleteCommentUrl(baseUrl: String, commentId: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        return "$cleanBase/rest/v1/novel_comments?id=eq.${encode(commentId.trim())}"
    }

    internal fun buildProfileLookupUrl(baseUrl: String, userId: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        return "$cleanBase/rest/v1/profiles?id=eq.${encode(userId.trim())}&select=username"
    }

    suspend fun fetchComments(
        context: Context,
        providerName: String,
        novelUrl: String,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): CommentLoadResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val defaultUsername = appContext.getString(R.string.novel_comments_default_username)
        val token = if (SupabaseAuth.isLoggedIn(appContext)) {
            SupabaseAuth.getValidAccessToken(appContext)
        } else {
            null
        }
        fetchCommentsInternal(
            isConfigured = SupabaseAuth.isConfigured,
            baseUrl = baseUrl,
            providerName = providerName,
            novelUrl = novelUrl,
            offset = offset,
            limit = limit,
            bearerToken = token,
            defaultUsername = defaultUsername,
            transport = defaultTransport,
        )
    }

    internal fun fetchCommentsInternal(
        isConfigured: Boolean,
        baseUrl: String,
        providerName: String,
        novelUrl: String,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
        bearerToken: String? = null,
        defaultUsername: String = "Anonymous",
        transport: CommentHttpTransport = defaultTransport,
    ): CommentLoadResult {
        if (!isConfigured) return CommentLoadResult.ServiceUnavailable
        val cleanProvider = providerName.trim()
        val cleanUrl = canonicalNovelUrl(novelUrl)
        if (cleanProvider.isEmpty() || cleanUrl.isEmpty()) {
            return CommentLoadResult.ServiceUnavailable
        }

        val endpoint = buildGetCommentsUrl(
            baseUrl = baseUrl,
            providerName = cleanProvider,
            novelUrl = cleanUrl,
            limit = limit,
            offset = offset,
        )

        val response = try {
            val firstAttempt = transport.request(
                method = "GET",
                endpoint = endpoint,
                payload = null,
                bearerToken = bearerToken,
                prefer = null,
            )
            if (firstAttempt.code == 401 && bearerToken != null) {
                transport.request(
                    method = "GET",
                    endpoint = endpoint,
                    payload = null,
                    bearerToken = null,
                    prefer = null,
                )
            } else {
                firstAttempt
            }
        } catch (_: IOException) {
            return CommentLoadResult.NetworkError
        } catch (_: Exception) {
            return CommentLoadResult.ServiceUnavailable
        }

        if (!response.isSuccessful) {
            return CommentLoadResult.ServiceUnavailable
        }

        val array = try {
            JSONArray(response.body)
        } catch (_: JSONException) {
            return CommentLoadResult.ServiceUnavailable
        }

        val comments = ArrayList<NovelComment>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val parsed = parseCommentRow(
                row = row,
                baseUrl = baseUrl,
                bearerToken = bearerToken,
                defaultUsername = defaultUsername,
                transport = transport,
            ) ?: continue
            comments.add(parsed)
        }

        return CommentLoadResult.Success(
            NovelCommentsPage(
                comments = comments,
                hasMore = array.length() >= limit,
            )
        )
    }

    suspend fun upsertComment(
        context: Context,
        providerName: String,
        novelUrl: String,
        rating: Int,
        comment: String,
    ): CommentWriteResult = withContext(Dispatchers.IO) {
        if (!isValidSubmission(rating, comment)) {
            return@withContext CommentWriteResult.ValidationError
        }

        val appContext = context.applicationContext
        val wasLoggedIn = SupabaseAuth.isLoggedIn(appContext)
        if (!wasLoggedIn) {
            return@withContext CommentWriteResult.NotSignedIn
        }
        if (!SupabaseAuth.isConfigured) {
            return@withContext CommentWriteResult.ServiceUnavailable
        }

        val accessToken = SupabaseAuth.getValidAccessToken(appContext)
        if (accessToken.isNullOrBlank()) {
            return@withContext if (!SupabaseAuth.isLoggedIn(appContext)) {
                CommentWriteResult.SessionExpired
            } else {
                CommentWriteResult.NetworkError
            }
        }

        val sessionUserId = SupabaseAuth.currentUserId(appContext)
        if (sessionUserId.isNullOrBlank()) {
            return@withContext CommentWriteResult.SessionExpired
        }

        // Refresh cached username for the current user so profile changes reflect immediately
        usernameCache.remove(sessionUserId)
        val defaultUsername = appContext.getString(R.string.novel_comments_default_username)

        upsertCommentInternal(
            isConfigured = SupabaseAuth.isConfigured,
            baseUrl = baseUrl,
            providerName = providerName,
            novelUrl = novelUrl,
            sessionUserId = sessionUserId,
            accessToken = accessToken,
            rating = rating,
            comment = comment,
            defaultUsername = defaultUsername,
            transport = defaultTransport,
        )
    }

    internal fun upsertCommentInternal(
        isConfigured: Boolean,
        baseUrl: String,
        providerName: String,
        novelUrl: String,
        sessionUserId: String?,
        accessToken: String?,
        rating: Int,
        comment: String,
        defaultUsername: String = "Anonymous",
        transport: CommentHttpTransport = defaultTransport,
    ): CommentWriteResult {
        if (!isValidSubmission(rating, comment)) {
            return CommentWriteResult.ValidationError
        }
        if (!isConfigured) {
            return CommentWriteResult.ServiceUnavailable
        }
        if (sessionUserId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return CommentWriteResult.NotSignedIn
        }

        val cleanProvider = providerName.trim()
        val cleanUrl = canonicalNovelUrl(novelUrl)
        val cleanComment = comment.trim()
        if (cleanProvider.isEmpty() || cleanUrl.isEmpty()) {
            return CommentWriteResult.ServiceUnavailable
        }

        val payload = JSONObject()
            .put("provider_name", cleanProvider)
            .put("novel_url", cleanUrl)
            .put("user_id", sessionUserId.trim())
            .put("rating", rating)
            .put("comment", cleanComment)

        val response = try {
            transport.request(
                method = "POST",
                endpoint = buildUpsertCommentUrl(baseUrl),
                payload = payload,
                bearerToken = accessToken,
                prefer = UPSERT_PREFER,
            )
        } catch (_: IOException) {
            return CommentWriteResult.NetworkError
        } catch (_: Exception) {
            return CommentWriteResult.ServiceUnavailable
        }

        if (response.code == 401 || response.code == 403) {
            return CommentWriteResult.SessionExpired
        }
        if (!response.isSuccessful) {
            return CommentWriteResult.ServiceUnavailable
        }

        val savedComment = parseRepresentationComment(
            body = response.body,
            baseUrl = baseUrl,
            bearerToken = accessToken,
            defaultUsername = defaultUsername,
            transport = transport,
        )
        return CommentWriteResult.Success(savedComment)
    }

    suspend fun deleteComment(
        context: Context,
        commentId: String,
    ): CommentDeleteResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val wasLoggedIn = SupabaseAuth.isLoggedIn(appContext)
        if (!wasLoggedIn) {
            return@withContext CommentDeleteResult.NotSignedIn
        }
        if (!SupabaseAuth.isConfigured) {
            return@withContext CommentDeleteResult.ServiceUnavailable
        }

        val accessToken = SupabaseAuth.getValidAccessToken(appContext)
        if (accessToken.isNullOrBlank()) {
            return@withContext if (!SupabaseAuth.isLoggedIn(appContext)) {
                CommentDeleteResult.SessionExpired
            } else {
                CommentDeleteResult.NetworkError
            }
        }

        deleteCommentInternal(
            isConfigured = SupabaseAuth.isConfigured,
            baseUrl = baseUrl,
            commentId = commentId,
            accessToken = accessToken,
            transport = defaultTransport,
        )
    }

    internal fun deleteCommentInternal(
        isConfigured: Boolean,
        baseUrl: String,
        commentId: String,
        accessToken: String?,
        transport: CommentHttpTransport = defaultTransport,
    ): CommentDeleteResult {
        if (!isConfigured) return CommentDeleteResult.ServiceUnavailable
        if (accessToken.isNullOrBlank()) return CommentDeleteResult.NotSignedIn
        val cleanId = commentId.trim()
        if (cleanId.isEmpty()) return CommentDeleteResult.ServiceUnavailable

        val response = try {
            transport.request(
                method = "DELETE",
                endpoint = buildDeleteCommentUrl(baseUrl, cleanId),
                payload = null,
                bearerToken = accessToken,
                prefer = null,
            )
        } catch (_: IOException) {
            return CommentDeleteResult.NetworkError
        } catch (_: Exception) {
            return CommentDeleteResult.ServiceUnavailable
        }

        if (response.code == 401 || response.code == 403) {
            return CommentDeleteResult.SessionExpired
        }
        if (!response.isSuccessful) {
            return CommentDeleteResult.ServiceUnavailable
        }

        return CommentDeleteResult.Success
    }

    private fun parseRepresentationComment(
        body: String,
        baseUrl: String,
        bearerToken: String?,
        defaultUsername: String,
        transport: CommentHttpTransport,
    ): NovelComment? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val row = if (trimmed.startsWith("[")) {
                JSONArray(trimmed).optJSONObject(0)
            } else {
                JSONObject(trimmed)
            } ?: return null
            parseCommentRow(
                row = row,
                baseUrl = baseUrl,
                bearerToken = bearerToken,
                defaultUsername = defaultUsername,
                transport = transport,
            )
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseCommentRow(
        row: JSONObject,
        baseUrl: String,
        bearerToken: String?,
        defaultUsername: String,
        transport: CommentHttpTransport,
    ): NovelComment? {
        val id = row.optString("id").trim()
        val providerName = row.optString("provider_name").trim()
        val novelUrl = row.optString("novel_url").trim()
        val userId = row.optString("user_id").trim()
        val rating = row.optInt("rating", 0)
        val commentText = if (row.isNull("comment")) "" else row.optString("comment").trim()

        if (
            id.isEmpty() ||
            providerName.isEmpty() ||
            novelUrl.isEmpty() ||
            userId.isEmpty() ||
            rating !in MIN_RATING..MAX_RATING ||
            commentText.isEmpty()
        ) {
            return null
        }

        val inlineUsername = extractInlineUsername(row)
        val username = inlineUsername ?: resolveUsernameForUserId(
            userId = userId,
            baseUrl = baseUrl,
            bearerToken = bearerToken,
            defaultUsername = defaultUsername,
            transport = transport,
        )
        val avatarUrl = extractVerifiedPublicAvatarUrl(row)

        return NovelComment(
            id = id,
            providerName = providerName,
            novelUrl = novelUrl,
            userId = userId,
            username = username,
            avatarUrl = avatarUrl,
            rating = rating,
            comment = commentText,
            createdAt = row.optString("created_at", ""),
            updatedAt = row.optString("updated_at", ""),
        )
    }

    private fun extractInlineUsername(row: JSONObject): String? {
        if (!row.isNull("username")) {
            val direct = row.optString("username").trim()
            if (direct.isNotEmpty() && direct != "null") return direct
        }
        val profileObj = row.optJSONObject("profiles")
        if (profileObj != null && !profileObj.isNull("username")) {
            val nested = profileObj.optString("username").trim()
            if (nested.isNotEmpty() && nested != "null") return nested
        }
        return null
    }

    private fun extractVerifiedPublicAvatarUrl(row: JSONObject): String? {
        val candidate = when {
            !row.isNull("avatar_url") -> row.optString("avatar_url").trim()
            row.optJSONObject("profiles")?.isNull("avatar_url") == false ->
                row.optJSONObject("profiles")?.optString("avatar_url")?.trim().orEmpty()
            else -> ""
        }
        return candidate.takeIf {
            it.isNotEmpty() &&
                it != "null" &&
                (it.startsWith("https://") || it.startsWith("http://"))
        }
    }

    private fun resolveUsernameForUserId(
        userId: String,
        baseUrl: String,
        bearerToken: String?,
        defaultUsername: String,
        transport: CommentHttpTransport,
    ): String {
        usernameCache[userId]?.let { return it }

        val endpoint = buildProfileLookupUrl(baseUrl, userId)
        val response = try {
            val first = transport.request(
                method = "GET",
                endpoint = endpoint,
                payload = null,
                bearerToken = bearerToken,
                prefer = null,
            )
            if (first.code == 401 && bearerToken != null) {
                transport.request(
                    method = "GET",
                    endpoint = endpoint,
                    payload = null,
                    bearerToken = null,
                    prefer = null,
                )
            } else {
                first
            }
        } catch (_: Exception) {
            return defaultUsername
        }

        if (!response.isSuccessful) {
            return defaultUsername
        }

        val fetchedUsername = try {
            val array = JSONArray(response.body)
            val obj = array.optJSONObject(0)
            if (obj != null && !obj.isNull("username")) {
                obj.optString("username").trim().takeIf { it.isNotEmpty() && it != "null" }
            } else {
                null
            }
        } catch (_: JSONException) {
            null
        }

        if (fetchedUsername != null) {
            usernameCache[userId] = fetchedUsername
            return fetchedUsername
        }
        return defaultUsername
    }

    internal fun clearUsernameCacheForTests() {
        usernameCache.clear()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
