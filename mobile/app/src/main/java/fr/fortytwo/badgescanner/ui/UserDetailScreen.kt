package fr.fortytwo.badgescanner.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.fortytwo.badgescanner.data.BlameStatus
import fr.fortytwo.badgescanner.scan.ScanViewModel.UserDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PendingOrange = Color(0xFFFF9800)
private val PardonedBlue = Color(0xFF64B5F6)
private val TigedGreen = Color(0xFF4CAF50)

private fun blameStatusColor(status: BlameStatus): Color = when (status) {
    BlameStatus.NOT_HANDLED -> PendingOrange
    BlameStatus.PARDONED -> PardonedBlue
    BlameStatus.TIGED -> TigedGreen
}

/**
 * Full-screen CA user detail: intra identity themed with the coalition color,
 * plus every phone scan of that person's badge (a scan = a "bêtise").
 */
@Composable
fun UserDetailScreen(
    detail: UserDetail,
    onClose: () -> Unit,
    onOpenScan: (fr.fortytwo.badgescanner.data.ScanRecord) -> Unit,
    onDeleteUser: () -> Unit,
    onBlame: () -> Unit,
    onGivePoints: (Int, String) -> Unit,
    coalitionActionMessage: String?,
    coalitionActionSending: Boolean,
    coalitionActionSuccessTick: Long,
    onGiveTig: (Int, String) -> Unit,
    tigActionMessage: String?,
    tigEnabled: Boolean,
    onRefetchCa: () -> Unit,
    onRefetchProfiles: () -> Unit,
    onRefetchCoalitions: () -> Unit,
    onLocateOnCluster: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coalitionColor = parseCoalitionColor(detail.coalitionColor)
    val background = coalitionColor ?: MaterialTheme.colorScheme.background
    val onBackground = coalitionColor?.let { onColorFor(it) } ?: MaterialTheme.colorScheme.onBackground
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        if (detail.coalitionImageUrl != null) {
            AsyncImage(
                model = detail.coalitionImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.15f,
                modifier = Modifier.fillMaxSize().padding(56.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // Photo on the left, identity/status info on the right — saves
            // the vertical space the old fully-centered stacked layout used.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Opens the intra profile page — same login-driven URL 42
                // itself uses.
                val openIntraProfile: () -> Unit = {
                    val login = detail.login
                    if (login != null) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://profile.intra.42.fr/users/$login")),
                        )
                    }
                }

                if (detail.photoUrl != null) {
                    AsyncImage(
                        model = detail.photoUrl,
                        contentDescription = detail.login,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(onBackground.copy(alpha = 0.15f))
                            .clickable(enabled = detail.login != null, onClick = openIntraProfile),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(onBackground.copy(alpha = 0.15f))
                            .clickable(enabled = detail.login != null, onClick = openIntraProfile),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (detail.loading) {
                            CircularProgressIndicator(color = onBackground)
                        } else {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = onBackground,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.login ?: detail.entry.fullName.ifBlank { "user ${detail.entry.pk}" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onBackground,
                        modifier = detail.login?.let { login ->
                            Modifier.clickable {
                                clipboard.setText(AnnotatedString(login))
                                Toast.makeText(context, "Login copié : $login", Toast.LENGTH_SHORT).show()
                            }
                        } ?: Modifier,
                    )

                    val subtitle = listOfNotNull(detail.userType, detail.coalitionName).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onBackground.copy(alpha = 0.85f),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (detail.entry.fullName.isNotBlank() && detail.login != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = detail.entry.fullName,
                            style = MaterialTheme.typography.bodySmall,
                            color = onBackground.copy(alpha = 0.7f),
                        )
                    }
                    if (detail.ftId != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "ID 42 : ${detail.ftId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = onBackground.copy(alpha = 0.65f),
                        )
                    }
                    if (!detail.loading) {
                        val statusLine = listOfNotNull(
                            detail.level?.let { "Niveau %.2f".format(it) },
                            detail.location?.let { "En ligne · $it" } ?: "Hors ligne",
                        ).joinToString(" · ")
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = onBackground.copy(alpha = 0.65f),
                            modifier = detail.location?.let { host ->
                                Modifier.clickable { onLocateOnCluster(host) }
                            } ?: Modifier,
                        )
                    }
                    if (detail.currentProjects.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Projet(s) en cours : ${detail.currentProjects.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = onBackground.copy(alpha = 0.65f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            TigCoalitionCard(
                onBackground = onBackground,
                tigEnabled = tigEnabled && detail.ftId != null,
                tigMessage = tigActionMessage,
                onGiveTig = onGiveTig,
                // coalitionsUserId is resolved lazily on submit now (see
                // ScanViewModel.giveCoalitionPoints) — requiring it here too
                // hid the inputs behind "Coalition inconnue" even when the
                // coalition was already known and displayed above.
                coalitionEnabled = detail.coalitionId != null && detail.login != null,
                coalitionSending = coalitionActionSending,
                coalitionSuccessTick = coalitionActionSuccessTick,
                coalitionMessage = coalitionActionMessage,
                onGivePoints = onGivePoints,
            )

            Spacer(Modifier.height(16.dp))
            ScanCountCard(detail, onBackground, onOpenScan, onBlame)

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Supprimer l'utilisateur")
            }
        }

        // Composed last so it sits above the scrollable content and stays clickable
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = onBackground)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = onBackground)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Refetch CA") },
                    onClick = { menuExpanded = false; onRefetchCa() },
                )
                DropdownMenuItem(
                    text = { Text("Refetch 42 User") },
                    onClick = { menuExpanded = false; onRefetchProfiles() },
                )
                DropdownMenuItem(
                    text = { Text("Refetch 42 Coalitions") },
                    onClick = { menuExpanded = false; onRefetchCoalitions() },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Supprimer cet utilisateur ?") },
            text = {
                Text(
                    "${detail.login ?: detail.entry.fullName} sera retiré de la liste et tout son " +
                        "historique de scans sera supprimé. Il réapparaîtra si vous refaites un " +
                        "\"Refetch CA users\" et qu'il est toujours présent sur la CA.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDeleteUser() }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Annuler") }
            },
        )
    }
}

