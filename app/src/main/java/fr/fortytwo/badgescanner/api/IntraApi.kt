package fr.fortytwo.badgescanner.api

import android.util.Log
import fr.fortytwo.badgescanner.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

data class IntraUser(
    val id: String,
    val login: String,
    val displayName: String,
    val imageUrl: String?,
    /** cursus ids the user is enrolled in (21 = main cursus, 9 = piscine). */
    val cursusIds: List<Int>,
    /** Current physical location (e.g. "c1r2s3"), null when offline. */
    val location: String? = null,
    /** Level in the main cursus (21), null if not enrolled in it. */
    val level: Double? = null,
    /** Names of projects currently in progress. */
    val currentProjects: List<String> = emptyList(),
)

data class Coalition(
    val id: Int,
    val name: String,
    val slug: String,
    val color: String?,
    val imageUrl: String?,
    val coverUrl: String?,
    val score: Int,
)

/**
 * A `coalitions_user` record: links one user to one coalition. Its [id] —
 * not the coalition id, not the user id — is what `POST .../scores` actually
 * wants as `score[coalitions_user_id]`.
 */
data class CoalitionsUser(
    val id: Int,
    val coalitionId: Int,
)

/** One cluster room on a campus (e.g. "c1"), with its seat-map SVG. */
data class ClusterInfo(
    val id: Int,
    val name: String,
    val cdnLink: String,
)

/** One occupied seat, from `GET /campus/{id}/locations`. */
data class ClusterLocation(
    /** Seat id in the cluster SVG, e.g. "c1r2p3". */
    val host: String,
    val login: String,
    val photoUrl: String?,
)

/**
 * Minimal 42 API v2 client (client_credentials flow, like 42-watchdog's
 * `42apiV2` config). Used to resolve the login and the profile picture.
 */
class IntraApi(private val http: OkHttpClient) {

    private val tokenMutex = Mutex()
    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0

    /**
     * Runs [request], logging its HTTP code and duration under [label]. On a
     * 429 (rate limited), backs off for the `Retry-After` header's duration
     * (or [DEFAULT_RETRY_AFTER_MS] if absent) and retries, up to
     * [MAX_RETRIES] times, instead of the caller hanging on a slow/blocked
     * connection until the socket read timeout.
     */
    private suspend fun executeWithRetry(request: Request, label: String): Response {
        var attempt = 0
        while (true) {
            attempt++
            val start = System.currentTimeMillis()
            val response = http.newCall(request).execute()
            val elapsedMs = System.currentTimeMillis() - start
            Log.d(TAG, "$label -> HTTP ${response.code} in ${elapsedMs}ms (attempt $attempt)")
            if (response.code == 429 && attempt <= MAX_RETRIES) {
                val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                    ?: DEFAULT_RETRY_AFTER_MS
                response.close()
                Log.w(TAG, "$label rate-limited (429), backing off ${retryAfterMs}ms before retry $attempt")
                delay(retryAfterMs)
                continue
            }
            return response
        }
    }

    /**
     * Fetches a user by ft_id when available, by login otherwise
     * (`GET /v2/users/{id|login}`).
     */
    suspend fun fetchUser(settings: AppSettings, ftId: String?, login: String?): IntraUser =
        withContext(Dispatchers.IO) {
            val key = ftId?.takeIf { it.isNotBlank() }
                ?: login?.takeIf { it.isNotBlank() }?.lowercase()
                ?: throw IOException("No ft_id or ft_login to look up on the intranet")

            val token = getToken(settings)
            val request = Request.Builder()
                .url("${settings.ftEndpoint.trimEnd('/')}/users/$key")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            executeWithRetry(request, "fetchUser $key").use { resp ->
                if (resp.code == 404) throw IOException("User '$key' not found on the intranet")
                if (!resp.isSuccessful) throw IOException("Intranet request failed: HTTP ${resp.code}")
                parseUser(resp.body?.string().orEmpty())
                    ?: throw IOException("Could not parse intranet user response")
            }
        }

