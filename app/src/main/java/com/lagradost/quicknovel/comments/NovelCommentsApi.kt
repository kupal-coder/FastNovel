package com.lagradost.quicknovel.comments

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.auth.SupabaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

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
    data object DailyLimitReached : CommentWriteResult
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

sealed interface CommentReportResult {
    data object Success : CommentReportResult
    data object AlreadyReported : CommentReportResult
    data object NotSignedIn : CommentReportResult
    data object SessionExpired : CommentReportResult
    data object Failure : CommentReportResult
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
        payloadJson: String?,
        bearerToken: String?,
        prefer: String?,
    ): CommentHttpResponse
}

object NovelCommentsApi {
    const val PAGE_SIZE = 20
    const val MIN_RATING = 1
    const val MAX_RATING = 5
    const val MAX_COMMENT_LENGTH = 2000
    const val MAX_PROVIDER_NAME_LENGTH = 100
    const val MAX_NOVEL_URL_LENGTH = 1000
    const val MAX_REPORT_REASON_LENGTH = 200

    private const val UPSERT_PREFER = "resolution=merge-duplicates,return=representation"

    private val mapper = ObjectMapper()

    private val defaultTransport = CommentHttpTransport { method, endpoint, payloadJson, bearerToken, prefer ->
        val payloadObj = payloadJson?.let { JSONObject(it) }
        val response = SupabaseAuth.requestJson(
            method = method,
            endpoint = endpoint,
            payload = payloadObj,
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
            "?select=id,provider_name,novel_url,user_id,author_name,rating,comment,created_at,updated_at" +
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

    internal fun buildReportCommentUrl(baseUrl: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        return "$cleanBase/rest/v1/novel_comment_reports"
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
        if (
            cleanProvider.isEmpty() ||
            cleanProvider.length > MAX_PROVIDER_NAME_LENGTH ||
            cleanUrl.isEmpty() ||
            cleanUrl.length > MAX_NOVEL_URL_LENGTH
        ) {
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
                payloadJson = null,
                bearerToken = bearerToken,
                prefer = null,
            )
            if (firstAttempt.code == 401 && bearerToken != null) {
                transport.request(
                    method = "GET",
                    endpoint = endpoint,
                    payloadJson = null,
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

        val arrayNode = try {
            mapper.readTree(response.body)
        } catch (_: Exception) {
            return CommentLoadResult.ServiceUnavailable
        }
        if (arrayNode == null || !arrayNode.isArray) {
            return CommentLoadResult.ServiceUnavailable
        }

        val comments = ArrayList<NovelComment>(arrayNode.size())
        for (row in arrayNode) {
            if (row == null || !row.isObject) continue
            val parsed = parseCommentRow(
                row = row,
                defaultUsername = defaultUsername,
            ) ?: continue
            comments.add(parsed)
        }

        return CommentLoadResult.Success(
            NovelCommentsPage(
                comments = comments,
                hasMore = arrayNode.size() >= limit,
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
        if (
            cleanProvider.isEmpty() ||
            cleanProvider.length > MAX_PROVIDER_NAME_LENGTH ||
            cleanUrl.isEmpty() ||
            cleanUrl.length > MAX_NOVEL_URL_LENGTH
        ) {
            return CommentWriteResult.ServiceUnavailable
        }

        val payloadNode = mapper.createObjectNode()
            .put("provider_name", cleanProvider)
            .put("novel_url", cleanUrl)
            .put("user_id", sessionUserId.trim())
            .put("rating", rating)
            .put("comment", cleanComment)
        val payloadJson = mapper.writeValueAsString(payloadNode)

        val response = try {
            transport.request(
                method = "POST",
                endpoint = buildUpsertCommentUrl(baseUrl),
                payloadJson = payloadJson,
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
            if (isDailyLimitError(response.body)) {
                return CommentWriteResult.DailyLimitReached
            }
            return CommentWriteResult.ServiceUnavailable
        }

        val savedComment = parseRepresentationComment(
            body = response.body,
            defaultUsername = defaultUsername,
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
                payloadJson = null,
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

    suspend fun reportComment(
        context: Context,
        commentId: String,
        reason: String = "",
    ): CommentReportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val wasLoggedIn = SupabaseAuth.isLoggedIn(appContext)
        if (!wasLoggedIn) {
            return@withContext CommentReportResult.NotSignedIn
        }
        if (!SupabaseAuth.isConfigured) {
            return@withContext CommentReportResult.Failure
        }

        val accessToken = SupabaseAuth.getValidAccessToken(appContext)
        if (accessToken.isNullOrBlank()) {
            return@withContext if (!SupabaseAuth.isLoggedIn(appContext)) {
                CommentReportResult.SessionExpired
            } else {
                CommentReportResult.Failure
            }
        }

        val sessionUserId = SupabaseAuth.currentUserId(appContext)
        if (sessionUserId.isNullOrBlank()) {
            return@withContext CommentReportResult.SessionExpired
        }

        reportCommentInternal(
            isConfigured = SupabaseAuth.isConfigured,
            baseUrl = baseUrl,
            commentId = commentId,
            sessionUserId = sessionUserId,
            accessToken = accessToken,
            reason = reason,
            transport = defaultTransport,
        )
    }

    internal fun reportCommentInternal(
        isConfigured: Boolean,
        baseUrl: String,
        commentId: String,
        sessionUserId: String?,
        accessToken: String?,
        reason: String = "",
        transport: CommentHttpTransport = defaultTransport,
    ): CommentReportResult {
        if (!isConfigured) return CommentReportResult.Failure
        if (sessionUserId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return CommentReportResult.NotSignedIn
        }

        val cleanId = commentId.trim()
        if (cleanId.isEmpty()) return CommentReportResult.Failure
        val cleanReason = reason.trim().take(MAX_REPORT_REASON_LENGTH)

        val payloadNode = mapper.createObjectNode()
            .put("comment_id", cleanId)
            .put("user_id", sessionUserId.trim())
            .put("reason", cleanReason)
        val payloadJson = mapper.writeValueAsString(payloadNode)

        val response = try {
            transport.request(
                method = "POST",
                endpoint = buildReportCommentUrl(baseUrl),
                payloadJson = payloadJson,
                bearerToken = accessToken,
                prefer = "return=minimal",
            )
        } catch (_: Exception) {
            return CommentReportResult.Failure
        }

        if (response.code == 401 || response.code == 403) {
            return CommentReportResult.SessionExpired
        }
        if (isDuplicateReportError(response.code, response.body)) {
            return CommentReportResult.AlreadyReported
        }
        if (!response.isSuccessful) {
            return CommentReportResult.Failure
        }

        return CommentReportResult.Success
    }

    private fun isDailyLimitError(body: String): Boolean {
        if (body.contains("Daily comment limit reached", ignoreCase = true)) {
            return true
        }
        return try {
            val node = mapper.readTree(body)
            val message = node?.textOrEmpty("message").orEmpty()
            val details = node?.textOrEmpty("details").orEmpty()
            message.contains("Daily comment limit reached", ignoreCase = true) ||
                details.contains("Daily comment limit reached", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun isDuplicateReportError(code: Int, body: String): Boolean {
        if (code == 409) return true
        if (body.contains("23505")) return true
        return try {
            val node = mapper.readTree(body)
            val errorCode = node?.textOrEmpty("code").orEmpty()
            val message = node?.textOrEmpty("message").orEmpty()
            errorCode == "23505" ||
                message.contains("novel_comment_reports_comment_user_key", ignoreCase = true) ||
                message.contains("duplicate key", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseRepresentationComment(
        body: String,
        defaultUsername: String,
    ): NovelComment? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val root = mapper.readTree(trimmed) ?: return null
            val row = when {
                root.isArray && root.size() > 0 -> root.get(0)
                root.isObject -> root
                else -> null
            } ?: return null
            parseCommentRow(
                row = row,
                defaultUsername = defaultUsername,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCommentRow(
        row: JsonNode,
        defaultUsername: String,
    ): NovelComment? {
        val id = row.textOrEmpty("id")
        val providerName = row.textOrEmpty("provider_name")
        val novelUrl = row.textOrEmpty("novel_url")
        val userId = row.textOrEmpty("user_id")
        val rating = row.get("rating")?.asInt(0) ?: 0
        val commentText = row.textOrEmpty("comment")

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

        val authorName = row.textOrEmpty("author_name")
            .takeIf { it.isNotEmpty() && it != "null" }
            ?: defaultUsername

        return NovelComment(
            id = id,
            providerName = providerName,
            novelUrl = novelUrl,
            userId = userId,
            username = authorName,
            avatarUrl = null,
            rating = rating,
            comment = commentText,
            createdAt = row.textOrEmpty("created_at"),
            updatedAt = row.textOrEmpty("updated_at"),
        )
    }

    private fun JsonNode.textOrEmpty(field: String): String {
        val child = this.get(field) ?: return ""
        if (child.isNull) return ""
        return child.asText("").trim()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