/**
 * "TIG" and "Coalition" used to be (or, for coalition, briefly were) two
 * separate full-width cards stacked on top of each other; they're both
 * short one-off actions on this person, so a tab switcher between them
 * saves a card's worth of vertical space.
 */
@Composable
private fun TigCoalitionCard(
    onBackground: Color,
    tigEnabled: Boolean,
    tigMessage: String?,
    onGiveTig: (Int, String) -> Unit,
    coalitionEnabled: Boolean,
    coalitionSending: Boolean,
    coalitionSuccessTick: Long,
    coalitionMessage: String?,
    onGivePoints: (Int, String) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val chipColors = FilterChipDefaults.filterChipColors(
        labelColor = onBackground,
        iconColor = onBackground,
        selectedContainerColor = onBackground.copy(alpha = 0.25f),
        selectedLabelColor = onBackground,
    )

    Surface(
        color = onBackground.copy(alpha = 0.10f),
        contentColor = onBackground,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text("TIG") },
                    colors = chipColors,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text("Coalition") },
                    colors = chipColors,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                TigFields(
                    onBackground = onBackground,
                    enabled = tigEnabled,
                    message = tigMessage,
                    onSubmit = onGiveTig,
                )
            } else {
                CoalitionPointsFields(
                    onBackground = onBackground,
                    enabled = coalitionEnabled,
                    sending = coalitionSending,
                    successTick = coalitionSuccessTick,
                    message = coalitionMessage,
                    onSubmit = onGivePoints,
                )
            }
        }
    }
}

