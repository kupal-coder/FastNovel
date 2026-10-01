package com.lagradost.quicknovel.auth

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import com.lagradost.quicknovel.DataStore.removeKey
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_CHAPTER
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_SCROLL
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_SCROLL_CHAR
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Syncs the saved novels of the user (bookmarks, their category and the last read chapter) with
 * the public.library_items table so they survive a reinstall.
 *
 * The library itself stays exactly where the app already keeps it: the RESULT_BOOKMARK /
 * RESULT_BOOKMARK_STATE entries in DataStore and the EPUB_CURRENT_POSITION entry per novel. This
 * object only reads and writes those keys the same way the rest of the app does.
 *
 * Everything here is best effort. A change is queued locally first and uploaded with the next
 * flush; a failed request keeps the queue and is retried on the next trigger. Nothing is logged
 * that could contain a token or an email and no network error ever reaches the user.
 */
object LibrarySync {
    /** Own preferences, so timestamps/queue survive app restarts. */
    private const val PREFS_NAME = "library_sync_meta"

    private const val KEY_USER_ID = "user_id"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_QUEUE = "queue"
    private const val KEY_TS_PREFIX = "ts/"
    private const val KEY_DELETED_PREFIX = "deleted/"
    private const val KEY_DECISION_PREFIX = "decision/"
    private const val DECISION_MERGE = "merge"
    private const val DECISION_SKIP = "skip"

    /** GET /rest/v1/library_items page size. */
    private const val PAGE_SIZE = 1000

    /** POST /rest/v1/library_items batch size. */
    private const val UPLOAD_BATCH = 100

    /** A change is uploaded after this much quiet, or right away when the app goes to background. */
    private const val FLUSH_DEBOUNCE_MS = 5_000L

    /** Scrolling changes the position constantly, only queue an item every few seconds. */
    private const val PROGRESS_MARK_THROTTLE_MS = 3_000L

    /** Safety net so broken pagination can never loop forever. */
    private const val MAX_PULLED_ROWS = 20_000

    private const val BOOKMARK_PREFIX = "$RESULT_BOOKMARK/"

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val debounceLock = Any()
    private var debounceJob: Job? = null

    /** ResultCached.name -> item key of the local library, rebuilt when the library changed. */
    @Volatile
    private var titleIndex: Map<String, String>? = null
    private val titleIndexLock = Any()

    /** novel title -> last time a chapter change was queued, keeps scrolling from spamming prefs. */
    private val progressMarks = ConcurrentHashMap<String, Long>()

    /** Set when the account switch question is needed but no screen is available to show it. */
    @Volatile
    private var pendingPrompt: AccountPrompt? = null

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val summaryFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private data class AccountPrompt(val userId: String, val email: String)

    /** A novel that is in the local library right now. */
    private data class LocalItem(
        val id: Int,
        val key: String,
        val cached: ResultCached,
        val category: ReadType,
    )

    /** A row of public.library_items. */
    private data class RemoteItem(
        val key: String,
        val provider: String,
        val url: String,
        val title: String,
        val cover: String?,
        val category: ReadType,
        val chapterIndex: Int?,
        val deleted: Boolean,
        val updatedAt: Long,
    )

    // ------------------------------------------------------------------ triggers

