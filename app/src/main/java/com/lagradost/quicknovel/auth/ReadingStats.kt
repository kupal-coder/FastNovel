package com.lagradost.quicknovel.auth

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import com.lagradost.quicknovel.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the reading progress that has not been uploaded to Supabase yet.
 *
 * The database only ever receives increments through public.add_reading_stats(...), never absolute
 * totals, so a failed upload can simply be retried. Everything here is best effort: network errors
 * keep the pending values around for the next flush and never reach the user.
 *
 * Counts are only recorded while a user is signed in, so reading done as a guest can never end up
 * on whichever account signs in later.
 */
object ReadingStats {
    private const val PREFS_NAME = "reading_stats_pending"
    private const val KEY_CHAPTERS = "chapters"
    private const val KEY_NOVELS = "novels"
    private const val KEY_SECONDS = "seconds"
    private const val KEY_FINISHED_NOVELS = "finished_novels"

    /** A single reader session longer than this is not a reading session, ignore it. */
    private const val MAX_SESSION_SECONDS = 3 * 60 * 60L

    /** Caps of public.add_reading_stats, a larger backlog is spread over several flushes. */
    private const val MAX_CHAPTERS_PER_CALL = 200
    private const val MAX_NOVELS_PER_CALL = 20
    private const val MAX_SECONDS_PER_CALL = 86_400L

    private const val TIMEOUT_MS = 15_000

    /** Guards the pending counters against concurrent reader/viewmodel threads. */
    private val lock = Any()

    /** Makes sure two flushes never send (and subtract) the same increments twice. */
    private val flushMutex = Mutex()

    /** Used for fire-and-forget flushes, see [flushAsync]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Start of the current reader session, 0 when the reader is not in the foreground. */
    private var sessionStartedAtMs = 0L

    /** Flushes without suspending, for lifecycle callbacks. Never throws. */
    fun flushAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                flush(appContext)
            } catch (_: Throwable) {
                // best effort, the pending values are kept for the next flush
            }
        }
    }

    /**
     * Counts a chapter. Call this only on the first transition from unread to read, so re-reads
     * and chapters that were already read do not count twice.
     */
    fun recordChapterRead(context: Context) {
        val appContext = context.applicationContext
        if (!SupabaseAuth.isLoggedIn(appContext)) return
        synchronized(lock) { addPending(appContext, chapters = 1, novels = 0, seconds = 0L) }
    }

    /** Counts a novel once, when its final chapter becomes read. */
    fun recordNovelFinished(context: Context, novelKey: String) {
        val appContext = context.applicationContext
        if (!SupabaseAuth.isLoggedIn(appContext)) return
        if (novelKey.isBlank()) return

        synchronized(lock) {
            val preferences = prefs(appContext)
            val finished = preferences.getStringSet(KEY_FINISHED_NOVELS, emptySet()) ?: emptySet()
            if (finished.contains(novelKey)) return
            preferences.edit().putStringSet(KEY_FINISHED_NOVELS, finished + novelKey).apply()
            addPending(appContext, chapters = 0, novels = 1, seconds = 0L)
        }
    }

    /** Reader screen entered the foreground, only counts while the screen is on. */
    fun onReaderResumed(context: Context) {
        if (!isScreenOn(context.applicationContext)) {
            sessionStartedAtMs = 0L
            return
        }
        sessionStartedAtMs = SystemClock.elapsedRealtime()
    }

    /** Reader screen left the foreground, add the elapsed time to the pending seconds. */
    fun onReaderPaused(context: Context) {
        val appContext = context.applicationContext
        val startedAt = sessionStartedAtMs
        sessionStartedAtMs = 0L
        if (startedAt == 0L) return

        val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L
        if (elapsedSeconds <= 0L || elapsedSeconds > MAX_SESSION_SECONDS) return

        if (!SupabaseAuth.isLoggedIn(appContext)) return
        synchronized(lock) { addPending(appContext, chapters = 0, novels = 0, seconds = elapsedSeconds) }
    }

    /**
     * Uploads the pending increments, keeping them when the upload fails.
     *
     * On success only the values that were just sent are subtracted, so anything that was recorded
     * while the request was in flight stays pending.
     */
    suspend fun flush(context: Context) {
        val appContext = context.applicationContext
        if (!SupabaseAuth.isLoggedIn(appContext)) return

        flushMutex.withLock {
            val pending = synchronized(lock) { readPending(appContext) }
            if (pending.isEmpty()) return

            val accessToken = SupabaseAuth.getValidAccessToken(appContext) ?: return

            // Stay inside the caps of the rpc, whatever is left over is sent by the next flush.
            val chapters = minOf(pending.chapters, MAX_CHAPTERS_PER_CALL)
            val novels = minOf(pending.novels, MAX_NOVELS_PER_CALL)
            val seconds = minOf(pending.seconds, MAX_SECONDS_PER_CALL)

            if (!postIncrements(accessToken, chapters, novels, seconds)) return
            synchronized(lock) { subtractPending(appContext, chapters, novels, seconds) }
        }
    }

    /** Drops the pending increments and forgets which novels were already counted. */
    fun clear(context: Context) {
        prefs(context.applicationContext).edit().clear().apply()
    }

    /** POST /rest/v1/rpc/add_reading_stats, true when the server accepted the increments. */
    private suspend fun postIncrements(
        accessToken: String,
        chapters: Int,
        novels: Int,
        seconds: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("p_chapters", chapters)
                .put("p_novels", novels)
                .put("p_seconds", seconds)

            val url = URL("$baseUrl/rest/v1/rpc/add_reading_stats")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.doInput = true
                connection.useCaches = false
                connection.setRequestProperty("apikey", apiKey)
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")

                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private data class Pending(val chapters: Int, val novels: Int, val seconds: Long) {
        fun isEmpty(): Boolean = chapters <= 0 && novels <= 0 && seconds <= 0L
    }

    private val baseUrl: String get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val apiKey: String get() = BuildConfig.SUPABASE_KEY.trim()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readPending(context: Context): Pending {
        val preferences = prefs(context)
        return Pending(
            chapters = preferences.getInt(KEY_CHAPTERS, 0),
            novels = preferences.getInt(KEY_NOVELS, 0),
            seconds = preferences.getLong(KEY_SECONDS, 0L)
        )
    }

    private fun addPending(context: Context, chapters: Int, novels: Int, seconds: Long) {
        val preferences = prefs(context)
        preferences.edit()
            .putInt(KEY_CHAPTERS, preferences.getInt(KEY_CHAPTERS, 0) + chapters)
            .putInt(KEY_NOVELS, preferences.getInt(KEY_NOVELS, 0) + novels)
            .putLong(KEY_SECONDS, preferences.getLong(KEY_SECONDS, 0L) + seconds)
            .apply()
    }

    private fun subtractPending(context: Context, chapters: Int, novels: Int, seconds: Long) {
        val preferences = prefs(context)
        preferences.edit()
            .putInt(KEY_CHAPTERS, (preferences.getInt(KEY_CHAPTERS, 0) - chapters).coerceAtLeast(0))
            .putInt(KEY_NOVELS, (preferences.getInt(KEY_NOVELS, 0) - novels).coerceAtLeast(0))
            .putLong(
                KEY_SECONDS,
                (preferences.getLong(KEY_SECONDS, 0L) - seconds).coerceAtLeast(0L)
            )
            .apply()
    }

    private fun isScreenOn(context: Context): Boolean {
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isInteractive
    }
}
