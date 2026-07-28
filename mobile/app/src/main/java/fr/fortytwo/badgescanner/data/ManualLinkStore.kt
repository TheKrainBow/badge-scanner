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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manual badge → login associations, for badges the CA doesn't recognise
 * (no NFC-derived number on the CA). Keyed by the physical badge UID (hex).
 *
 * These links are intentionally forgotten whenever the CA directory is
 * refetched, so they never mask a proper CA record that later appears.
 */
class ManualLinkStore(context: Context, scope: CoroutineScope) {

    private val file = File(context.filesDir, "manual_links.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded = false

    private val _links = MutableStateFlow<Map<String, String>>(emptyMap())
    val links: StateFlow<Map<String, String>> = _links

    init {
        scope.launch(Dispatchers.IO) { ensureLoaded() }
    }

    /** Login manually linked to this badge UID, or null. */
    suspend fun get(uidHex: String): String? {
        ensureLoaded()
        return _links.value[uidHex.uppercase()]
    }

    suspend fun put(uidHex: String, login: String) {
        ensureLoaded()
        mutex.withLock {
            val updated = _links.value + (uidHex.uppercase() to login.trim().lowercase())
            _links.value = updated
            persist(updated)
        }
    }

    /** Forgets every manual link (called on CA directory refetch). */
    suspend fun clear() {
        mutex.withLock {
            _links.value = emptyMap()
            persist(emptyMap())
        }
        loaded = true
    }

    private suspend fun persist(map: Map<String, String>) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(map)) }
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val fromDisk = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrNull()
            }
            if (fromDisk != null) _links.value = fromDisk
            loaded = true
        }
    }
}
