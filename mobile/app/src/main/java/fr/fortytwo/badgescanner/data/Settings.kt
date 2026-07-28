package fr.fortytwo.badgescanner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val caEndpoint: String = "https://ca.42nice.fr/api",
    val caUsername: String = "",
    val caPassword: String = "",
    val ftTokenUrl: String = "https://api.intra.42.fr/oauth/token",
    val ftEndpoint: String = "https://api.intra.42.fr/v2",
    val ftUid: String = "",
    val ftSecret: String = "",
    /** Whether to show the raw badge codes card on a scan result. */
    val displayDetailedScans: Boolean = false,
    /** `close[closer_id]` sent with every TIG — the staff member giving it. */
    val closerId: String = "",
    /** `filter[campus_id]` for the Cluster page's cluster/location lookups. */
    val campusId: String = "41",
) {
    val caConfigured: Boolean get() = caEndpoint.isNotBlank() && caUsername.isNotBlank() && caPassword.isNotBlank()
    val ftConfigured: Boolean get() = ftUid.isNotBlank() && ftSecret.isNotBlank()
    val tigConfigured: Boolean get() = ftConfigured && closerId.isNotBlank()
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val CA_ENDPOINT = stringPreferencesKey("ca_endpoint")
        val CA_USERNAME = stringPreferencesKey("ca_username")
        val CA_PASSWORD = stringPreferencesKey("ca_password")
        val FT_TOKEN_URL = stringPreferencesKey("ft_token_url")
        val FT_ENDPOINT = stringPreferencesKey("ft_endpoint")
        val FT_UID = stringPreferencesKey("ft_uid")
        val FT_SECRET = stringPreferencesKey("ft_secret")
        val DISPLAY_DETAILED = booleanPreferencesKey("display_detailed_scans")
        val CLOSER_ID = stringPreferencesKey("closer_id")
        val CAMPUS_ID = stringPreferencesKey("campus_id")
    }

    private val defaults = AppSettings()

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            caEndpoint = p[Keys.CA_ENDPOINT] ?: defaults.caEndpoint,
            caUsername = p[Keys.CA_USERNAME] ?: defaults.caUsername,
            caPassword = p[Keys.CA_PASSWORD] ?: defaults.caPassword,
            ftTokenUrl = p[Keys.FT_TOKEN_URL] ?: defaults.ftTokenUrl,
            ftEndpoint = p[Keys.FT_ENDPOINT] ?: defaults.ftEndpoint,
            ftUid = p[Keys.FT_UID] ?: defaults.ftUid,
            ftSecret = p[Keys.FT_SECRET] ?: defaults.ftSecret,
            displayDetailedScans = p[Keys.DISPLAY_DETAILED] ?: defaults.displayDetailedScans,
            closerId = p[Keys.CLOSER_ID] ?: defaults.closerId,
            campusId = p[Keys.CAMPUS_ID] ?: defaults.campusId,
        )
    }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.CA_ENDPOINT] = s.caEndpoint.trim()
            p[Keys.CA_USERNAME] = s.caUsername.trim()
            p[Keys.CA_PASSWORD] = s.caPassword
            p[Keys.FT_TOKEN_URL] = s.ftTokenUrl.trim()
            p[Keys.FT_ENDPOINT] = s.ftEndpoint.trim()
            p[Keys.FT_UID] = s.ftUid.trim()
            p[Keys.FT_SECRET] = s.ftSecret.trim()
            p[Keys.DISPLAY_DETAILED] = s.displayDetailedScans
            p[Keys.CLOSER_ID] = s.closerId.trim()
            p[Keys.CAMPUS_ID] = s.campusId.trim()
        }
    }
}
