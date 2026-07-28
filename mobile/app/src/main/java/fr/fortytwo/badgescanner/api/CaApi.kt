package fr.fortytwo.badgescanner.api

import fr.fortytwo.badgescanner.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Detail of a CA user (`GET /users/{pk}/`), as 42-watchdog reads it. */
data class CaUser(
    val ftLogin: String,
    val ftId: String,
    val accessProfile: Int,
)

/** One user of the CA directory listing (`GET /users/`), badge numbers included. */
@Serializable
data class CaDirEntry(
    val pk: Long,
    val fullName: String = "",
    val ftLogin: String = "",
    val ftId: String = "",
    val badges: List<Long> = emptyList(),
) {
    /** Login to use on the intra: ft_login, else a piscine name login. */
    val displayLogin: String?
        get() = ftLogin.ifBlank { null } ?: fr.fortytwo.badgescanner.util.piscineLoginFromName(fullName)

    /** Cache/history key: ft_id when present, else the display login. */
    val intraKey: String?
        get() = ftId.ifBlank { null } ?: displayLogin

    /** Whether this user is resolvable on the intra (kept in the Users list). */
    val isResolvable: Boolean
        get() = ftLogin.isNotBlank() || ftId.isNotBlank() || displayLogin != null

    /**
     * Whether this user is kept at refetch time: resolvable, not a "3b3…"
     * account (42 workstation/system accounts start with that prefix), and
     * not a "[BH]" tagged name.
     */
    val isListable: Boolean
        get() {
            if (!isResolvable) return false
            if (fullName.contains("[BH]", ignoreCase = true)) return false
            val prefix = "3b3"
            if (fullName.trim().startsWith(prefix, ignoreCase = true)) return false
            if (ftLogin.startsWith(prefix, ignoreCase = true)) return false
            if (displayLogin?.startsWith(prefix, ignoreCase = true) == true) return false
            return true
        }
}

/**
 * Client for the access control API ("CA", an Ixoff ibox). The user pk is
 * NOT the badge number: badge numbers live in each user's `badges[].number`,
 * so badge resolution goes through the full user listing (see CaDirectory).
 */
class CaApi(private val http: OkHttpClient) {

    /**
     * Fetches every user of the CA, one page at a time (default 30), calling
     * [onPage] after each page with the entries so far and the total count
     * (`count`, or -1 if the CA doesn't report it) so the UI can show progress.
     */
    suspend fun fetchAllUsers(
        settings: AppSettings,
        pageSize: Int = 30,
        onPage: suspend (fetched: Int, total: Int) -> Unit = { _, _ -> },
    ): List<CaDirEntry> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<CaDirEntry>()
            // Hint both common DRF paginations; unknown params are ignored
            var url: String? = "${settings.caEndpoint.trimEnd('/')}/users/" +
                "?limit=$pageSize&page_size=$pageSize"
            val visited = mutableSetOf<String>()
            var total = -1

            while (url != null && visited.add(url) && visited.size <= 10_000) {
                val body = get(settings, url)
                val root = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                    ?: throw IOException("Could not parse CA users response")

                val (results, next) = when (root) {
                    is JsonArray -> root to null
                    is JsonObject -> {
                        val arr = (root["results"] as? JsonArray) ?: (root["data"] as? JsonArray)
                        val c = (root["count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                        if (c != null) total = c
                        val n = (root["next"] as? JsonPrimitive)?.contentOrNull
                        (arr ?: JsonArray(emptyList())) to n
                    }
                    else -> JsonArray(emptyList()) to null
                }

                results.mapNotNullTo(out) { parseDirEntry(it) }
                onPage(out.size, total)
                // `next` uses the ibox's internal hostname; keep only its
                // path/query and rebase onto the configured endpoint
                url = next?.let { rebase(it, settings.caEndpoint) }
            }
            out
        }

    /** `GET /users/{pk}/` — same fields watchdog's CreateNewUser reads. */
    suspend fun fetchUser(settings: AppSettings, pk: Long): CaUser? =
        withContext(Dispatchers.IO) {
            val body = get(settings, "${settings.caEndpoint.trimEnd('/')}/users/$pk/")
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return@withContext null
            val properties = root["properties"] as? JsonObject
            CaUser(
                ftLogin = properties?.get("ft_login")?.jsonPrimitive?.contentOrNull.orEmpty(),
                ftId = properties?.get("ft_id")?.jsonPrimitive?.contentOrNull.orEmpty(),
                accessProfile = root["access_profile"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            )
        }

    private fun get(settings: AppSettings, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(settings.caUsername, settings.caPassword))
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw IOException("CA authentication failed (${resp.code}) — check the CA username/password")
            }
            if (!resp.isSuccessful) throw IOException("CA request failed: HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun parseDirEntry(element: JsonElement): CaDirEntry? {
        val obj = element as? JsonObject ?: return null
        val pk = ((obj["pk"] ?: obj["id"]) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        val properties = obj["properties"] as? JsonObject
        val badges = (obj["badges"] as? JsonArray)?.mapNotNull { badge ->
            when (badge) {
                is JsonObject -> (badge["number"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                is JsonPrimitive -> badge.contentOrNull?.toLongOrNull()
                else -> null
            }
        } ?: emptyList()
        return CaDirEntry(
            pk = pk,
            fullName = (obj["full_name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            ftLogin = properties?.get("ft_login")?.jsonPrimitive?.contentOrNull.orEmpty(),
            ftId = properties?.get("ft_id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            badges = badges,
        )
    }

    private fun rebase(next: String, endpoint: String): String? {
        val nextUrl = next.toHttpUrlOrNull() ?: return null
        val base = endpoint.toHttpUrlOrNull() ?: return next
        return nextUrl.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
            .toString()
    }
}
