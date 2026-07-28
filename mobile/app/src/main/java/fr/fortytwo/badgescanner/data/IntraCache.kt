package fr.fortytwo.badgescanner.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Cached intra data for one user (login, photo, type, coalition). */
@Serializable
data class IntraInfo(
    val fetchedAt: Long,
    val login: String? = null,
    val ftId: String? = null,
    val photoUrl: String? = null,
    val userType: String? = null,
    val coalitionName: String? = null,
    val coalitionColor: String? = null,
    val coalitionImageUrl: String? = null,
    /** Numeric coalition id, needed to POST /coalitions/{id}/scores. */
    val coalitionId: Int? = null,
    /**
     * The user's `coalitions_user` record id for [coalitionId] (from
     * `GET /users/{login}/coalitions_users`), needed as
     * `score[coalitions_user_id]` when POSTing a score.
     */
    val coalitionsUserId: Int? = null,
    /** Current physical location (e.g. "c1r2s3"), null when offline. */
    val location: String? = null,
    /** Level in the main cursus (21). */
    val level: Double? = null,
    /** Names of projects currently in progress. */
    val currentProjects: List<String> = emptyList(),
    /** True when the last attempt to fetch this user's 42 profile failed. */
    val profileError: Boolean = false,
    /** True when the last attempt to fetch this user's coalition failed. */
    val coalitionError: Boolean = false,
)

/**
 * On-disk cache of intra lookups (coalition, picture, login, type), keyed by
 * the CA identity (ft_id or login). Freshness ([TTL_MS] = 12h) gates whether
 * the intranet is re-queried; the full map is exposed as a [StateFlow] so the
 * Users list can show cached photos/coalitions the moment they're known.
 */
class IntraCache(context: Context, scope: CoroutineScope) {

    private val file = File(context.filesDir, "intra_cache.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded = false

    private val _entries = MutableStateFlow<Map<String, IntraInfo>>(emptyMap())
    /** All cached entries by key (lowercased). Updates on every put. */
    val entries: StateFlow<Map<String, IntraInfo>> = _entries

    init {
        scope.launch(Dispatchers.IO) { ensureLoaded() }
    }

    /** Cached info if present and fresher than 12h, else null (→ refetch). */
    suspend fun getFresh(key: String): IntraInfo? {
        ensureLoaded()
        val info = _entries.value[key.lowercase()] ?: return null
        return if (System.currentTimeMillis() - info.fetchedAt <= TTL_MS) info else null
    }

    /** Cached info regardless of age, for display. Non-suspending. */
    fun peek(key: String): IntraInfo? = _entries.value[key.lowercase()]

    suspend fun put(key: String, info: IntraInfo) {
        ensureLoaded()
        mutex.withLock {
            val updated = _entries.value + (key.lowercase() to info)
            _entries.value = updated
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(updated)) }
            }
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val fromDisk = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString<Map<String, IntraInfo>>(file.readText()) }.getOrNull()
            }
            if (fromDisk != null) _entries.value = fromDisk
            loaded = true
        }
    }

    companion object {
        private const val TTL_MS = 12L * 60 * 60 * 1000
    }
}
