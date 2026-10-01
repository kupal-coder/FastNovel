package com.lagradost.quicknovel.auth

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.quicknovel.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Result of a Supabase authentication call. */
sealed interface AuthResult {
    /**
     * The call succeeded. [needsEmailConfirmation] is true when a sign up went through but the
     * project requires the user to confirm their email before a session is handed out.
     */
    data class Success(val needsEmailConfirmation: Boolean = false) : AuthResult

    /** The call failed, [message] is safe to show to the user. */
    data class Failure(val message: String) : AuthResult
}

/**
 * Minimal Supabase Auth (GoTrue) client built on [HttpURLConnection] + org.json.
 *
 * supabase-kt is deliberately not used because it requires minSdk 26 while this app supports 23.
 * The project url and the anon/publishable key come from BuildConfig, which is filled at build
 * time from local.properties or the SUPABASE_URL / SUPABASE_KEY environment variables.
 */
object SupabaseAuth {
    private const val PREFS_NAME = "supabase_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"

    private const val TIMEOUT_MS = 15_000
    /** Refresh the access token when it expires in less than this many seconds. */
    private const val REFRESH_MARGIN_SECONDS = 60L
    private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L

    private const val ERROR_GENERIC = "Something went wrong"
    private const val ERROR_NETWORK = "No internet connection / can't reach server"
    private const val ERROR_NOT_CONFIGURED = "Login is not configured in this build"

    /** Guards token refreshes so parallel callers don't rotate the refresh token twice. */
    private val refreshMutex = Mutex()

    private val baseUrl: String get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val apiKey: String get() = BuildConfig.SUPABASE_KEY.trim()

    /** True when both the project url and the anon key were available at build time. */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    /**
     * Set when the user picks "Continue without account". Deliberately *not* persisted, so the
     * login screen shows up again the next time the app process starts.
     */
    @Volatile
    var skippedThisSession: Boolean = false
        private set

    fun skipForSession() {
        skippedThisSession = true
    }

    fun isLoggedIn(context: Context): Boolean =
        !prefs(context).getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()

    fun shouldShowLogin(context: Context): Boolean =
        isConfigured && !isLoggedIn(context) && !skippedThisSession

    fun currentEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun currentUserId(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }

    /** POST /auth/v1/token?grant_type=password */
    suspend fun signIn(context: Context, email: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext AuthResult.Failure(ERROR_NOT_CONFIGURED)

            val payload = JSONObject()
                .put("email", email)
                .put("password", password)

            try {
                val response = postJson("$baseUrl/auth/v1/token?grant_type=password", payload)
                if (!response.isSuccessful) {
                    return@withContext AuthResult.Failure(errorMessageOf(response.body))
                }
                val json = JSONObject(response.body)
                if (json.optString("access_token").isBlank()) {
                    return@withContext AuthResult.Failure(ERROR_GENERIC)
                }
                saveSession(context, json)
                AuthResult.Success()
            } catch (_: IOException) {
                AuthResult.Failure(ERROR_NETWORK)
            } catch (_: JSONException) {
                AuthResult.Failure(ERROR_GENERIC)
            }
        }

    /**
     * POST /auth/v1/signup
     *
     * When "Confirm email" is turned off the response already contains a session, otherwise the
     * user has to confirm their email first and [AuthResult.Success.needsEmailConfirmation] is set.
     */
    suspend fun signUp(context: Context, email: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext AuthResult.Failure(ERROR_NOT_CONFIGURED)

            val payload = JSONObject()
                .put("email", email)
                .put("password", password)

            try {
                val response = postJson("$baseUrl/auth/v1/signup", payload)
                if (!response.isSuccessful) {
                    return@withContext AuthResult.Failure(errorMessageOf(response.body))
                }
                val json = JSONObject(response.body)
                if (json.optString("access_token").isNotBlank()) {
                    saveSession(context, json)
                    AuthResult.Success()
                } else {
                    AuthResult.Success(needsEmailConfirmation = true)
                }
            } catch (_: IOException) {
                AuthResult.Failure(ERROR_NETWORK)
            } catch (_: JSONException) {
                AuthResult.Failure(ERROR_GENERIC)
            }
        }

    /**
     * Returns a usable access token, refreshing it via POST /auth/v1/token?grant_type=refresh_token
     * when it is about to expire. Returns null when there is no session or the refresh failed.
     * A 400/401 means the refresh token is dead, so the local session is cleared.
     */
    suspend fun getValidAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        refreshMutex.withLock {
            val preferences = prefs(context)
            val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
            if (refreshToken.isNullOrBlank()) return@withLock null

            val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)
            val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
            if (!accessToken.isNullOrBlank() && expiresAt - nowSeconds() > REFRESH_MARGIN_SECONDS) {
                return@withLock accessToken
            }

            try {
                val response = postJson(
                    "$baseUrl/auth/v1/token?grant_type=refresh_token",
                    JSONObject().put("refresh_token", refreshToken)
                )
                when {
                    response.isSuccessful -> {
                        val json = JSONObject(response.body)
                        val newAccessToken = json.optString("access_token")
                        if (newAccessToken.isBlank()) {
                            null
                        } else {
                            saveSession(context, json)
                            newAccessToken
                        }
                    }

                    response.code == HttpURLConnection.HTTP_BAD_REQUEST ||
                            response.code == HttpURLConnection.HTTP_UNAUTHORIZED -> {
                        clearSession(context)
                        null
                    }

                    else -> null
                }
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
    }

    /** Best effort POST /auth/v1/logout, the local session is cleared either way. */
    suspend fun signOut(context: Context) {
        withContext(Dispatchers.IO) {
            val accessToken = prefs(context).getString(KEY_ACCESS_TOKEN, null)
            if (isConfigured && !accessToken.isNullOrBlank()) {
                try {
                    postJson("$baseUrl/auth/v1/logout", JSONObject(), bearerToken = accessToken)
                } catch (_: IOException) {
                    // ignored, the session is dropped locally anyway
                } catch (_: JSONException) {
                    // ignored, the session is dropped locally anyway
                }
            }
            clearSession(context)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveSession(context: Context, json: JSONObject) {
        val expiresIn = json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
        val expiresAt = json.optLong("expires_at", nowSeconds() + expiresIn)
        val user = json.optJSONObject("user")

        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, json.optString("access_token"))
            .putString(KEY_REFRESH_TOKEN, json.optString("refresh_token"))
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_USER_ID, user?.optString("id") ?: "")
            .putString(KEY_EMAIL, user?.optString("email") ?: "")
            .apply()
    }

    private fun clearSession(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private class Response(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private fun postJson(
        endpoint: String,
        payload: JSONObject,
        bearerToken: String? = null
    ): Response {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.setRequestProperty("apikey", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (bearerToken != null) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream: InputStream? =
                if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }
            return Response(code, body ?: "")
        } finally {
            connection.disconnect()
        }
    }

    /** GoTrue is not consistent about the error field, so try all of them. */
    private fun errorMessageOf(body: String): String {
        return try {
            val json = JSONObject(body)
            listOf("error_description", "msg", "message")
                .firstNotNullOfOrNull { field -> json.optString(field).takeIf { it.isNotBlank() } }
                ?: ERROR_GENERIC
        } catch (_: JSONException) {
            ERROR_GENERIC
        }
    }
}
