package fr.fortytwo.badgescanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.fortytwo.badgescanner.api.CaDirectory
import fr.fortytwo.badgescanner.data.AppSettings
import fr.fortytwo.badgescanner.scan.ScanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    directoryInfo: CaDirectory.Info,
    directoryProgress: CaDirectory.Progress,
    directoryMessage: String?,
    onRefetchDirectory: () -> Unit,
    profileProgress: ScanViewModel.IntraRefetchProgress,
    profileMessage: String?,
    onRefetchProfiles: () -> Unit,
    coalitionProgress: ScanViewModel.IntraRefetchProgress,
    coalitionMessage: String?,
    onRefetchCoalitions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var caEndpoint by remember(settings) { mutableStateOf(settings.caEndpoint) }
    var caUsername by remember(settings) { mutableStateOf(settings.caUsername) }
    var caPassword by remember(settings) { mutableStateOf(settings.caPassword) }
    var ftTokenUrl by remember(settings) { mutableStateOf(settings.ftTokenUrl) }
    var ftEndpoint by remember(settings) { mutableStateOf(settings.ftEndpoint) }
    var ftUid by remember(settings) { mutableStateOf(settings.ftUid) }
    var ftSecret by remember(settings) { mutableStateOf(settings.ftSecret) }
    var displayDetailed by remember(settings) { mutableStateOf(settings.displayDetailedScans) }
    var closerId by remember(settings) { mutableStateOf(settings.closerId) }
    var campusId by remember(settings) { mutableStateOf(settings.campusId) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Access Control (CA)", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = caEndpoint, onValueChange = { caEndpoint = it; saved = false },
            label = { Text("CA endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = caUsername, onValueChange = { caUsername = it; saved = false },
            label = { Text("CA username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = caPassword, onValueChange = { caPassword = it; saved = false },
            label = { Text("CA password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${directoryInfo.userCount} users cached",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (directoryInfo.fetchedAt > 0) {
                        "Fetched " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                            .format(Date(directoryInfo.fetchedAt))
                    } else {
                        "Never fetched"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onRefetchDirectory,
                enabled = !directoryProgress.running && settings.caConfigured,
            ) {
                Text(if (directoryProgress.running) "Fetching…" else "Refetch CA users")
            }
        }

        if (directoryProgress.running) {
            val total = directoryProgress.total
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (directoryProgress.fetched.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Fetched ${directoryProgress.fetched} / $total users…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Fetched ${directoryProgress.fetched} users…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (directoryMessage != null) {
            Text(
                directoryMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (directoryMessage.startsWith("Fetched")) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("42 API", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = ftUid, onValueChange = { ftUid = it; saved = false },
            label = { Text("Application UID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ftSecret, onValueChange = { ftSecret = it; saved = false },
            label = { Text("Application secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = ftEndpoint, onValueChange = { ftEndpoint = it; saved = false },
            label = { Text("API endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ftTokenUrl, onValueChange = { ftTokenUrl = it; saved = false },
            label = { Text("Token URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        IntraRefetchRow(
            title = "Cache 42 profiles for every CA user",
            subtitle = "Login, photo, type, location, level, projects. Coalitions fetched separately below.",
            buttonLabel = "Fetch 42 users info",
            enabled = settings.ftConfigured,
            progress = profileProgress,
            message = profileMessage,
            onClick = onRefetchProfiles,
        )

        Spacer(Modifier.height(4.dp))
        IntraRefetchRow(
            title = "Cache coalitions for every CA user",
            subtitle = "Requires the profile fetch above to have run first (needs a resolved login).",
            buttonLabel = "Fetch coalitions",
            enabled = settings.ftConfigured,
            progress = coalitionProgress,
            message = coalitionMessage,
            onClick = onRefetchCoalitions,
        )

        Spacer(Modifier.height(8.dp))
        Text("TIG", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = closerId, onValueChange = { closerId = it; saved = false },
            label = { Text("Closer ID (ton ID intra)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Envoyé comme close[closer_id] à chaque TIG donné depuis l'application.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text("Cluster", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = campusId, onValueChange = { campusId = it; saved = false },
            label = { Text("Campus ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Utilisé pour filtrer les clusters et les postes occupés (filter[campus_id]).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text("Display", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Display detailed scans", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Show the raw badge codes (UID, hex, wiegand…). Unknown badges always show them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = displayDetailed,
                onCheckedChange = { displayDetailed = it; onSave(settings.copy(displayDetailedScans = it)) },
            )
        }

        Button(
            onClick = {
                onSave(
                    AppSettings(
                        caEndpoint = caEndpoint,
                        caUsername = caUsername,
                        caPassword = caPassword,
                        ftTokenUrl = ftTokenUrl,
                        ftEndpoint = ftEndpoint,
                        ftUid = ftUid,
                        ftSecret = ftSecret,
                        displayDetailedScans = displayDetailed,
                        closerId = closerId,
                        campusId = campusId,
                    )
                )
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved ✓" else "Save")
        }

        Text(
            "Credentials are stored on this device only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IntraRefetchRow(
    title: String,
    subtitle: String,
    buttonLabel: String,
    enabled: Boolean,
    progress: ScanViewModel.IntraRefetchProgress,
    message: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onClick, enabled = !progress.running && enabled) {
            Text(if (progress.running) "Fetching…" else buttonLabel)
        }
    }

    if (progress.running) {
        val total = progress.total
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (progress.fetched.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Fetched ${progress.fetched} / $total users…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        progress.currentLogin?.let { login ->
            Text(
                "Fetching $login…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (message != null) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (message.startsWith("Fetched")) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}