    /** Full sync (download + merge + upload), used on sign in, app start and "Sync now". */
    fun syncAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                sync(appContext)
            } catch (_: Throwable) {
                // best effort, the next trigger tries again
            }
        }
    }

    /** Uploads what is pending, used for the debounced flush and when the app is backgrounded. */
    fun flushAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                push(appContext)
            } catch (_: Throwable) {
                // best effort, the next trigger tries again
            }
        }
    }

    /** Shows the account switch question as soon as a screen is available again. */
    fun onActivityResumed(activity: Activity) {
        val prompt = pendingPrompt ?: return
        pendingPrompt = null
        showAccountDialog(activity, prompt)
    }

    /** The user signed out: keep the library, forget everything about the sync. */
    fun clear(context: Context) {
        try {
            prefs(context).edit().clear().apply()
            titleIndex = null
            progressMarks.clear()
            pendingPrompt = null
        } catch (_: Throwable) {
            // nothing to clean up
        }
    }

    /** A novel was added to the library or changed category. */
    fun onItemChanged(id: Int) {
        val context = appContext() ?: return
        if (!SupabaseAuth.isLoggedIn(context)) return
        try {
            val cached = context.getKey<ResultCached>(RESULT_BOOKMARK, id.toString()) ?: return
            val key = itemKeyOf(cached)
            removeDuplicateItems(context, id, key)
            markDirty(context, key, deleted = !isInLibrary(context, id))
        } catch (_: Throwable) {
            // best effort
        }
    }

    /** A novel is about to be removed from the library, call before the keys are deleted. */
    fun onItemRemoved(id: Int) {
        val context = appContext() ?: return
        if (!SupabaseAuth.isLoggedIn(context)) return
        try {
            val cached = context.getKey<ResultCached>(RESULT_BOOKMARK, id.toString()) ?: return
            markDirty(context, itemKeyOf(cached), deleted = true)
        } catch (_: Throwable) {
            // best effort
        }
    }

    /** The reader moved to another chapter, [title] is the novel title used by the reader. */
    fun onProgressChanged(title: String) {
        val context = appContext() ?: return
        if (!SupabaseAuth.isLoggedIn(context)) return
        if (title.isBlank()) return
        try {
            val now = System.currentTimeMillis()
            val last = progressMarks[title] ?: 0L
            if (now - last < PROGRESS_MARK_THROTTLE_MS) return
            progressMarks[title] = now
            val key = titleKeyIndex(context)[title] ?: return
            markDirty(context, key, deleted = false)
        } catch (_: Throwable) {
            // best effort
        }
    }

    /** The reader closed, the position is final now. Never throttled. */
    fun onChapterReadChanged(title: String) {
        val context = appContext() ?: return
        if (!SupabaseAuth.isLoggedIn(context)) return
        try {
            val key = titleKeyIndex(context)[title] ?: return
            progressMarks[title] = System.currentTimeMillis()
            markDirty(context, key, deleted = false)
        } catch (_: Throwable) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ status

    /** Epoch millis of the last successful sync, 0 when this device never synced. */
    fun lastSyncedAt(context: Context): Long = try {
        prefs(context).getLong(KEY_LAST_SYNC, 0L)
    } catch (_: Throwable) {
        0L
    }

    /** "Last synced <time>" / "Not synced yet" plus what exactly is synced. */
    fun summary(context: Context): String {
        val lastSynced = lastSyncedAt(context)
        val headline = if (lastSynced <= 0L) {
            context.getString(R.string.library_sync_not_synced)
        } else {
            context.getString(
                R.string.library_sync_last_synced,
                synchronized(summaryFormat) { summaryFormat.format(Date(lastSynced)) }
            )
        }
        return headline + "\n" + context.getString(R.string.library_sync_description)
    }

    // ------------------------------------------------------------------ sync

    /** Downloads the library, merges it with the local one and uploads what is pending. */
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            mutex.withLock {
                if (!SupabaseAuth.isLoggedIn(appContext)) return@withLock false

                val userId = SupabaseAuth.currentUserId(appContext) ?: return@withLock false
                val accessToken = SupabaseAuth.getValidAccessToken(appContext)
                    ?: return@withLock false

                val localItems = localLibrary(appContext)
                val lastUser = prefs(appContext).getString(KEY_USER_ID, null)
                val decision = prefs(appContext).getString(KEY_DECISION_PREFIX + userId, null)

                var allowLocalUpload = true
                val needsDecision = localItems.isNotEmpty() && lastUser != userId
                if (needsDecision) {
                    when (decision) {
                        DECISION_MERGE -> Unit
                        DECISION_SKIP -> allowLocalUpload = false
                        else -> {
                            // Ask once before touching the library of another account.
                            val prompt = AccountPrompt(
                                userId,
                                SupabaseAuth.getEmail(appContext).orEmpty()
                            )
                            val activity = CommonActivity.activity
                            if (activity != null && !activity.isFinishing) {
                                showAccountDialog(activity, prompt)
                            } else {
                                pendingPrompt = prompt
                            }
                            // The answer starts a new sync, for now stay away from the upload.
                            return@withLock false
                        }
                    }
                }

                val remoteItems = fetchRemote(accessToken) ?: return@withLock false

                merge(appContext, localItems, remoteItems, allowLocalUpload)

                // Only remember the account once the question (if any) was answered, so a
                // skipped prompt comes back instead of silently uploading the library.
                if (!needsDecision || decision != null) {
                    prefs(appContext).edit().putString(KEY_USER_ID, userId).apply()
                }

                if (!pushLocked(appContext, accessToken)) return@withLock false

                prefs(appContext).edit()
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .apply()
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Uploads the queued items, see [pushLocked]. */
    suspend fun push(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            mutex.withLock {
                if (!SupabaseAuth.isLoggedIn(appContext)) return@withLock false
                val accessToken = SupabaseAuth.getValidAccessToken(appContext)
                    ?: return@withLock false
                pushLocked(appContext, accessToken)
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ upload

    /**
     * Sends the queued items in batches of [UPLOAD_BATCH]. A batch is only taken out of the queue
     * after the server accepted it, so a failure just means "retry later".
     */
    private suspend fun pushLocked(context: Context, accessToken: String): Boolean {
        val preferences = prefs(context)
        val queue = preferences.getStringSet(KEY_QUEUE, emptySet())?.toList() ?: return true
        if (queue.isEmpty()) return true

        val local = localLibrary(context).associateBy { it.key }

        for (batch in queue.sorted().chunked(UPLOAD_BATCH)) {
            val rows = JSONArray()
            val sent = HashSet<String>()
            for (key in batch) {
                val row = rowFor(context, preferences, key, local[key]) ?: continue
                rows.put(row)
                sent.add(key)
            }
            if (sent.isEmpty()) {
                removeFromQueue(context, batch.toSet())
                continue
            }
            if (!upload(accessToken, rows)) return false
            removeFromQueue(context, sent)
        }
        return true
    }

    private fun rowFor(
        context: Context,
        preferences: SharedPreferences,
        key: String,
        local: LocalItem?,
    ): JSONObject? = try {
        val userId = SupabaseAuth.currentUserId(context) ?: return null
        val updatedAt = preferences.getLong(KEY_TS_PREFIX + key, 0L)
            .takeIf { it > 0L } ?: System.currentTimeMillis()

        val row = JSONObject()
            .put("user_id", userId)
            .put("item_key", key)
            .put("provider", local?.cached?.apiName ?: key.substringBefore('|'))
            .put("novel_url", local?.cached?.source ?: key.substringAfter('|', ""))
            .put("deleted", local == null)
            .put("updated_at", isoTime(updatedAt))

        if (local != null) {
            row.put("title", local.cached.name)
            row.put("cover_url", local.cached.poster ?: JSONObject.NULL)
            row.put("category", local.category.name)
            row.put("last_chapter_index", readChapterIndex(context, local.cached.name) ?: 0)
        }
        row
    } catch (_: JSONException) {
        null
    }

    /** POST /rest/v1/library_items?on_conflict=user_id,item_key, the server merges the rows. */
    private suspend fun upload(accessToken: String, rows: JSONArray): Boolean =
        try {
            val response = SupabaseAuth.requestJson(
                method = "POST",
                endpoint = "$baseUrl/rest/v1/library_items?on_conflict=user_id,item_key",
                body = rows,
                bearerToken = accessToken,
                prefer = "resolution=merge-duplicates,return=minimal"
            )
            response.isSuccessful
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }

    // ------------------------------------------------------------------ download

    /** All rows of the signed in user, paginated. Null when the server could not be reached. */
    private suspend fun fetchRemote(accessToken: String): List<RemoteItem>? {
        val items = ArrayList<RemoteItem>()
        var offset = 0
        while (items.size < MAX_PULLED_ROWS) {
            val response = try {
                SupabaseAuth.requestJson(
                    method = "GET",
                    endpoint = "$baseUrl/rest/v1/library_items" +
                            "?select=*&limit=$PAGE_SIZE&offset=$offset",
                    bearerToken = accessToken
                )
            } catch (_: Exception) {
                return null
            }
            if (!response.isSuccessful) return null

            val page = try {
                JSONArray(response.body)
            } catch (_: JSONException) {
                return null
            }
            if (page.length() == 0) break

            for (index in 0 until page.length()) {
                val row = page.optJSONObject(index) ?: continue
                remoteItemOf(row)?.let { items.add(it) }
            }
            offset += page.length()
        }
        return items
    }

    private fun remoteItemOf(row: JSONObject): RemoteItem? {
        val key = row.optString("item_key").takeIf { it.isNotBlank() } ?: return null
        return RemoteItem(
            key = key,
            provider = row.optString("provider").takeIf { it.isNotBlank() }
                ?: key.substringBefore('|'),
            url = row.optString("novel_url").takeIf { it.isNotBlank() }
                ?: key.substringAfter('|', ""),
            title = if (row.isNull("title")) "" else row.optString("title"),
            cover = if (row.isNull("cover_url")) null else row.optString("cover_url")
                .takeIf { it.isNotBlank() },
            category = readTypeOf(row.opt("category")),
            chapterIndex = if (row.isNull("last_chapter_index")) {
                null
            } else {
                row.optInt("last_chapter_index", -1).takeIf { it >= 0 }
            },
            deleted = row.optBoolean("deleted", false),
            updatedAt = parseTime(row.opt("updated_at"))
        )
    }

    // ------------------------------------------------------------------ merge

    /**
     * Newest updated_at wins per item key:
     *  - remote newer, not deleted: the local item gets the remote category and chapter
     *  - remote newer, deleted: the local item is removed
     *  - local newer: the item is queued for upload
     *  - only local (and no deleted marker): the item is queued for upload
     *  - only remote: the novel is created locally so it opens like any other bookmark
     */
    private fun merge(
        context: Context,
        localItems: List<LocalItem>,
        remoteItems: List<RemoteItem>,
        allowLocalUpload: Boolean,
    ) {
        val remote = remoteItems.associateBy { it.key }
        val localKeys = localItems.mapTo(HashSet()) { it.key }
        val queue = HashSet<String>()
        val synced = HashSet<String>()

        for (item in localItems) {
            val row = remote[item.key]
            val localAt = localUpdatedAt(context, item.key)
            if (row == null) {
                // Only known on this device, "no deleted marker" means it stays and goes up.
                if (allowLocalUpload && !isMarkedDeleted(context, item.key)) queue.add(item.key)
                continue
            }
            when {
                row.updatedAt > localAt -> {
                    applyRemoteItem(context, item, row)
                    synced.add(item.key)
                }

                row.updatedAt < localAt -> queue.add(item.key)
                row.deleted -> queue.add(item.key) // same timestamp, keep the local copy
                else -> synced.add(item.key)
            }
        }

        for (row in remoteItems) {
            if (localKeys.contains(row.key)) continue
            val localAt = localUpdatedAt(context, row.key)
            when {
                // Deleted here while offline, do not resurrect it.
                isMarkedDeleted(context, row.key) && localAt >= row.updatedAt -> {
                    if (allowLocalUpload) queue.add(row.key)
                }

                row.deleted -> {
                    writeMeta(context, row.key, row.updatedAt, deleted = true)
                    synced.add(row.key)
                }

                row.category == ReadType.NONE -> Unit
                else -> {
                    createLocalItem(context, row)
                    synced.add(row.key)
                }
            }
        }

        removeFromQueue(context, synced)
        for (key in queue) markDirty(context, key, deleted = false)
    }

    /** The remote row is newer, apply it to the local library. */
    private fun applyRemoteItem(context: Context, item: LocalItem, row: RemoteItem) {
        if (row.deleted) {
            context.removeKey(RESULT_BOOKMARK, item.id.toString())
            context.removeKey(RESULT_BOOKMARK_STATE, item.id.toString())
            writeMeta(context, row.key, row.updatedAt, deleted = true)
        } else {
            context.setKey(RESULT_BOOKMARK_STATE, item.id.toString(), row.category.prefValue)
            row.chapterIndex?.let { applyPosition(context, item.cached.name, it) }
            writeMeta(context, row.key, row.updatedAt, deleted = false)
        }
        titleIndex = null
        BookDownloader2.bookmarkChanged(item.id)
    }

    /** The row only exists on the server, recreate the novel with the app's own structures. */
    private fun createLocalItem(context: Context, row: RemoteItem) {
        val existingId = findLocalIdByKey(context, row.key)
        val existing = existingId?.let {
            context.getKey<ResultCached>(RESULT_BOOKMARK, it.toString())
        }
        val id = existing?.id ?: existingId
        ?: BookDownloader2Helper.generateId(row.provider, null, row.title)
        val title = existing?.name?.takeIf { it.isNotBlank() } ?: row.title

        if (existing == null) {
            context.setKey(
                RESULT_BOOKMARK, id.toString(), ResultCached(
                    source = row.url,
                    name = row.title,
                    apiName = row.provider,
                    id = id,
                    author = null,
                    poster = row.cover,
                    tags = null,
                    rating = null,
                    totalChapters = 1,
                    cachedTime = System.currentTimeMillis(),
                )
            )
        }
        context.setKey(RESULT_BOOKMARK_STATE, id.toString(), row.category.prefValue)
        row.chapterIndex?.let { applyPosition(context, title, it) }
        writeMeta(context, row.key, row.updatedAt, deleted = false)
        titleIndex = null
        BookDownloader2.bookmarkChanged(id)
    }

    /**
     * Writes the position of another device. The chapter name and the scroll offset belong to the
     * position that is being replaced, so they are dropped to make the index take effect.
     */
    private fun applyPosition(context: Context, title: String, chapterIndex: Int) {
        if (title.isBlank()) return
        context.setKey(EPUB_CURRENT_POSITION, title, chapterIndex)
        context.removeKey(EPUB_CURRENT_POSITION_CHAPTER, title)
        context.removeKey(EPUB_CURRENT_POSITION_SCROLL, title)
        context.removeKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, title)
    }

    /** Removes stale copies of [key], the app can end up with two ids for the same novel. */
    private fun removeDuplicateItems(context: Context, keepId: Int, key: String) {
        for (id in bookmarkIds(context)) {
            if (id == keepId) continue
            val cached = context.getKey<ResultCached>(RESULT_BOOKMARK, id.toString()) ?: continue
            if (itemKeyOf(cached) != key) continue
            context.removeKey(RESULT_BOOKMARK, id.toString())
            context.removeKey(RESULT_BOOKMARK_STATE, id.toString())
            BookDownloader2.bookmarkChanged(id)
        }
    }

    // ------------------------------------------------------------------ local library

    /** Every novel that is in the library right now, NONE entries are not part of it. */
    private fun localLibrary(context: Context): List<LocalItem> {
        val items = ArrayList<LocalItem>()
        for (id in bookmarkIds(context)) {
            val cached = context.getKey<ResultCached>(RESULT_BOOKMARK, id.toString()) ?: continue
            val category = ReadType.fromSpinner(
                context.getKey<Int>(RESULT_BOOKMARK_STATE, id.toString())
            )
            if (category == ReadType.NONE) continue
            items.add(LocalItem(id, itemKeyOf(cached), cached, category))
        }
        return items.distinctBy { it.key }
    }

    private fun bookmarkIds(context: Context): List<Int> =
        context.getKeys(BOOKMARK_PREFIX)
            .mapNotNull { it.removePrefix(BOOKMARK_PREFIX).toIntOrNull() }

    private fun isInLibrary(context: Context, id: Int): Boolean {
        val state = context.getKey<Int>(RESULT_BOOKMARK_STATE, id.toString())
        return ReadType.fromSpinner(state) != ReadType.NONE &&
                context.getKey<ResultCached>(RESULT_BOOKMARK, id.toString()) != null
    }

    private fun findLocalIdByKey(context: Context, key: String): Int? {
        for (id in bookmarkIds(context)) {
            val cached = context.getKey<ResultCached>(RESULT_BOOKMARK, id.toString()) ?: continue
            if (itemKeyOf(cached) == key) return id
        }
        return null
    }

    private fun itemKeyOf(cached: ResultCached): String = "${cached.apiName}|${cached.source}"

    private fun readChapterIndex(context: Context, title: String): Int? =
        context.getKey<Int>(EPUB_CURRENT_POSITION, title)

    // ------------------------------------------------------------------ queue

    private fun markDirty(context: Context, key: String, deleted: Boolean) {
        if (key.isBlank()) return
        val preferences = prefs(context)
        val queue = preferences.getStringSet(KEY_QUEUE, emptySet()) ?: emptySet()
        preferences.edit()
            .putLong(KEY_TS_PREFIX + key, System.currentTimeMillis())
            .putBoolean(KEY_DELETED_PREFIX + key, deleted)
            .putStringSet(KEY_QUEUE, queue + key)
            .apply()
        scheduleFlush(context)
    }

    private fun scheduleFlush(context: Context) {
        val appContext = context.applicationContext
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                try {
                    push(appContext)
                } catch (_: Throwable) {
                    // best effort, the next trigger tries again
                }
            }
        }
    }

    private fun removeFromQueue(context: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        val preferences = prefs(context)
        val queue = preferences.getStringSet(KEY_QUEUE, emptySet()) ?: return
        if (queue.isEmpty()) return
        preferences.edit().putStringSet(KEY_QUEUE, queue - keys).apply()
    }

    private fun localUpdatedAt(context: Context, key: String): Long =
        prefs(context).getLong(KEY_TS_PREFIX + key, 0L)

    private fun isMarkedDeleted(context: Context, key: String): Boolean =
        prefs(context).getBoolean(KEY_DELETED_PREFIX + key, false)

    private fun writeMeta(context: Context, key: String, updatedAt: Long, deleted: Boolean) {
        prefs(context).edit()
            .putLong(KEY_TS_PREFIX + key, updatedAt)
            .putBoolean(KEY_DELETED_PREFIX + key, deleted)
            .apply()
    }

    // ------------------------------------------------------------------ account switch

    /**
     * Another account is signed in and this device has a library of its own. Ask once whether it
     * should be uploaded, the answer starts a new sync.
     */
    private fun showAccountDialog(activity: Activity, prompt: AccountPrompt) {
        val appContext = activity.applicationContext
        activity.runOnUiThread {
            try {
                val email = prompt.email.ifBlank {
                    activity.getString(R.string.library_sync_upload_unknown_account)
                }
                AlertDialog.Builder(activity)
                    .setTitle(R.string.library_sync_upload_title)
                    .setMessage(activity.getString(R.string.library_sync_upload_message, email))
                    .setPositiveButton(R.string.library_sync_upload_merge) { _, _ ->
                        recordDecision(appContext, prompt.userId, merge = true)
                    }
                    .setNegativeButton(R.string.library_sync_upload_skip) { _, _ ->
                        recordDecision(appContext, prompt.userId, merge = false)
                    }
                    .setOnCancelListener {
                        recordDecision(appContext, prompt.userId, merge = false)
                    }
                    .show()
            } catch (_: Throwable) {
                // Ask again on the next screen instead of silently deciding for the user.
                pendingPrompt = prompt
            }
        }
    }

    private fun recordDecision(context: Context, userId: String, merge: Boolean) {
        try {
            prefs(context).edit()
                .putString(
                    KEY_DECISION_PREFIX + userId,
                    if (merge) DECISION_MERGE else DECISION_SKIP
                )
                .apply()
        } catch (_: Throwable) {
            // best effort, the question is asked again
        }
        syncAsync(context)
    }

    // ------------------------------------------------------------------ helpers

    private fun appContext(): Context? = BaseApplication.context?.applicationContext

    private fun titleKeyIndex(context: Context): Map<String, String> {
        titleIndex?.let { return it }
        synchronized(titleIndexLock) {
            titleIndex?.let { return it }
            val index = HashMap<String, String>()
            for (item in localLibrary(context)) {
                if (item.cached.name.isNotBlank()) index[item.cached.name] = item.key
            }
            titleIndex = index
            return index
        }
    }

    private fun readTypeOf(value: Any?): ReadType {
        if (value is Number) return ReadType.fromSpinner(value.toInt())
        val text = (value as? String)?.trim().orEmpty()
        if (text.isNotEmpty()) {
            ReadType.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
                ?.let { return it }
            text.toIntOrNull()?.let { return ReadType.fromSpinner(it) }
        }
        return ReadType.READING
    }

    /** Supabase returns timestamptz as ISO-8601 in UTC, e.g. 2026-10-01T12:34:56.789+00:00. */
    private fun parseTime(value: Any?): Long {
        if (value is Number) return value.toLong()
        if (value !is String || value.isBlank()) return 0L
        return try {
            val normalised = value.trim().replace('T', ' ').take(19)
            synchronized(timeFormat) { timeFormat.parse(normalised)?.time ?: 0L }
        } catch (_: Exception) {
            0L
        }
    }

    private fun isoTime(millis: Long): String =
        synchronized(isoFormat) { isoFormat.format(Date(millis)) }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val baseUrl: String get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
}
