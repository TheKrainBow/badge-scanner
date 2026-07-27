package fr.fortytwo.badgescanner.scan

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.fortytwo.badgescanner.api.CaApi
import fr.fortytwo.badgescanner.api.CaDirEntry
import fr.fortytwo.badgescanner.api.CaDirectory
import fr.fortytwo.badgescanner.api.CaTrust
import fr.fortytwo.badgescanner.api.ClusterInfo
import fr.fortytwo.badgescanner.api.ClusterLayout
import fr.fortytwo.badgescanner.api.Coalition
import fr.fortytwo.badgescanner.api.IntraApi
import fr.fortytwo.badgescanner.api.parseClusterSvg
import fr.fortytwo.badgescanner.data.IntraCache
import fr.fortytwo.badgescanner.data.IntraInfo
import fr.fortytwo.badgescanner.data.ManualLinkStore
import fr.fortytwo.badgescanner.data.AppSettings
import fr.fortytwo.badgescanner.data.BlameStatus
import fr.fortytwo.badgescanner.data.HistoryStore
import fr.fortytwo.badgescanner.data.ScanRecord
import fr.fortytwo.badgescanner.data.SettingsRepository
import fr.fortytwo.badgescanner.nfc.BadgeCodes
import fr.fortytwo.badgescanner.nfc.Wiegand
import fr.fortytwo.badgescanner.nfc.toHex
import fr.fortytwo.badgescanner.util.piscineLoginFromName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface ScanState {
    data object Idle : ScanState
    data class Working(val codes: BadgeCodes, val step: String) : ScanState
    data class Success(val codes: BadgeCodes, val record: ScanRecord) : ScanState
    data class Failure(val codes: BadgeCodes, val record: ScanRecord) : ScanState
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val historyStore = HistoryStore(application, viewModelScope)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // The CA is slow: give its requests a much longer read timeout
    private val caApi = CaApi(
        CaTrust.pinnedClient(
            application,
            http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build(),
        )
    )
    private val caDirectory = CaDirectory(application, caApi)
    private val intraApi = IntraApi(http)
    private val intraCache = IntraCache(application, viewModelScope)
    private val manualLinks = ManualLinkStore(application, viewModelScope)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val history: StateFlow<List<ScanRecord>> = historyStore.history

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    val directoryInfo: StateFlow<CaDirectory.Info> = caDirectory.info
    val directoryProgress: StateFlow<CaDirectory.Progress> = caDirectory.progress

    private val _directoryMessage = MutableStateFlow<String?>(null)
    val directoryMessage: StateFlow<String?> = _directoryMessage

    data class IntraRefetchProgress(
        val running: Boolean = false,
        val fetched: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val currentLogin: String? = null,
    )

    private val _profileProgress = MutableStateFlow(IntraRefetchProgress())
    val profileProgress: StateFlow<IntraRefetchProgress> = _profileProgress

    private val _profileMessage = MutableStateFlow<String?>(null)
    val profileMessage: StateFlow<String?> = _profileMessage

    private val _coalitionProgress = MutableStateFlow(IntraRefetchProgress())
    val coalitionProgress: StateFlow<IntraRefetchProgress> = _coalitionProgress

    private val _coalitionFetchMessage = MutableStateFlow<String?>(null)
    val coalitionFetchMessage: StateFlow<String?> = _coalitionFetchMessage

    init {
        viewModelScope.launch { caDirectory.preload() }
    }

    private var lastUidHex: String? = null
    private var lastScanAt: Long = 0

    /** Called from the NFC reader callback (binder thread). */
    fun onTag(uid: ByteArray) {
        val hex = uid.toHex()
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            // The reader fires repeatedly while the badge stays on the phone
            if (hex == lastUidHex && now - lastScanAt < 2_500) return
            lastUidHex = hex
            lastScanAt = now
        }
        val codes = Wiegand.fromUid(uid)
        viewModelScope.launch { process(codes) }
    }

    private suspend fun process(codes: BadgeCodes) {
        val s = settings.value
        var login: String? = null
        var ftId: String? = null
        var photoUrl: String? = null
        var matchedBadgeId: String? = null
        var matchedEntry: CaDirEntry? = null
        var error: String? = null
        var userType: String? = null
        var coalitionName: String? = null
        var coalitionColor: String? = null
        var coalitionImageUrl: String? = null

        _scanState.value = ScanState.Working(codes, "Looking up badge on the CA…")
        if (!s.caConfigured) {
            error = "CA credentials are not configured (see Settings)"
        } else {
            try {
                val match = caDirectory.findByBadge(codes.caCandidates)
                val manualLogin = if (match == null) manualLinks.get(codes.uidHex) else null
                if (match == null && manualLogin != null) {
                    // Badge manually linked to a student previously — open
                    // that student's own CA page directly, same as a real
                    // badge match, so this badge purely acts as a lookup key.
                    login = manualLogin
                    matchedEntry = caDirectory.entries.value.firstOrNull {
                        it.displayLogin?.equals(manualLogin, ignoreCase = true) == true
                    }
                } else if (match == null) {
                    error = "Badge not in CA directory (tried ${codes.caCandidates.joinToString(", ")}; " +
                        "${directoryInfo.value.userCount} users cached). " +
                        "If this badge is new, use “Refetch CA users” in Settings, " +
                        "or associate it to a student below."
                } else {
                    matchedBadgeId = match.badge.toString()
                    matchedEntry = match.entry
                    // Cache-only: no live CA refetch here anymore (it was
                    // the CA's slow GET /users/{pk}/, on every single scan).
                    login = match.entry.ftLogin.takeIf { it.isNotBlank() }
                    ftId = match.entry.ftId.takeIf { it.isNotBlank() }
                    // Piscine badges have no ft_login/ft_id; their CA name is
                    // like "[PISCINE] 249 lgauvrea" — use the trailing login
                    if (login == null && ftId == null) {
                        login = piscineLoginFromName(match.entry.fullName)
                        if (login != null) {
                            Log.i("BadgeScanner", "Piscine badge: derived login '$login' from '${match.entry.fullName}'")
                        }
                    }
                    if (login == null && ftId == null) {
                        error = "CA user ${match.entry.fullName.ifBlank { match.entry.pk.toString() }} " +
                            "has no ft_login and no ft_id"
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "CA request failed"
            }
        }

        if (error == null) {
            if (!s.ftConfigured) {
                if (login == null) {
                    error = "42 API credentials are not configured (see Settings)"
                }
                // else: keep the CA login, just without photo/confirmation
            } else if (matchedEntry != null) {
                // Known CA user — the common case. Never block the badge-to-
                // profile transition on a live intra fetch here (that's what
                // made every scan feel slow, even for people already
                // resolved): just peek whatever's cached, for free, to
                // enrich this history record's snapshot. openUser() below
                // shows the live page instantly from cache and refreshes
                // stale profile/coalition info in the background on its own.
                val cacheKey = ftId?.takeIf { it.isNotBlank() } ?: login?.takeIf { it.isNotBlank() }
                cacheKey?.let { intraCache.peek(it) }?.let { cached ->
                    login = cached.login ?: login
                    ftId = cached.ftId ?: ftId
                    photoUrl = cached.photoUrl
                    userType = cached.userType
                    coalitionName = cached.coalitionName
                    coalitionColor = cached.coalitionColor
                    coalitionImageUrl = cached.coalitionImageUrl
                }
            } else {
                // No CA page to open for this login (a manually-linked badge
                // whose student isn't in the local CA cache) — this is the
                // only path left showing the old full-screen result card
                // instead of a live page with its own background refresh, so
                // it still needs to actually wait for real data.
                try {
                    val info = resolveIntra(s, login, ftId) { step ->
                        _scanState.value = ScanState.Working(codes, step)
                    }
                    if (info != null) {
                        login = info.login ?: login
                        ftId = info.ftId ?: ftId
                        photoUrl = info.photoUrl
                        userType = info.userType
                        coalitionName = info.coalitionName
                        coalitionColor = info.coalitionColor
                        coalitionImageUrl = info.coalitionImageUrl
                    }
                } catch (e: Exception) {
                    if (login == null) error = e.message ?: "Intranet request failed"
                }
            }
        }

        val record = ScanRecord(
            timestamp = System.currentTimeMillis(),
            uidHex = codes.uidHex,
            mifareHex = codes.mifareHex,
            wiegand = matchedBadgeId ?: codes.wiegand26,
            login = login,
            ftId = ftId,
            photoUrl = photoUrl,
            error = error,
            userType = userType,
            coalitionName = coalitionName,
            coalitionColor = coalitionColor,
            coalitionImageUrl = coalitionImageUrl,
        )
        historyStore.add(record)
        if (error == null && login != null && matchedEntry != null) {
            // A badge scan just identifies the user and opens their page —
            // it's no longer a blame by itself; that's a separate action
            // from there ("Blâmer").
            _scanState.value = ScanState.Idle
            openUser(matchedEntry)
        } else if (error == null && login != null) {
            // No CA entry to open a page for (e.g. a manually-linked badge)
            // — fall back to the old result card.
            _scanState.value = ScanState.Success(codes, record)
        } else {
            _scanState.value = ScanState.Failure(codes, record)
        }
    }

    /**
     * Resolves intra info (login, photo, type, coalition) for a user, using
     * the 12h disk cache and falling back to a fresh fetch + cache. Returns
     * null when there's nothing to look up; throws on a network/parse error.
     */
    private suspend fun resolveIntra(
        s: AppSettings,
        login: String?,
        ftId: String?,
        onStep: (String) -> Unit = {},
    ): IntraInfo? {
        val cacheKey = ftId?.takeIf { it.isNotBlank() } ?: login?.takeIf { it.isNotBlank() }
        if (cacheKey == null) return null

        intraCache.getFresh(cacheKey)?.let {
            onStep("Loading cached intra info…")
            return it
        }

        onStep("Fetching user on the intranet…")
        val info = fetchIntraInfo(s, login, ftId) { onStep("Fetching coalition…") }
        intraCache.put(cacheKey, info)
        return info
    }

    /** Fetches a user's profile + coalition from the 42 API. No cache read/write. */
    private suspend fun fetchIntraInfo(
        s: AppSettings,
        login: String?,
        ftId: String?,
        onFetchingCoalition: () -> Unit = {},
    ): IntraInfo {
        val userInfo = fetchIntraUserOnly(s, login, ftId)
        val coalition = userInfo.login?.let { lg ->
            onFetchingCoalition()
            // Best-effort here: a transient coalition-fetch error shouldn't
            // fail the whole scan/open — the user still gets login/photo.
            runCatching { fetchCoalitionOnly(s, lg) }.getOrNull()
        }
        // Also best-effort, for the same reason: it's only needed later, to
        // POST a score, and shouldn't block showing the user otherwise.
        val coalitionsUserId = if (userInfo.login != null && coalition != null) {
            runCatching { fetchCoalitionsUserId(s, userInfo.login, coalition.id) }.getOrNull()
        } else {
            null
        }
        return userInfo.copy(
            coalitionName = coalition?.name,
            coalitionColor = coalition?.color,
            coalitionImageUrl = coalition?.imageUrl,
            coalitionId = coalition?.id,
            coalitionsUserId = coalitionsUserId,
        )
    }

    /** Fetches just the profile (login, ft_id, photo, type, location, level, projects) — no coalition, no cache I/O. */
    private suspend fun fetchIntraUserOnly(s: AppSettings, login: String?, ftId: String?): IntraInfo {
        val intraUser = intraApi.fetchUser(s, ftId, login)
        return IntraInfo(
            fetchedAt = System.currentTimeMillis(),
            login = intraUser.login.takeIf { it.isNotBlank() } ?: login,
            ftId = intraUser.id.takeIf { it.isNotBlank() } ?: ftId,
            photoUrl = intraUser.imageUrl,
            userType = userTypeFromCursus(intraUser.cursusIds),
            location = intraUser.location,
            level = intraUser.level,
            currentProjects = intraUser.currentProjects,
        )
    }

    /**
     * Fetches just the picked coalition for [login] — no cache I/O. Throws
     * on a real request failure (so callers that need to distinguish "the
     * fetch failed" from "this user genuinely has zero coalitions" can);
     * returns null only when the request succeeded with no coalitions.
     */
    private suspend fun fetchCoalitionOnly(s: AppSettings, login: String): Coalition? =
        intraApi.fetchCoalitions(s, login).let(::pickCoalition)

    /**
     * Fetches [login]'s `coalitions_users` and returns the id of the record
     * matching [coalitionId] — the value `POST .../scores` wants as
     * `score[coalitions_user_id]`. No cache I/O. Throws on a real request
     * failure, same contract as [fetchCoalitionOnly].
     */
    private suspend fun fetchCoalitionsUserId(s: AppSettings, login: String, coalitionId: Int): Int? =
        intraApi.fetchCoalitionsUsers(s, login).firstOrNull { it.coalitionId == coalitionId }?.id

    fun saveSettings(s: AppSettings) {
        viewModelScope.launch { settingsRepository.save(s) }
    }

    fun refetchCaDirectory() {
        if (directoryProgress.value.running) return
        viewModelScope.launch {
            _directoryMessage.value = null
            try {
                val count = caDirectory.refresh(settings.value)
                // A fresh directory supersedes every hand-made badge link
                manualLinks.clear()
                _directoryMessage.value = "Fetched $count users from the CA"
            } catch (e: Exception) {
                _directoryMessage.value = e.message ?: "CA directory refresh failed"
            }
        }
    }

    /** True while either the profile or the coalition bulk fetch is running. */
    private fun anyIntraRefetchRunning() =
        _profileProgress.value.running || _coalitionProgress.value.running || directoryProgress.value.running

    /**
     * Bulk-fetches 42 profile (login, ft_id, photo, type, location, level,
     * projects — everything except coalition) for every user in the
     * (already cached) CA directory who isn't already freshly cached. Slow —
     * only call on user request. A user's existing cached coalition, if any,
     * is preserved untouched.
     */
    fun refetchUserProfiles() {
        if (anyIntraRefetchRunning()) return
        viewModelScope.launch {
            _profileMessage.value = null
            val s = settings.value
            // One entry per distinct person: dedupe by intraKey so two
            // badges for the same login only cost one 42 API round trip.
            val targets = caDirectory.entries.value
                .mapNotNull { e -> e.intraKey?.let { key -> key to e } }
                .distinctBy { it.first.lowercase() }
            _profileProgress.value = IntraRefetchProgress(running = true, total = targets.size)
            var failed = 0
            var skipped = 0
            for ((index, target) in targets.withIndex()) {
                val (key, entry) = target
                if (intraCache.getFresh(key) != null) {
                    skipped++
                } else {
                    _profileProgress.value = _profileProgress.value.copy(currentLogin = entry.displayLogin ?: key)
                    val cached = intraCache.peek(key)
                    runCatching {
                        val fresh = fetchIntraUserOnly(s, entry.displayLogin, entry.ftId.ifBlank { null })
                        // Keep whatever coalition is already cached — this
                        // pass only refreshes the profile fields.
                        intraCache.put(
                            key,
                            fresh.copy(
                                coalitionName = cached?.coalitionName,
                                coalitionColor = cached?.coalitionColor,
                                coalitionImageUrl = cached?.coalitionImageUrl,
                                coalitionId = cached?.coalitionId,
                                coalitionsUserId = cached?.coalitionsUserId,
                                coalitionError = cached?.coalitionError ?: false,
                                profileError = false,
                            ),
                        )
                    }.onFailure {
                        failed++
                        // Record the failure so the "Error" filter on the
                        // Users page can surface users who never resolved —
                        // preserves whatever was already cached, if anything.
                        val base = cached ?: IntraInfo(fetchedAt = 0, login = entry.displayLogin)
                        intraCache.put(key, base.copy(profileError = true))
                    }
                    delay(350) // stay well under the 42 API's ~2 req/s limit
                }
                _profileProgress.value = _profileProgress.value.copy(fetched = index + 1, failed = failed, skipped = skipped)
            }
            _profileProgress.value = _profileProgress.value.copy(running = false, currentLogin = null)
            val newlyFetched = targets.size - skipped - failed
            _profileMessage.value = if (failed == 0) {
                "Fetched $newlyFetched new profiles ($skipped already cached)"
            } else {
                "Fetched $newlyFetched new profiles ($skipped already cached, $failed failed)"
            }
        }
    }

    /**
     * Bulk-fetches coalitions for every CA user who already has a resolved
     * login (from a previous "Fetch 42 users info" run) but no fresh cached
     * coalition yet. Independent of the profile fetch, so a failed/rate-
     * limited coalition run — the most likely source of "no coalition" on a
     * piscine student — can be retried on its own without re-fetching every
     * profile too.
     */
    fun refetchCoalitions() {
        if (anyIntraRefetchRunning()) return
        viewModelScope.launch {
            _coalitionFetchMessage.value = null
            val s = settings.value
            val targets = caDirectory.entries.value
                .mapNotNull { e -> e.intraKey?.let { key -> key to e } }
                .distinctBy { it.first.lowercase() }
                .mapNotNull { (key, entry) ->
                    val login = intraCache.peek(key)?.login ?: entry.displayLogin
                    login?.let { Triple(key, entry, it) }
                }
            _coalitionProgress.value = IntraRefetchProgress(running = true, total = targets.size)
            var failed = 0
            var skipped = 0
            for ((index, target) in targets.withIndex()) {
                val (key, _, login) = target
                val cached = intraCache.peek(key)
                if (cached?.coalitionName != null && intraCache.getFresh(key) != null) {
                    skipped++
                } else {
                    _coalitionProgress.value = _coalitionProgress.value.copy(currentLogin = login)
                    runCatching {
                        // Only the coalition name/color here — coalitionsUserId
                        // (needed to POST a score) is fetched lazily by
                        // giveCoalitionPoints instead. Halves the request count
                        // of this pass, which was blowing through the 42 API's
                        // hourly quota on large populations (esp. right after
                        // a "Fetch 42 users info" run in the same hour).
                        val coalition = fetchCoalitionOnly(s, login)
                        val base = cached ?: IntraInfo(fetchedAt = System.currentTimeMillis(), login = login)
                        intraCache.put(
                            key,
                            base.copy(
                                fetchedAt = System.currentTimeMillis(),
                                coalitionName = coalition?.name,
                                coalitionColor = coalition?.color,
                                coalitionImageUrl = coalition?.imageUrl,
                                coalitionId = coalition?.id,
                                coalitionError = false,
                            ),
                        )
                    }.onFailure {
                        failed++
                        // Record the failure so the "Error" filter on the
                        // Users page can surface users who never resolved.
                        val base = cached ?: IntraInfo(fetchedAt = 0, login = login)
                        intraCache.put(key, base.copy(coalitionError = true))
                    }
                    delay(350) // one request per user now — stay well under the 42 API's ~2 req/s limit
                }
                _coalitionProgress.value = _coalitionProgress.value.copy(fetched = index + 1, failed = failed, skipped = skipped)
            }
            _coalitionProgress.value = _coalitionProgress.value.copy(running = false, currentLogin = null)
            val newlyFetched = targets.size - skipped - failed
            _coalitionFetchMessage.value = if (failed == 0) {
                "Fetched $newlyFetched coalitions ($skipped already cached)"
            } else {
                "Fetched $newlyFetched coalitions ($skipped already cached, $failed failed)"
            }
        }
    }

    /**
     * Refetches 42 profile for just [entry] — the "Refetch 42 User" action
     * from a user detail page's menu. Unlike [refetchUserProfiles] this
     * targets one person only and updates immediately, no progress/rate
     * limiting needed for a single request.
     */
    fun refetchCurrentUserProfile(entry: CaDirEntry) {
        viewModelScope.launch {
            val key = entry.intraKey ?: return@launch
            val s = settings.value
            val cached = intraCache.peek(key)
            val merged = runCatching {
                val fresh = fetchIntraUserOnly(s, entry.displayLogin, entry.ftId.ifBlank { null })
                fresh.copy(
                    coalitionName = cached?.coalitionName,
                    coalitionColor = cached?.coalitionColor,
                    coalitionImageUrl = cached?.coalitionImageUrl,
                    coalitionId = cached?.coalitionId,
                    coalitionsUserId = cached?.coalitionsUserId,
                    coalitionError = cached?.coalitionError ?: false,
                    profileError = false,
                )
            }.onFailure {
                val base = cached ?: IntraInfo(fetchedAt = 0, login = entry.displayLogin)
                intraCache.put(key, base.copy(profileError = true))
            }.getOrNull() ?: return@launch
            intraCache.put(key, merged)
            _selectedUser.update { cur ->
                if (cur == null || cur.entry.pk != entry.pk) cur
                else cur.copy(
                    loading = false,
                    login = merged.login ?: cur.login,
                    ftId = merged.ftId ?: cur.ftId,
                    photoUrl = merged.photoUrl ?: cur.photoUrl,
                    userType = merged.userType ?: cur.userType,
                    location = merged.location,
                    level = merged.level,
                    currentProjects = merged.currentProjects,
                )
            }
        }
    }

    /** Refetches the coalition for just [entry] — the "Refetch 42 Coalitions" action from a user detail page's menu. */
    fun refetchCurrentUserCoalition(entry: CaDirEntry) {
        viewModelScope.launch {
            val key = entry.intraKey ?: return@launch
            val s = settings.value
            val cached = intraCache.peek(key)
            val login = cached?.login ?: entry.displayLogin ?: return@launch
            val base = cached ?: IntraInfo(fetchedAt = 0, login = login)
            val merged = runCatching {
                val coalition = fetchCoalitionOnly(s, login)
                val coalitionsUserId = coalition?.let {
                    runCatching { fetchCoalitionsUserId(s, login, it.id) }.getOrNull()
                }
                base.copy(
                    fetchedAt = System.currentTimeMillis(),
                    coalitionName = coalition?.name,
                    coalitionColor = coalition?.color,
                    coalitionImageUrl = coalition?.imageUrl,
                    coalitionId = coalition?.id,
                    coalitionsUserId = coalitionsUserId,
                    coalitionError = false,
                )
            }.onFailure {
                intraCache.put(key, base.copy(coalitionError = true))
            }.getOrNull() ?: return@launch
            intraCache.put(key, merged)
            _selectedUser.update { cur ->
                if (cur == null || cur.entry.pk != entry.pk) cur
                else cur.copy(
                    coalitionName = merged.coalitionName,
                    coalitionColor = merged.coalitionColor,
                    coalitionImageUrl = merged.coalitionImageUrl,
                    coalitionId = merged.coalitionId,
                    coalitionsUserId = merged.coalitionsUserId,
                )
            }
        }
    }

    // ---- Manual badge association (for unrecognized badges) ----

    private val _associating = MutableStateFlow<ScanRecord?>(null)
    /** The scan record for which the user is picking a student, or null. */
    val associating: StateFlow<ScanRecord?> = _associating

    fun startAssociation(record: ScanRecord) { _associating.value = record }
    fun cancelAssociation() { _associating.value = null }

    /** Links the scanned badge to [login] and re-resolves the record. */
    fun associateBadge(record: ScanRecord, login: String) {
        val chosen = login.trim()
        if (chosen.isEmpty()) return
        _associating.value = null
        // Immediate refresh: show the chosen student and drop the error, so
        // the page never lingers on the "unknown badge" state.
        patchRecord(record.timestamp) { it.copy(login = chosen.lowercase(), error = null) }
        viewModelScope.launch {
            manualLinks.put(record.uidHex, chosen)
            // Then enrich with photo/type/coalition once the intra responds
            val info = runCatching { resolveIntra(settings.value, chosen, null) }.getOrNull()
            if (info != null) {
                patchRecord(record.timestamp) { r ->
                    r.copy(
                        login = info.login ?: r.login,
                        ftId = info.ftId ?: r.ftId,
                        photoUrl = info.photoUrl ?: r.photoUrl,
                        userType = info.userType ?: r.userType,
                        coalitionName = info.coalitionName ?: r.coalitionName,
                        coalitionColor = info.coalitionColor ?: r.coalitionColor,
                        coalitionImageUrl = info.coalitionImageUrl ?: r.coalitionImageUrl,
                        error = null,
                    )
                }
            }
        }
    }

    /** Deletes a scan/blame and scrubs it from every in-memory view showing it. */
    fun deleteHistoryEntry(record: ScanRecord) {
        viewModelScope.launch { historyStore.remove(record) }
        _scanState.update { st ->
            when (st) {
                is ScanState.Success -> if (st.record.timestamp == record.timestamp) ScanState.Idle else st
                is ScanState.Failure -> if (st.record.timestamp == record.timestamp) ScanState.Idle else st
                else -> st
            }
        }
        _openedRecord.update { r -> if (r?.timestamp == record.timestamp) null else r }
        _selectedUser.update { d ->
            if (d == null) d else d.copy(scans = d.scans.filterNot { it.timestamp == record.timestamp })
        }
    }

    fun setScanReason(timestamp: Long, reason: String) =
        patchRecord(timestamp) { it.copy(reason = reason.trim().ifBlank { null }) }

    /** Sets a blame's resolution. [tigDuration] is only kept when [status] is [BlameStatus.TIGED]. */
    fun setBlameStatus(timestamp: Long, status: BlameStatus, tigDuration: String?) =
        patchRecord(timestamp) {
            it.copy(
                blameStatus = status,
                tigDuration = if (status == BlameStatus.TIGED) tigDuration?.trim()?.ifBlank { null } else null,
            )
        }

    /** Applies [transform] to a record in history and every in-memory copy. */
    private fun patchRecord(timestamp: Long, transform: (ScanRecord) -> ScanRecord) {
        viewModelScope.launch { historyStore.update(timestamp, transform) }
        _scanState.update { st ->
            when (st) {
                is ScanState.Success ->
                    if (st.record.timestamp == timestamp) st.copy(record = transform(st.record)) else st
                is ScanState.Failure ->
                    if (st.record.timestamp == timestamp) st.copy(record = transform(st.record)) else st
                else -> st
            }
        }
        _openedRecord.update { r -> if (r?.timestamp == timestamp) transform(r) else r }
        _selectedUser.update { d ->
            if (d == null) d
            else d.copy(scans = d.scans.map { if (it.timestamp == timestamp) transform(it) else it })
        }
    }

    private val _openedRecord = MutableStateFlow<ScanRecord?>(null)
    val openedRecord: StateFlow<ScanRecord?> = _openedRecord

    /** Re-open a past scan full-screen, as if the badge was just scanned. */
    fun openRecord(record: ScanRecord) {
        _openedRecord.value = record
    }

    fun closeRecord() {
        _openedRecord.value = null
    }

    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }

    fun resetScan() {
        _scanState.value = ScanState.Idle
    }

    // ---- Users page ----

    enum class UserOrder(val label: String) {
        Alphabetical("A→Z"),
        LatestScan("Latest scan"),
        BlameCount("Blame count"),
    }

    data class UserFilters(
        val query: String = "",
        val type: String? = null,        // "Student" | "Piscine" | null (all)
        val scannedOnly: Boolean = false,
        val errorOnly: Boolean = false,  // profile/coalition fetch failed, or never fetched
        val coalition: String? = null,   // coalition name | null (all)
        // Default: most recently scanned first, then alphabetical for the
        // never-scanned users (handled by the LatestScan comparator).
        val order: UserOrder = UserOrder.LatestScan,
    )

    /** A CA user row enriched with cached intra info + phone-scan stats. */
    data class UserRow(
        val entry: CaDirEntry,
        val login: String?,
        val scanCount: Int,
        /** Scans not yet marked done — shown as the list badge. */
        val pendingCount: Int,
        val lastScan: Long?,
        val userType: String?,
        val coalitionName: String?,
        val coalitionColor: String?,
        val photoUrl: String?,
        /** Profile or coalition fetch failed last attempt, or was never attempted at all. */
        val hasError: Boolean,
    )

    /** Full detail of a CA user, with intra info and every scan of the badge. */
    data class UserDetail(
        val entry: CaDirEntry,
        val loading: Boolean,
        val login: String?,
        val ftId: String?,
        val photoUrl: String?,
        val userType: String?,
        val coalitionName: String?,
        val coalitionColor: String?,
        val coalitionImageUrl: String?,
        val coalitionId: Int?,
        val coalitionsUserId: Int?,
        val location: String?,
        val level: Double?,
        val currentProjects: List<String>,
        val scans: List<ScanRecord>,
    )

    private val _userFilters = MutableStateFlow(UserFilters())
    val userFilters: StateFlow<UserFilters> = _userFilters

    // All rows (enriched with cached intra info), rebuilt when the directory,
    // history or intra cache changes. A person = a unique login: several CA
    // entries (i.e. several badges) for the same login are merged into one row.
    val allUserRows: StateFlow<List<UserRow>> =
        combine(caDirectory.entries, historyStore.history, intraCache.entries) { entries, history, cache ->
            fun infoFor(e: CaDirEntry) = e.intraKey?.let { cache[it.lowercase()] }

            // Group by resolved login (falling back to ft_id, then pk) so the
            // same person on two badges collapses to a single user.
            entries.groupBy { e ->
                (infoFor(e)?.login ?: e.displayLogin)?.lowercase()
                    ?: e.ftId.ifBlank { null }?.let { "id:$it" }
                    ?: "pk:${e.pk}"
            }.map { (_, group) ->
                // Representative entry: the one carrying the most identity
                val rep = group.maxByOrNull {
                    (if (it.ftId.isNotBlank()) 2 else 0) + (if (it.ftLogin.isNotBlank()) 1 else 0)
                } ?: group.first()
                val info = infoFor(rep) ?: group.firstNotNullOfOrNull { infoFor(it) }
                // Scans of any badge of this person
                val scans = group.flatMap { scansFor(it, history) }.distinctBy { it.timestamp }
                UserRow(
                    entry = rep,
                    login = info?.login ?: rep.displayLogin,
                    scanCount = scans.size,
                    pendingCount = scans.count { it.blameStatus == BlameStatus.NOT_HANDLED },
                    lastScan = scans.maxOfOrNull { it.timestamp },
                    userType = info?.userType,
                    coalitionName = info?.coalitionName,
                    coalitionColor = info?.coalitionColor,
                    photoUrl = info?.photoUrl,
                    hasError = info == null || info.profileError || info.coalitionError,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Coalitions present among the users, for the coalition filter. */
    val availableCoalitions: StateFlow<List<String>> =
        allUserRows.map { rows ->
            rows.mapNotNull { it.coalitionName }.distinct().sorted()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Rows after search + filters + ordering. */
    val userRows: StateFlow<List<UserRow>> =
        combine(allUserRows, _userFilters) { rows, f ->
            val q = f.query.trim().lowercase()
            rows.asSequence()
                .filter { row ->
                    q.isEmpty() ||
                        (row.login?.lowercase()?.contains(q) == true) ||
                        row.entry.fullName.lowercase().contains(q)
                }
                .filter { f.type == null || it.userType == f.type }
                .filter { !f.scannedOnly || it.scanCount > 0 }
                .filter { !f.errorOnly || it.hasError }
                .filter { f.coalition == null || it.coalitionName == f.coalition }
                .sortedWith(orderComparator(f.order))
                .toList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun orderComparator(order: UserOrder): Comparator<UserRow> = when (order) {
        UserOrder.Alphabetical ->
            compareBy { (it.login ?: it.entry.fullName).lowercase() }
        UserOrder.LatestScan ->
            compareByDescending<UserRow> { it.lastScan ?: Long.MIN_VALUE }
                .thenBy { (it.login ?: it.entry.fullName).lowercase() }
        UserOrder.BlameCount ->
            compareByDescending<UserRow> { it.scanCount }
                .thenBy { (it.login ?: it.entry.fullName).lowercase() }
    }

    fun setUserQuery(query: String) { _userFilters.update { it.copy(query = query) } }
    fun setUserType(type: String?) { _userFilters.update { it.copy(type = type) } }
    fun setUserErrorOnly(only: Boolean) { _userFilters.update { it.copy(errorOnly = only) } }
    fun setUserScannedOnly(only: Boolean) { _userFilters.update { it.copy(scannedOnly = only) } }
    fun setUserCoalition(coalition: String?) { _userFilters.update { it.copy(coalition = coalition) } }
    fun setUserOrder(order: UserOrder) { _userFilters.update { it.copy(order = order) } }

    private val _selectedUser = MutableStateFlow<UserDetail?>(null)
    val selectedUser: StateFlow<UserDetail?> = _selectedUser

    /** Blames (not every raw scan) recorded for this user — see [ScanRecord.isBlame]. */
    private fun scansFor(entry: CaDirEntry, history: List<ScanRecord>): List<ScanRecord> =
        history.filter { r ->
            r.isBlame &&
                ((entry.ftId.isNotBlank() && r.ftId == entry.ftId) ||
                    (r.login != null && entry.displayLogin != null &&
                        r.login.equals(entry.displayLogin, ignoreCase = true)))
        }

    /**
     * Opens a user detail. Renders straight from [IntraCache] — never blocks
     * on the network, so there's no visible refetch when the user was
     * already resolved (e.g. by "Refetch 42 users"). Only hits the network
     * synchronously (with the loading spinner) the very first time a user
     * has no cached intra info at all.
     *
     * Either way, a silent background pass then refreshes location/level/
     * current projects (which age quickly and aren't part of the bulk
     * refetch's steady-state cache hit) — it updates the cache and the open
     * detail once done, but never toggles [UserDetail.loading].
     */
    fun openUser(entry: CaDirEntry) {
        val scans = scansFor(entry, historyStore.history.value).sortedByDescending { it.timestamp }
        val cacheKey = entry.intraKey
        val cached = cacheKey?.let { intraCache.peek(it) }
        _selectedUser.value = UserDetail(
            entry = entry,
            loading = cached == null && settings.value.ftConfigured,
            login = cached?.login ?: entry.displayLogin,
            ftId = cached?.ftId ?: entry.ftId.ifBlank { null },
            photoUrl = cached?.photoUrl,
            userType = cached?.userType,
            coalitionName = cached?.coalitionName,
            coalitionColor = cached?.coalitionColor,
            coalitionImageUrl = cached?.coalitionImageUrl,
            coalitionId = cached?.coalitionId,
            coalitionsUserId = cached?.coalitionsUserId,
            location = cached?.location,
            level = cached?.level,
            currentProjects = cached?.currentProjects.orEmpty(),
            scans = scans,
        )
        if (!settings.value.ftConfigured) return

        if (cached == null) {
            // Nothing cached at all yet: resolve for real, visibly.
            viewModelScope.launch {
                val info = runCatching {
                    resolveIntra(settings.value, entry.displayLogin, entry.ftId.ifBlank { null })
                }.getOrNull()
                _selectedUser.update { cur ->
                    if (cur == null || cur.entry.pk != entry.pk) cur
                    else cur.copy(
                        loading = false,
                        login = info?.login ?: cur.login,
                        ftId = info?.ftId ?: cur.ftId,
                        photoUrl = info?.photoUrl ?: cur.photoUrl,
                        userType = info?.userType ?: cur.userType,
                        coalitionName = info?.coalitionName ?: cur.coalitionName,
                        coalitionColor = info?.coalitionColor ?: cur.coalitionColor,
                        coalitionImageUrl = info?.coalitionImageUrl ?: cur.coalitionImageUrl,
                        coalitionId = info?.coalitionId ?: cur.coalitionId,
                        coalitionsUserId = info?.coalitionsUserId ?: cur.coalitionsUserId,
                        location = info?.location ?: cur.location,
                        level = info?.level ?: cur.level,
                        currentProjects = info?.currentProjects ?: cur.currentProjects,
                    )
                }
            }
        } else {
            // Already showing cached data — silently top up location/level/
            // projects in the background, ignoring failures entirely.
            viewModelScope.launch {
                val login = cached.login ?: entry.displayLogin
                val ftId = cached.ftId ?: entry.ftId.ifBlank { null }
                val fresh = runCatching { fetchIntraUserOnly(settings.value, login, ftId) }.getOrNull()
                    ?: return@launch
                val merged = cached.copy(
                    login = fresh.login ?: cached.login,
                    ftId = fresh.ftId ?: cached.ftId,
                    photoUrl = fresh.photoUrl ?: cached.photoUrl,
                    userType = fresh.userType ?: cached.userType,
                    location = fresh.location,
                    level = fresh.level,
                    currentProjects = fresh.currentProjects,
                    fetchedAt = System.currentTimeMillis(),
                )
                intraCache.put(cacheKey, merged)
                _selectedUser.update { cur ->
                    if (cur == null || cur.entry.pk != entry.pk) cur
                    else cur.copy(
                        login = merged.login ?: cur.login,
                        ftId = merged.ftId ?: cur.ftId,
                        photoUrl = merged.photoUrl ?: cur.photoUrl,
                        userType = merged.userType ?: cur.userType,
                        location = merged.location,
                        level = merged.level,
                        currentProjects = merged.currentProjects,
                    )
                }
            }
        }
    }

    fun closeUser() {
        _selectedUser.value = null
    }

    /**
     * Removes a CA user from the local cache only (never touches the CA
     * itself) along with every scan of theirs recorded on this phone — for
     * stale CA entries (lost badges, etc.) that shouldn't show up anymore.
     * Temporary: a later "Refetch CA users" restores it if still on the CA.
     */
    fun deleteUser(entry: CaDirEntry) {
        viewModelScope.launch {
            val toRemove = scansFor(entry, historyStore.history.value)
            toRemove.forEach { historyStore.remove(it) }
            caDirectory.remove(entry.pk)
            closeUser()
        }
    }

    /**
     * Records a scan as if the badge had just been tapped, using the intra
     * info already loaded for this user — for when the physical badge isn't
     * available. Opens it immediately so a reason/done state can be set.
     */
    fun addManualBlame(detail: UserDetail) {
        viewModelScope.launch {
            val record = ScanRecord(
                timestamp = System.currentTimeMillis(),
                uidHex = "manual:${detail.entry.pk}:${System.currentTimeMillis()}",
                mifareHex = "",
                wiegand = "MANUAL",
                login = detail.login,
                ftId = detail.ftId,
                photoUrl = detail.photoUrl,
                userType = detail.userType,
                coalitionName = detail.coalitionName,
                coalitionColor = detail.coalitionColor,
                coalitionImageUrl = detail.coalitionImageUrl,
                isBlame = true,
            )
            historyStore.add(record)
            _selectedUser.update { cur ->
                if (cur?.entry?.pk == detail.entry.pk) cur.copy(scans = listOf(record) + cur.scans) else cur
            }
            openRecord(record)
        }
    }

    private val _coalitionActionMessage = MutableStateFlow<String?>(null)
    val coalitionActionMessage: StateFlow<String?> = _coalitionActionMessage

    private val _coalitionActionSending = MutableStateFlow(false)
    val coalitionActionSending: StateFlow<Boolean> = _coalitionActionSending

    /** Bumped only when a coalition-points POST actually succeeds — lets the card clear its inputs precisely then, not just on any submit. */
    private val _coalitionActionSuccessTick = MutableStateFlow(0L)
    val coalitionActionSuccessTick: StateFlow<Long> = _coalitionActionSuccessTick

    /**
     * POSTs coalition points (positive or negative) for the given user.
     * [UserDetail.coalitionsUserId] is no longer preloaded by the bulk
     * coalition fetch (too many requests for large populations — see
     * refetchCoalitions) — it's resolved here on demand instead, the first
     * time points are given to that person, and cached from then on.
     */
    fun giveCoalitionPoints(detail: UserDetail, value: Int, reason: String) {
        val coalitionId = detail.coalitionId
        val login = detail.login
        if (coalitionId == null || login == null) {
            _coalitionActionMessage.value = "Coalition inconnue pour cet utilisateur"
            return
        }
        viewModelScope.launch {
            _coalitionActionMessage.value = null
            _coalitionActionSending.value = true
            val coalitionsUserId = detail.coalitionsUserId
                ?: runCatching { fetchCoalitionsUserId(settings.value, login, coalitionId) }.getOrNull()
                    ?.also { resolvedId ->
                        val key = detail.entry.intraKey
                        val cached = key?.let { intraCache.peek(it) }
                        if (key != null && cached != null) {
                            intraCache.put(key, cached.copy(coalitionsUserId = resolvedId))
                        }
                        _selectedUser.update { cur ->
                            if (cur?.entry?.pk == detail.entry.pk) cur.copy(coalitionsUserId = resolvedId) else cur
                        }
                    }
            if (coalitionsUserId == null) {
                _coalitionActionMessage.value = "Coalition inconnue pour cet utilisateur"
                _coalitionActionSending.value = false
                return@launch
            }
            runCatching {
                intraApi.postCoalitionScore(settings.value, coalitionId, coalitionsUserId, value, reason)
            }.onSuccess {
                _coalitionActionMessage.value = "$value point(s) envoyé(s) à $login"
                _coalitionActionSuccessTick.update { it + 1 }
            }.onFailure { e ->
                _coalitionActionMessage.value = e.message ?: "Échec de l'envoi des points"
            }
            _coalitionActionSending.value = false
        }
    }

    private val _tigActionMessage = MutableStateFlow<String?>(null)
    val tigActionMessage: StateFlow<String?> = _tigActionMessage

    /**
     * POSTs a TIG (`close[kind]=social_security`) for the given user.
     * [durationSeconds] must be one of 7200/14400/28800 (2h/4h/8h).
     */
    fun giveTig(detail: UserDetail, durationSeconds: Int, reason: String) {
        val s = settings.value
        val userId = detail.ftId
        val closerId = s.closerId
        if (userId == null || closerId.isBlank()) {
            _tigActionMessage.value = "Closer ID non configuré (voir Réglages) ou utilisateur 42 inconnu"
            return
        }
        viewModelScope.launch {
            _tigActionMessage.value = null
            runCatching {
                intraApi.postTig(s, userId, closerId, reason, durationSeconds)
            }.onSuccess {
                val hours = durationSeconds / 3600
                _tigActionMessage.value = "TIG de ${hours}h envoyé à ${detail.login ?: userId}"
            }.onFailure { e ->
                _tigActionMessage.value = e.message ?: "Échec de l'envoi du TIG"
            }
        }
    }

    // ---- Cluster page ----

    /** One occupied seat, keyed by host id (e.g. "c1r2p3"). */
    data class ClusterOccupant(val login: String, val photoUrl: String?)

    private val _clusters = MutableStateFlow<List<ClusterInfo>>(emptyList())
    val clusters: StateFlow<List<ClusterInfo>> = _clusters

    /** Parsed seat-map layout per cluster id. */
    private val _clusterLayouts = MutableStateFlow<Map<Int, ClusterLayout>>(emptyMap())
    val clusterLayouts: StateFlow<Map<Int, ClusterLayout>> = _clusterLayouts

    /** Currently-occupied seats across the whole campus, keyed by host id. Shared by every cluster tab. */
    private val _clusterOccupants = MutableStateFlow<Map<String, ClusterOccupant>>(emptyMap())
    val clusterOccupants: StateFlow<Map<String, ClusterOccupant>> = _clusterOccupants

    private val _clusterLoading = MutableStateFlow(false)
    val clusterLoading: StateFlow<Boolean> = _clusterLoading

    private val _clusterMessage = MutableStateFlow<String?>(null)
    val clusterMessage: StateFlow<String?> = _clusterMessage

    /** Campus the currently-loaded clusters/layouts belong to — avoids refetching every time the tab reopens. */
    private var clustersLoadedForCampus: String? = null

    /**
     * Loads the campus's clusters, their seat-map SVGs (parsed once, not
     * re-fetched every time), and who's currently sitting where. Cheap to
     * call repeatedly — only hits the network the first time for a given
     * campus, or when [force] is set (pull-to-refresh).
     */
    fun loadClusters(force: Boolean = false) {
        val s = settings.value
        if (!force && clustersLoadedForCampus == s.campusId && _clusters.value.isNotEmpty()) {
            refreshClusterOccupants()
            return
        }
        viewModelScope.launch {
            _clusterLoading.value = true
            _clusterMessage.value = null
            runCatching {
                // Natural sort by the trailing number in the name (c1, c2, …
                // c10, not the lexicographic c1, c10, c2, …).
                val list = intraApi.fetchClusters(s, s.campusId)
                    .sortedBy { Regex("""\d+""").find(it.name)?.value?.toIntOrNull() ?: Int.MAX_VALUE }
                _clusters.value = list
                val layouts = mutableMapOf<Int, ClusterLayout>()
                for (c in list) {
                    val svg = intraApi.fetchClusterSvg(c.cdnLink)
                    layouts[c.id] = parseClusterSvg(svg)
                }
                _clusterLayouts.value = layouts
                val locations = intraApi.fetchActiveLocations(s, s.campusId)
                _clusterOccupants.value = locations.associate { it.host to ClusterOccupant(it.login, it.photoUrl) }
                clustersLoadedForCampus = s.campusId
            }.onFailure { e ->
                _clusterMessage.value = e.message ?: "Échec du chargement des clusters"
            }
            _clusterLoading.value = false
        }
    }

    /** Refreshes just who's sitting where — not the cluster list/SVGs, which don't change. */
    fun refreshClusterOccupants() {
        viewModelScope.launch {
            _clusterLoading.value = true
            _clusterMessage.value = null
            runCatching {
                val locations = intraApi.fetchActiveLocations(settings.value, settings.value.campusId)
                _clusterOccupants.value = locations.associate { it.host to ClusterOccupant(it.login, it.photoUrl) }
            }.onFailure { e ->
                _clusterMessage.value = e.message ?: "Échec du rafraîchissement"
            }
            _clusterLoading.value = false
        }
    }

    /** Opens a seat's occupant on the user detail page, if they're a known CA user. */
    fun openUserByLogin(login: String) {
        val entry = caDirectory.entries.value.firstOrNull {
            it.displayLogin?.equals(login, ignoreCase = true) == true
        }
        if (entry != null) openUser(entry)
    }

    /**
     * Host (e.g. "c1r3p6") to center the Cluster tab's map on — set from a
     * user's own page ("En ligne · c1r3p6" is clickable) and consumed once
     * by [ClusterScreen], which clears it back to null when handled.
     */
    private val _clusterLocateRequest = MutableStateFlow<String?>(null)
    val clusterLocateRequest: StateFlow<String?> = _clusterLocateRequest

    fun locateOnCluster(host: String) {
        _clusterLocateRequest.value = host
    }

    fun clearClusterLocateRequest() {
        _clusterLocateRequest.value = null
    }

    companion object {
        /** Coalitions shown with priority over the piscine ones, in this order. */
        private val PRIORITY_COALITIONS = listOf("harkonnen", "corrino", "atreides")
        private val SECONDARY_COALITIONS = listOf("hordes", "alliance")

        /**
         * cursus 21 => main student cursus, cursus 9 => piscine. A student
         * enrolled in 21 is "Student"; someone with only 9 is "Piscine".
         */
        fun userTypeFromCursus(cursusIds: List<Int>): String? = when {
            21 in cursusIds -> "Student"
            9 in cursusIds -> "Piscine"
            else -> null
        }

        /**
         * Picks the coalition to theme the page with: a main coalition
         * (Harkonnen/Corrino/Atreides) wins over a piscine one
         * (Hordes/Alliance); otherwise the highest-scoring coalition.
         *
         * Matches on slug OR name (case-insensitive) because coalition slugs
         * can be campus-prefixed — matching slug alone would miss e.g. Corrino
         * and wrongly fall back to the highest-scoring (piscine) coalition.
         */
        fun pickCoalition(coalitions: List<Coalition>): Coalition? {
            fun match(keys: List<String>) = keys.firstNotNullOfOrNull { key ->
                coalitions.firstOrNull { c ->
                    c.slug.equals(key, ignoreCase = true) ||
                        c.name.equals(key, ignoreCase = true)
                }
            }
            return match(PRIORITY_COALITIONS)
                ?: match(SECONDARY_COALITIONS)
                ?: coalitions.maxByOrNull { it.score }
        }

    }
}
