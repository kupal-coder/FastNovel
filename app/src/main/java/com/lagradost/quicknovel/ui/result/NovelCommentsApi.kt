package com.lagradost.quicknovel.ui.result

import android.content.Context
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.auth.SupabaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

internal data class CommunityComment(
    val id: String,
    val providerName: String,
    val novelUrl: String,
    val userId: String,
    val rating: Int,
    val comment: String,
    val createdAt: String,
    val updatedAt: String,
)

internal enum class NovelCommentsFailure {
    SignIn,
    SessionExpired,
    Network,
    Service,
}

internal sealed interface NovelCommentsResult<out T> {
    data class Success<T>(val value: T) : NovelCommentsResult<T>
    data class Failure(val reason: NovelCommentsFailure) : NovelCommentsResult<Nothing>
}

internal fun isNovelCommentValid(rating: Int?, comment: String): Boolean {
    val trimmed = comment.trim()
    return rating != null && rating in 1..5 && trimmed.isNotEmpty() &&
        trimmed.codePointCount(0, trimmed.length) <= NovelCommentsApi.MAX_COMMENT_CHARACTERS
}

/** Supabase REST access for public comments; author identity is never accepted from the client. */
internal object NovelCommentsApi {
    const val PAGE_SIZE = 20
    const val MAX_COMMENT_CHARACTERS = 2000

    private val baseUrl: String get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')

    internal fun commentsPath(providerName: String, novelUrl: String, offset: Int): String =
        "/rest/v1/novel_comments?select=id,provider_name,novel_url,user_id,rating,comment,created_at,updated_at" +
            "&provider_name=eq.${encode(providerName)}" +
            "&novel_url=eq.${encode(novelUrl)}" +
            "&order=created_at.desc,id.desc&limit=$PAGE_SIZE&offset=${offset.coerceAtLeast(0)}"

    internal fun deletePath(commentId: String): String =
        "/rest/v1/novel_comments?id=eq.${encode(commentId)}"

    suspend fun loadPage(
        providerName: String,
        novelUrl: String,
        offset: Int,
    ): NovelCommentsResult<List<CommunityComment>> = withContext(Dispatchers.IO) {
        if (!SupabaseAuth.isConfigured) {
            return@withContext NovelCommentsResult.Failure(NovelCommentsFailure.Service)
        }
        try {
            // The comments table is publicly readable; do not require a session for reads.
            val response = SupabaseAuth.requestJson(
                method = "GET",
                endpoint = baseUrl + commentsPath(providerName, novelUrl, offset),
                // Public reads use the project's anon key; signed-in writes use the user's session.
                bearerToken = BuildConfig.SUPABASE_KEY.trim(),
            )
            if (!response.isSuccessful) {
                return@withContext NovelCommentsResult.Failure(NovelCommentsFailure.Service)
            }

            val rows = JSONArray(response.body)
            val parsed = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                CommunityComment(
                    id = row.getString("id"),
                    providerName = row.getString("provider_name"),
                    novelUrl = row.getString("novel_url"),
                    userId = row.getString("user_id"),
                    rating = row.getInt("rating").coerceIn(1, 5),
                    comment = row.getString("comment"),
                    createdAt = row.optString("created_at"),
                    updatedAt = row.optString("updated_at"),
                )
            }
            NovelCommentsResult.Success(parsed)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            NovelCommentsResult.Failure(NovelCommentsFailure.Network)
        } catch (_: JSONException) {
            NovelCommentsResult.Failure(NovelCommentsFailure.Service)
        } catch (_: Exception) {
            NovelCommentsResult.Failure(NovelCommentsFailure.Service)
        }
    }

    suspend fun upsert(
        context: Context,
        providerName: String,
        novelUrl: String,
        rating: Int,
        comment: String,
    ): NovelCommentsResult<Unit> = withContext(Dispatchers.IO) {
        if (!isNovelCommentValid(rating, comment)) {
            return@withContext NovelCommentsResult.Failure(NovelCommentsFailure.Service)
        }
        try {
            val accessToken = when (val token = getWriteToken(context)) {
                is NovelCommentsResult.Success -> token.value
                is NovelCommentsResult.Failure -> return@withContext token
            }
            val payload = JSONObject()
                .put("provider_name", providerName)
                .put("novel_url", novelUrl)
                .put("rating", rating)
                .put("comment", comment.trim())
            val response = SupabaseAuth.requestJson(
                method = "POST",
                endpoint = "$baseUrl/rest/v1/novel_comments?on_conflict=provider_name,novel_url,user_id",
                payload = payload,
                bearerToken = accessToken,
                prefer = "resolution=merge-duplicates,return=representation",
            )
            when {
                response.code == 401 -> NovelCommentsResult.Failure(NovelCommentsFailure.SessionExpired)
                response.isSuccessful -> NovelCommentsResult.Success(Unit)
                else -> NovelCommentsResult.Failure(NovelCommentsFailure.Service)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            NovelCommentsResult.Failure(NovelCommentsFailure.Network)
        } catch (_: Exception) {
            NovelCommentsResult.Failure(NovelCommentsFailure.Service)
        }
    }

    suspend fun delete(context: Context, commentId: String): NovelCommentsResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val accessToken = when (val token = getWriteToken(context)) {
                    is NovelCommentsResult.Success -> token.value
                    is NovelCommentsResult.Failure -> return@withContext token
                }
                val response = SupabaseAuth.requestJson(
                    method = "DELETE",
                    endpoint = baseUrl + deletePath(commentId),
                    bearerToken = accessToken,
                )
                when {
                    response.code == 401 -> NovelCommentsResult.Failure(NovelCommentsFailure.SessionExpired)
                    response.isSuccessful -> NovelCommentsResult.Success(Unit)
                    else -> NovelCommentsResult.Failure(NovelCommentsFailure.Service)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                NovelCommentsResult.Failure(NovelCommentsFailure.Network)
            } catch (_: Exception) {
                NovelCommentsResult.Failure(NovelCommentsFailure.Service)
            }
        }

    private suspend fun getWriteToken(context: Context): NovelCommentsResult<String> {
        if (!SupabaseAuth.isConfigured) {
            return NovelCommentsResult.Failure(NovelCommentsFailure.Service)
        }
        if (!SupabaseAuth.isLoggedIn(context) || SupabaseAuth.currentUserId(context) == null) {
            return NovelCommentsResult.Failure(NovelCommentsFailure.SignIn)
        }
        val token = SupabaseAuth.getValidAccessToken(context)
        if (token != null) return NovelCommentsResult.Success(token)
        return NovelCommentsResult.Failure(
            if (!SupabaseAuth.isLoggedIn(context) || SupabaseAuth.currentUserId(context) == null) {
                NovelCommentsFailure.SessionExpired
            } else {
                NovelCommentsFailure.Network
            }
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