@Composable
private fun CoalitionPointsFields(
    onBackground: Color,
    enabled: Boolean,
    sending: Boolean,
    successTick: Long,
    message: String?,
    onSubmit: (Int, String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    // Clear the inputs only once the POST actually succeeds (successTick
    // bumps) — not just on submit, so a failed request leaves what the user
    // typed in place instead of silently discarding it.
    var lastSuccessTick by remember { mutableStateOf(successTick) }
    LaunchedEffect(successTick) {
        if (successTick != lastSuccessTick) {
            reason = ""
            value = ""
        }
        lastSuccessTick = successTick
    }

    if (!enabled) {
        Text(
            "Coalition inconnue pour cet utilisateur",
            style = MaterialTheme.typography.bodySmall,
            color = onBackground.copy(alpha = 0.6f),
        )
        return
    }

    OutlinedTextField(
        value = reason,
        onValueChange = { reason = it },
        label = { Text("Raison") },
        singleLine = true,
        enabled = !sending,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = { new -> if (new.all { it.isDigit() || it == '-' }) value = new },
        label = { Text("Points (+/-)") },
        singleLine = true,
        enabled = !sending,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    val points = value.toIntOrNull()
    Button(
        onClick = {
            if (points != null && reason.isNotBlank()) {
                onSubmit(points, reason.trim())
            }
        },
        enabled = !sending && points != null && reason.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (sending) "Envoi…" else "Envoyer")
    }
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = onBackground.copy(alpha = 0.8f))
    }
}

private data class TigOption(val label: String, val durationSeconds: Int)

private val TigOptions = listOf(
    TigOption("2h", 7200),
    TigOption("4h", 14400),
    TigOption("8h", 28800),
)

@Composable
private fun TigFields(
    onBackground: Color,
    enabled: Boolean,
    message: String?,
    onSubmit: (Int, String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(TigOptions[0]) }
    var durationMenuExpanded by remember { mutableStateOf(false) }

    if (!enabled) {
        Text(
            "Utilisateur 42 inconnu, ou Closer ID non configuré (voir Réglages)",
            style = MaterialTheme.typography.bodySmall,
            color = onBackground.copy(alpha = 0.6f),
        )
        return
    }

    OutlinedTextField(
        value = reason,
        onValueChange = { reason = it },
        label = { Text("Raison") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { durationMenuExpanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Durée : ${selected.label}", modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = durationMenuExpanded,
            onDismissRequest = { durationMenuExpanded = false },
        ) {
            TigOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { selected = option; durationMenuExpanded = false },
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            if (reason.isNotBlank()) {
                onSubmit(selected.durationSeconds, reason.trim())
                reason = ""
            }
        },
        enabled = reason.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Envoyer")
    }
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = onBackground.copy(alpha = 0.8f))
    }
}

@Composable
private fun ScanCountCard(
    detail: UserDetail,
    onBackground: Color,
    onOpenScan: (fr.fortytwo.badgescanner.data.ScanRecord) -> Unit,
    onBlame: () -> Unit,
) {
    Surface(
        color = onBackground.copy(alpha = 0.10f),
        contentColor = onBackground,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (detail.scans.isEmpty()) {
                    "Never blamed"
                } else {
                    "Blamed ${detail.scans.size} time${if (detail.scans.size > 1) "s" else ""}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onBlame, modifier = Modifier.fillMaxWidth()) {
                Text("Blâmer")
            }
            if (detail.scans.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val fmt = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault())
                detail.scans.forEach { scan ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenScan(scan) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Status dot: orange = to handle, blue = pardoned, green = TIGed
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(blameStatusColor(scan.blameStatus)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(fmt.format(Date(scan.timestamp)), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    scan.wiegand,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = onBackground.copy(alpha = 0.7f),
                                )
                            }
                            Text(
                                text = listOfNotNull(
                                    scan.reason,
                                    scan.blameStatus.label +
                                        (if (scan.blameStatus == BlameStatus.TIGED && scan.tigDuration != null) {
                                            " (${scan.tigDuration})"
                                        } else ""),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = onBackground.copy(alpha = if (scan.reason != null) 0.9f else 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}
