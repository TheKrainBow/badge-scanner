package fr.fortytwo.badgescanner.api

import android.content.Context
import fr.fortytwo.badgescanner.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Badge -> user resolution over the CA user listing, cached on disk.
 *
 * The CA is very slow, so the full listing is only fetched on demand
 * ("Refetch CA users" in Settings) — never automatically. Scans only hit
 * the cache; the per-user freshness check is a single `GET /users/{pk}/`
 * done by the scan flow.
 */
class CaDirectory(context: Context, private val api: CaApi) {

    @Serializable
    private data class CacheFile(val fetchedAt: Long = 0, val entries: List<CaDirEntry> = emptyList())

    data class Match(val entry: CaDirEntry, val badge: Long)
    data class Info(val userCount: Int, val fetchedAt: Long)
    data class Progress(val running: Boolean, val fetched: Int, val total: Int)

    private val file = File(context.filesDir, "ca_directory.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded = false

    private val _info = MutableStateFlow(Info(0, 0))
    val info: StateFlow<Info> = _info

    private val _progress = MutableStateFlow(Progress(false, 0, 0))
    val progress: StateFlow<Progress> = _progress

    /** The kept (resolvable) users, for the Users page. */
    private val _entries = MutableStateFlow<List<CaDirEntry>>(emptyList())
    val entries: StateFlow<List<CaDirEntry>> = _entries

    suspend fun preload() = mutex.withLock { ensureLoaded() }

    /** Cache-only lookup — never triggers a network call. */
    suspend fun findByBadge(candidates: List<String>): Match? = mutex.withLock {
        ensureLoaded()
        for (candidate in candidates.mapNotNull { it.toLongOrNull() }) {
            _entries.value.firstOrNull { candidate in it.badges }?.let { return Match(it, candidate) }
        }
        null
    }

    /** Full refetch of the CA user listing. Slow — only call on user request. */
    suspend fun refresh(settings: AppSettings): Int {
        val previous = _info.value
        _progress.value = Progress(running = true, fetched = 0, total = -1)
        var succeeded = false
        try {
            val fresh = api.fetchAllUsers(settings, pageSize = 30) { fetched, total ->
                // Update the visible "users cached" count page per page
                _progress.value = Progress(running = true, fetched = fetched, total = total)
                _info.value = _info.value.copy(userCount = fetched)
            }
            // Keep only users we can resolve on the intra (ft_login/ft_id or
            // a login embedded in the CA name) — filtered at refetch time
            val kept = fresh.filter { it.isListable }
            succeeded = true
            mutex.withLock {
                _entries.value = kept
                loaded = true
                val fetchedAt = System.currentTimeMillis()
                _info.value = Info(kept.size, fetchedAt)
                withContext(Dispatchers.IO) {
                    runCatching { file.writeText(json.encodeToString(CacheFile(fetchedAt, kept))) }
                }
            }
            return kept.size
        } finally {
            // A partial fetch left the count showing the incomplete number;
            // restore the last good value unless we replaced it wholesale
            if (!succeeded) _info.value = previous
            _progress.value = _progress.value.copy(running = false)
        }
    }

    /**
     * Removes one entry from the local cache only — does not touch the CA
     * itself. Temporary: a later "Refetch CA users" will bring the entry
     * back if it's still listed on the CA.
     */
    suspend fun remove(pk: Long) = mutex.withLock {
        ensureLoaded()
        val updated = _entries.value.filterNot { it.pk == pk }
        _entries.value = updated
        val fetchedAt = _info.value.fetchedAt
        _info.value = Info(updated.size, fetchedAt)
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(CacheFile(fetchedAt, updated))) }
        }
    }

    // Must be called while holding [mutex]
    private suspend fun ensureLoaded() {
        if (loaded) return
        val cache = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<CacheFile>(file.readText()) }.getOrNull()
        }
        // Older caches may predate filtering — filter on load too
        val kept = (cache?.entries ?: emptyList()).filter { it.isListable }
        _entries.value = kept
        _info.value = Info(kept.size, cache?.fetchedAt ?: 0)
        loaded = true
    }
}
