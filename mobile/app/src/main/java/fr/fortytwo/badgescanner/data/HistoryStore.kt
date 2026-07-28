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

/** The 3 possible states of a blame. */
enum class BlameStatus(val label: String) {
    /** Just created, nothing decided yet — shown with the "to handle" dot. */
    NOT_HANDLED("À gérer"),
    /** The student was forgiven, no consequence. */
    PARDONED("Pardon"),
    /** The student was TIGed — see [ScanRecord.tigDuration] for how long. */
    TIGED("TIGé"),
}

@Serializable
data class ScanRecord(
    val timestamp: Long,
    /** UID bytes as read by the phone, hex encoded. */
    val uidHex: String,
    /** Badge hex as the readers/script report it (little-endian value). */
    val mifareHex: String,
    /** Wiegand code (the CA candidate that matched, or the W26 code). */
    val wiegand: String,
    val login: String? = null,
    val ftId: String? = null,
    val photoUrl: String? = null,
    val error: String? = null,
    /** "Student", "Piscine" or null (derived from cursus_users). */
    val userType: String? = null,
    val coalitionName: String? = null,
    /** Coalition color as a hex string, e.g. "#4180DB". */
    val coalitionColor: String? = null,
    /** Coalition logo (SVG) shown as the themed background. */
    val coalitionImageUrl: String? = null,
    /** Free-text description of the misdeed, e.g. "Bouteille d'eau", "écran". */
    val reason: String? = null,
    /**
     * Legacy pre-[BlameStatus] field ("sanction applied"). No longer written
     * or read by the app itself — kept only so [HistoryStore]'s one-time
     * migration can map old records to a [BlameStatus].
     */
    val done: Boolean = false,
    /**
     * Whether this is an actual sanction ("blame"), as opposed to a plain
     * badge-scan log entry. Only records created explicitly via "Blâmer" set
     * this — a physical badge tap just logs the scan and opens the user's
     * page, it's no longer a blame by itself.
     */
    val isBlame: Boolean = false,
    val blameStatus: BlameStatus = BlameStatus.NOT_HANDLED,
    /** How long the TIG was, e.g. "2h", "1 semaine". Only set when [blameStatus] is [BlameStatus.TIGED]. */
    val tigDuration: String? = null,
)

/** Simple JSON-file backed history, newest first, capped at [MAX_ENTRIES]. */
class HistoryStore(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.filesDir, "scan_history.json")
    // One-time marker: every record already on disk when this file first
    // appears predates the isBlame/blameStatus split, so it's migrated once.
    private val blameMigrationMarker = File(context.filesDir, "scan_history_blame_migration_done")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()

    private val _history = MutableStateFlow<List<ScanRecord>>(emptyList())
    val history: StateFlow<List<ScanRecord>> = _history

    init {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val loaded = runCatching {
                    json.decodeFromString<List<ScanRecord>>(file.readText())
                }.getOrDefault(emptyList())
                _history.value = if (blameMigrationMarker.exists()) {
                    loaded
                } else {
                    migrateToBlames(loaded)
                }
            }
        }
    }

    /**
     * Every scan predating [ScanRecord.isBlame] was, in effect, a blame
     * (there was no other kind) — convert them once so old history isn't
     * lost from the Users page's blame list. Old `done=true` (a sanction had
     * been applied) becomes [BlameStatus.PARDONED]; `done=false` becomes
     * [BlameStatus.NOT_HANDLED]. Runs exactly once, gated by [blameMigrationMarker].
     */
    private fun migrateToBlames(loaded: List<ScanRecord>): List<ScanRecord> {
        val migrated = loaded.map { r ->
            if (r.isBlame) r else r.copy(
                isBlame = true,
                blameStatus = if (r.done) BlameStatus.PARDONED else BlameStatus.NOT_HANDLED,
            )
        }
        runCatching { file.writeText(json.encodeToString(migrated)) }
        runCatching { blameMigrationMarker.createNewFile() }
        return migrated
    }

    suspend fun add(record: ScanRecord) {
        mutex.withLock {
            val updated = (listOf(record) + _history.value).take(MAX_ENTRIES)
            _history.value = updated
            persist(updated)
        }
    }

    /** Replaces the record with the given timestamp via [transform]. */
    suspend fun update(timestamp: Long, transform: (ScanRecord) -> ScanRecord) {
        mutex.withLock {
            val updated = _history.value.map { if (it.timestamp == timestamp) transform(it) else it }
            _history.value = updated
            persist(updated)
        }
    }

    suspend fun remove(record: ScanRecord) {
        mutex.withLock {
            val updated = _history.value.filterNot { it == record }
            _history.value = updated
            persist(updated)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            _history.value = emptyList()
            persist(emptyList())
        }
    }

    private suspend fun persist(records: List<ScanRecord>) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(records)) }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
