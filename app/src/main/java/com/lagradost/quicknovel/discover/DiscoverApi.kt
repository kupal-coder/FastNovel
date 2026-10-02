package com.lagradost.quicknovel.discover

import android.content.Context
import androidx.annotation.StringRes
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.auth.LibrarySync.LibraryNovel
import com.lagradost.quicknovel.auth.ProfileResult
import com.lagradost.quicknovel.auth.SupabaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun String.discoverCharacterCount(): Int = codePointCount(0, length)

internal fun isDiscoverBodyValid(body: String): Boolean =
    body.discoverCharacterCount() <= 500 && body.trim().discoverCharacterCount() in 10..500

/** Shared by the picker, prefill, cards and every outgoing tag/filter. No fixed taxonomy. */
fun normalizeDiscoverTags(tags: Iterable<String>): List<String> {
    val normalized = linkedSetOf<String>()
    var characters = 0
    for (raw in tags) {
        val tag = raw.lowercase(Locale.ROOT)
            .filterNot { it in "{}\"," }
            .replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        val length = tag.discoverCharacterCount()
        if (tag.isEmpty() || length > 40 || tag in normalized || characters + length > 300) continue
        normalized.add(tag)
        characters += length
        if (normalized.size == 8) break
    }
    return normalized.toList()
}

data class DiscoverPost(
    val id: String,
    val userId: String,
    val authorName: String,
    val provider: String,
    val novelUrl: String,
    val novelTitle: String,
    val coverUrl: String?,
    val body: String,
    val rating: Int?,
    val tags: List<String>,
    val createdAt: Long,
)

enum class DiscoverError(@StringRes val text: Int) {
    SignIn(R.string.username_error_sign_in_again),
    Username(R.string.discover_username_required),
    Duplicate(R.string.discover_already_posted),
    DailyLimit(R.string.discover_daily_limit),
    CouldNotPost(R.string.discover_could_not_post),
    Offline(R.string.username_error_no_internet),
    Generic(R.string.discover_error),
    AlreadyReported(R.string.discover_already_reported),
}

sealed interface DiscoverResult<out T> {
    data class Success<T>(val value: T) : DiscoverResult<T>
    data class Failure(val error: DiscoverError) : DiscoverResult<Nothing>
}

/** Supabase REST only, using the existing HttpURLConnection + org.json transport (API 23). */
object DiscoverApi {
    const val PAGE_SIZE = 20
    private val baseUrl: String get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')

    internal fun errorOf(code: Int, body: String): DiscoverError = when {
        code == 401 -> DiscoverError.SignIn
        code == 409 -> DiscoverError.Duplicate
        body.contains("username", ignoreCase = true) -> DiscoverError.Username
        body.contains("limit", ignoreCase = true) -> DiscoverError.DailyLimit
        code in 400..499 -> DiscoverError.CouldNotPost
        else -> DiscoverError.Generic
    }

    internal fun feedPath(offset: Int, tag: String?): String {
        val filter = normalizeDiscoverTags(listOfNotNull(tag)).firstOrNull()
        val query = filter?.let { "&tags=" + encode("cs.{\"$it\"}") }.orEmpty()
        return "/rest/v1/discover_posts?select=*&order=created_at.desc&limit=$PAGE_SIZE" +
                "&offset=${offset.coerceAtLeast(0)}$query"
    }

    suspend fun profile(context: Context): DiscoverResult<String?> = try {
        when (val result = SupabaseAuth.getProfile(context)) {
            is ProfileResult.Success -> DiscoverResult.Success(result.username)
            is ProfileResult.Failure -> DiscoverResult.Failure(when (result.message) {
                context.getString(R.string.username_error_sign_in_again) -> DiscoverError.SignIn
                context.getString(R.string.username_error_no_internet) -> DiscoverError.Offline
                else -> DiscoverError.Generic
            })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        DiscoverResult.Failure(DiscoverError.Offline)
    } catch (_: Exception) {
        DiscoverResult.Failure(DiscoverError.Generic)
    }

    suspend fun feed(context: Context, offset: Int, tag: String?): DiscoverResult<List<DiscoverPost>> =
        request(context, "GET", feedPath(offset, tag)) { body ->
            val rows = JSONArray(body)
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val tags = row.optJSONArray("tags") ?: JSONArray()
                DiscoverPost(
                    id = row.getString("id"),
                    userId = row.getString("user_id"),
                    authorName = row.getString("author_name"),
                    provider = row.getString("provider"),
                    novelUrl = row.getString("novel_url"),
                    novelTitle = row.getString("novel_title"),
                    coverUrl = if (row.isNull("cover_url")) null else row.optString("cover_url"),
                    body = row.getString("body"),
                    rating = if (row.isNull("rating")) null else row.optInt("rating").takeIf { it in 1..5 },
                    tags = normalizeDiscoverTags((0 until tags.length()).map { tags.getString(it) }),
                    createdAt = parseCreatedAt(row.getString("created_at")),
                )
            }
        }