    /** `GET /v2/users/{login}/coalitions` — the user's coalitions. */
    suspend fun fetchCoalitions(settings: AppSettings, login: String): List<Coalition> =
        withContext(Dispatchers.IO) {
            val token = getToken(settings)
            val request = Request.Builder()
                .url("${settings.ftEndpoint.trimEnd('/')}/users/${login.lowercase()}/coalitions")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            executeWithRetry(request, "fetchCoalitions $login").use { resp ->
                // A 404 here means the user has no coalitions_user record at
                // all (never assigned one, e.g. early piscine) — the 42 API
                // reports that as 404 rather than 200 + []. That's a genuine
                // "no coalition", not a failure. Any other non-2xx (429
                // exhausted, 5xx, auth issue…) IS a real failure and must not
                // be cached as "0 coalitions".
                if (resp.code == 404) return@withContext emptyList()
                if (!resp.isSuccessful) {
                    throw IOException("Coalitions request failed: HTTP ${resp.code}")
                }
                val arr = runCatching { Json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray }
                    .getOrNull() ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    Coalition(
                        id = o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null,
                        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        slug = o["slug"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        color = o["color"]?.jsonPrimitive?.contentOrNull,
                        imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                        coverUrl = o["cover_url"]?.jsonPrimitive?.contentOrNull,
                        score = o["score"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    )
                }
            }
        }

    /**
     * `GET /v2/users/{login}/coalitions_users` — the user's `coalitions_user`
     * records (one per coalition they belong to). Their `id` — not the
     * coalition id — is what the scores endpoint wants
     * (`score[coalitions_user_id]`).
     */
    suspend fun fetchCoalitionsUsers(settings: AppSettings, login: String): List<CoalitionsUser> =
        withContext(Dispatchers.IO) {
            val token = getToken(settings)
            val request = Request.Builder()
                .url("${settings.ftEndpoint.trimEnd('/')}/users/${login.lowercase()}/coalitions_users")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            executeWithRetry(request, "fetchCoalitionsUsers $login").use { resp ->
                // Same reasoning as fetchCoalitions: 404 means "no
                // coalitions_user record at all", a genuine empty result, not
                // a failure to be treated the same as a real error.
                if (resp.code == 404) return@withContext emptyList()
                if (!resp.isSuccessful) {
                    throw IOException("Coalitions_users request failed: HTTP ${resp.code}")
                }
                val arr = runCatching { Json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray }
                    .getOrNull() ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    CoalitionsUser(
                        id = o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null,
                        coalitionId = o["coalition_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?: return@mapNotNull null,
                    )
                }
            }
        }

    /**
     * `POST /v2/coalitions/{coalitionId}/scores` — gives (or takes, if
     * negative) coalition points to a user. Form-encoded, matching how the
     * 42 API expects `score[...]` fields (same shape as the token request).
     *
     * Needs the target `coalitions_user` record's own id (from
     * [fetchCoalitionsUsers]), not the login — `score[coalitions_user]` with
     * a login silently does the wrong thing; the API wants
     * `score[coalitions_user_id]`.
     */
    suspend fun postCoalitionScore(settings: AppSettings, coalitionId: Int, coalitionsUserId: Int, value: Int, reason: String) =
        withContext(Dispatchers.IO) {
            val token = getToken(settings)
            val form = FormBody.Builder()
                .add("score[reason]", reason)
                .add("score[coalitions_user_id]", coalitionsUserId.toString())
                .add("score[value]", value.toString())
                .build()
            val request = Request.Builder()
                .url("${settings.ftEndpoint.trimEnd('/')}/coalitions/$coalitionId/scores")
                .header("Authorization", "Bearer $token")
                .post(form)
                .build()
            executeWithRetry(request, "postCoalitionScore $coalitionsUserId").use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Coalition score request failed: HTTP ${resp.code}")
                }
            }
        }

    /**
     * `POST /v2/closes` — gives a "TIG" (serious_misconduct close with a
     * community-service duration) to a user. [durationSeconds] must be one
     * of 7200 (2h), 14400 (4h) or 28800 (8h), per the API.
     *
     * `community_services_attributes` must be an *array* of hashes
     * (`close[community_services_attributes][][duration]`) — sent as a plain
     * hash (no `[]`), the API 500s.
     */
    suspend fun postTig(settings: AppSettings, userId: String, closerId: String, reason: String, durationSeconds: Int) =
        withContext(Dispatchers.IO) {
            val token = getToken(settings)
            val form = FormBody.Builder()
                .add("close[user_id]", userId)
                .add("close[kind]", "serious_misconduct")
                .add("close[state]", "close")
                .add("close[closer_id]", closerId)
                .add("close[reason]", reason)
                .add("close[community_services_attributes][][duration]", durationSeconds.toString())
                .build()
            val request = Request.Builder()
                .url("${settings.ftEndpoint.trimEnd('/')}/closes")
                .header("Authorization", "Bearer $token")
                .post(form)
                .build()
            executeWithRetry(request, "postTig $userId").use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("TIG request failed: HTTP ${resp.code}")
                }
            }
        }

    /** `GET /v2/clusters?filter[campus_id]={campusId}` — the cluster rooms on a campus. */
    suspend fun fetchClusters(settings: AppSettings, campusId: String): List<ClusterInfo> =
        withContext(Dispatchers.IO) {
            val token = getToken(settings)
            val request = Request.Builder()
                .url("${settings.ftEndpoint.trimEnd('/')}/clusters?filter[campus_id]=$campusId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            executeWithRetry(request, "fetchClusters $campusId").use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Clusters request failed: HTTP ${resp.code}")
                }
                val arr = runCatching { Json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray }
                    .getOrNull() ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    ClusterInfo(
                        id = o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null,
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        cdnLink = o["cdn_link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    )
                }
            }
        }

    /**
     * Raw fetch of a cluster's seat-map SVG straight from the CDN — public,
     * no bearer token needed (it's `cdn.intra.42.fr`, not `api.intra.42.fr`).
     */
    suspend fun fetchClusterSvg(cdnLink: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(cdnLink).get().build()
            executeWithRetry(request, "fetchClusterSvg").use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Cluster SVG fetch failed: HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        }

    /**
     * `GET /v2/campus/{campusId}/locations` — every currently-occupied seat
     * on the campus, across every page (the API caps `page[size]` at 100).
     */
    suspend fun fetchActiveLocations(settings: AppSettings, campusId: String): List<ClusterLocation> =
        withContext(Dispatchers.IO) {
            val token = getToken(settings)
            val results = mutableListOf<ClusterLocation>()
            var page = 1
            while (true) {
                val request = Request.Builder()
                    .url(
                        "${settings.ftEndpoint.trimEnd('/')}/campus/$campusId/locations" +
                            "?sort=host&filter[active]=true&page[size]=100&page[number]=$page",
                    )
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val pageItems = executeWithRetry(request, "fetchActiveLocations page $page").use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Locations request failed: HTTP ${resp.code}")
                    }
                    val arr = runCatching { Json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray }
                        .getOrNull() ?: return@use emptyList()
                    arr.mapNotNull { el ->
                        val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                        val host = o["host"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val user = o["user"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: return@mapNotNull null
                        val login = user["login"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val image = user["image"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val photoUrl = image?.get("link")?.jsonPrimitive?.contentOrNull
                        ClusterLocation(host, login, photoUrl)
                    }
                }
                results += pageItems
                if (pageItems.size < 100) break
                page++
                delay(350) // stay well under the 42 API's ~2 req/s limit across pages
            }
            results
        }

    private fun parseUser(body: String): IntraUser? {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val image = root["image"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val versions = image?.get("versions")?.let { runCatching { it.jsonObject }.getOrNull() }
        val imageUrl = versions?.get("medium")?.jsonPrimitive?.contentOrNull
            ?: image?.get("link")?.jsonPrimitive?.contentOrNull
        val cursusUsers = (root["cursus_users"] as? JsonArray)?.mapNotNull { cu ->
            runCatching { cu.jsonObject }.getOrNull()
        }.orEmpty()
        val cursusIds = cursusUsers.mapNotNull { o ->
            o["cursus_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: (o["cursus"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("id")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        }.distinct()
        val level = cursusUsers.firstOrNull { o ->
            val cid = o["cursus_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: (o["cursus"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("id")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            cid == 21
        }?.get("level")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val currentProjects = (root["projects_users"] as? JsonArray)?.mapNotNull { pu ->
            val o = runCatching { pu.jsonObject }.getOrNull() ?: return@mapNotNull null
            val status = o["status"]?.jsonPrimitive?.contentOrNull
            if (status != "in_progress") return@mapNotNull null
            (o["project"] as? kotlinx.serialization.json.JsonObject)
                ?.get("name")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        return IntraUser(
            id = root["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            login = root["login"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            displayName = root["displayname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            imageUrl = imageUrl,
            cursusIds = cursusIds,
            location = root["location"]?.jsonPrimitive?.contentOrNull,
            level = level,
            currentProjects = currentProjects,
        )
    }

    private suspend fun getToken(settings: AppSettings): String = tokenMutex.withLock {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) {
            return cached
        }

        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", settings.ftUid)
            .add("client_secret", settings.ftSecret)
            .add("scope", "public projects profile elearning tig forum")
            .build()
        val request = Request.Builder().url(settings.ftTokenUrl).post(form).build()

        executeWithRetry(request, "getToken").use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("42 API token request failed: HTTP ${resp.code} — check the API UID/secret")
            }
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw IOException("Could not parse 42 API token response")
            val token = root["access_token"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("42 API token response has no access_token")
            val expiresIn = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600
            accessToken = token
            tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000
            return token
        }
    }

    companion object {
        private const val TAG = "BadgeScanner"
        private const val MAX_RETRIES = 3
        private const val DEFAULT_RETRY_AFTER_MS = 2_000L
    }
}