    suspend fun popularTags(context: Context): DiscoverResult<List<String>> =
        request(context, "POST", "/rest/v1/rpc/discover_top_tags", JSONObject().put("p_limit", 30)) { body ->
            val rows = JSONArray(body)
            // Normalize individually: the popular row can contain 30 tags, not just eight.
            (0 until rows.length()).mapNotNull {
                normalizeDiscoverTags(listOf(rows.getJSONObject(it).getString("tag"))).firstOrNull()
            }.distinct().take(30)
        }

    suspend fun create(
        context: Context,
        novel: LibraryNovel,
        body: String,
        rating: Int?,
        tags: List<String>,
    ): DiscoverResult<Unit> {
        val userId = SupabaseAuth.currentUserId(context)
            ?: return DiscoverResult.Failure(DiscoverError.SignIn)
        if (!isDiscoverBodyValid(body) || (rating != null && rating !in 1..5)) {
            return DiscoverResult.Failure(DiscoverError.CouldNotPost)
        }
        val payload = JSONObject()
            .put("user_id", userId)
            .put("provider", novel.provider)
            .put("novel_url", novel.url)
            .put("novel_title", novel.title)
            .put("cover_url", novel.cover ?: JSONObject.NULL)
            .put("body", body.trim())
            .put("tags", JSONArray(normalizeDiscoverTags(tags)))
        if (rating != null) payload.put("rating", rating)
        // author_name is intentionally omitted: the database trigger owns it.
        return request(context, "POST", "/rest/v1/discover_posts", payload, "return=representation") { Unit }
    }

    suspend fun delete(context: Context, postId: String): DiscoverResult<Unit> =
        request(context, "DELETE", "/rest/v1/discover_posts?id=eq.${encode(postId)}") { Unit }

    suspend fun report(context: Context, postId: String): DiscoverResult<Unit> {
        val userId = SupabaseAuth.currentUserId(context)
            ?: return DiscoverResult.Failure(DiscoverError.SignIn)
        val result = request(context, "POST", "/rest/v1/discover_reports", JSONObject()
            .put("post_id", postId).put("user_id", userId)) { Unit }
        return if (result is DiscoverResult.Failure && result.error == DiscoverError.Duplicate) {
            DiscoverResult.Failure(DiscoverError.AlreadyReported)
        } else result
    }

    private suspend fun <T> request(
        context: Context,
        method: String,
        path: String,
        payload: JSONObject? = null,
        prefer: String? = null,
        parse: (String) -> T,
    ): DiscoverResult<T> = withContext(Dispatchers.IO) {
        try {
            val token = SupabaseAuth.getValidAccessToken(context)
                ?: return@withContext DiscoverResult.Failure(
                    if (SupabaseAuth.isLoggedIn(context)) DiscoverError.Offline else DiscoverError.SignIn
                )
            val response = SupabaseAuth.requestJson(method, baseUrl + path, payload, token, prefer)
            if (response.isSuccessful) DiscoverResult.Success(parse(response.body))
            else DiscoverResult.Failure(errorOf(response.code, response.body))
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            DiscoverResult.Failure(DiscoverError.Offline)
        } catch (_: Exception) {
            // Never log or show raw response bodies, credentials or exception messages.
            DiscoverResult.Failure(DiscoverError.Generic)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal fun parseCreatedAt(value: String): Long = try {
        val normalized = value.replace(Regex("\\.(\\d+)")) {
            "." + it.groupValues[1].take(3).padEnd(3, '0')
        }.replace(Regex("Z$"), "+0000")
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        val pattern = if (normalized.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSSZ" else "yyyy-MM-dd'T'HH:mm:ssZ"
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(normalized)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
