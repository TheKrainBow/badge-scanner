package fr.fortytwo.badgescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.fortytwo.badgescanner.data.BlameStatus
import fr.fortytwo.badgescanner.data.ScanRecord
import fr.fortytwo.badgescanner.nfc.BadgeCodes
import fr.fortytwo.badgescanner.nfc.Wiegand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen scan result, themed with the user's coalition: the coalition
 * color fills the background and its logo sits faded behind the details.
 * Used both for a live scan and when reopening a history entry.
 */
@Composable
fun BadgeResultScreen(
    record: ScanRecord,
    actionLabel: String,
    onAction: () -> Unit,
    onReasonChange: (Long, String) -> Unit = { _, _ -> },
    onSetBlameStatus: (Long, BlameStatus, String?) -> Unit = { _, _, _ -> },
    onDelete: (ScanRecord) -> Unit = {},
    onAssociate: (ScanRecord) -> Unit = {},
    showDetails: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val coalitionColor = parseCoalitionColor(record.coalitionColor)
    val background = coalitionColor ?: MaterialTheme.colorScheme.background
    val onBackground = coalitionColor?.let { onColorFor(it) } ?: MaterialTheme.colorScheme.onBackground

    Box(modifier = modifier.fillMaxSize().background(background)) {
        // Coalition logo as a faded background watermark
        if (record.coalitionImageUrl != null) {
            AsyncImage(
                model = record.coalitionImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.18f,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
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
            verticalArrangement = Arrangement.Center,
        ) {
            if (record.photoUrl != null) {
                AsyncImage(
                    model = record.photoUrl,
                    contentDescription = record.login,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(onBackground.copy(alpha = 0.15f)),
                )
            } else {
                Icon(
                    imageVector = if (record.error == null) Icons.Filled.Person else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = onBackground,
                    modifier = Modifier.size(100.dp),
                )
            }

            Spacer(16.dp)

            if (record.login != null) {
                Text(
                    text = record.login,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                )
            }

            if (record.userType != null || record.coalitionName != null) {
                Spacer(8.dp)
                Text(
                    text = listOfNotNull(record.userType, record.coalitionName).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = onBackground.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (record.coalitionColor != null) {
                Spacer(8.dp)
                Text(
                    text = record.coalitionColor.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = onBackground.copy(alpha = 0.7f),
                )
            }

            if (record.error != null) {
                Spacer(8.dp)
                Text(
                    text = record.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(8.dp)
            Text(
                text = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = onBackground.copy(alpha = 0.7f),
            )

            if (record.isBlame) {
                Spacer(24.dp)
                BlameCard(
                    record = record,
                    onBackground = onBackground,
                    onReasonChange = onReasonChange,
                    onSetBlameStatus = onSetBlameStatus,
                    onDelete = onDelete,
                )
            } else if (record.login == null) {
                // Unrecognized badge: let the user link it to a student
                Spacer(24.dp)
                Button(
                    onClick = { onAssociate(record) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text("Associer à un étudiant")
                }
            }

            if (showDetails) {
                Spacer(24.dp)
                val codes: BadgeCodes? = runCatching { Wiegand.fromUidHex(record.uidHex) }.getOrNull()
                Surface(
                    color = onBackground.copy(alpha = 0.10f),
                    contentColor = onBackground,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CodeLine("UID", record.uidHex, onBackground)
                        CodeLine("Badge hex", record.mifareHex, onBackground)
                        CodeLine("Wiegand", record.wiegand, onBackground)
                        if (codes != null) {
                            CodeLine("Premium", codes.premium.toString(), onBackground)
                            CodeLine("FC / Card", "${codes.facilityCode} / ${codes.cardNumber}", onBackground)
                        }
                    }
                }
            }

            Spacer(24.dp)
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = onBackground,
                    contentColor = background,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun BlameCard(
    record: ScanRecord,
    onBackground: Color,
    onReasonChange: (Long, String) -> Unit,
    onSetBlameStatus: (Long, BlameStatus, String?) -> Unit,
    onDelete: (ScanRecord) -> Unit,
) {
    var reason by remember(record.timestamp) { mutableStateOf(record.reason ?: "") }
    var tigDuration by remember(record.timestamp) { mutableStateOf(record.tigDuration ?: "") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = onBackground,
        unfocusedTextColor = onBackground,
        focusedBorderColor = onBackground,
        unfocusedBorderColor = onBackground.copy(alpha = 0.5f),
        cursorColor = onBackground,
        focusedLabelColor = onBackground,
        unfocusedLabelColor = onBackground.copy(alpha = 0.7f),
    )
    Surface(
        color = onBackground.copy(alpha = 0.10f),
        contentColor = onBackground,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Blâme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = reason,
                onValueChange = {
                    reason = it
                    onReasonChange(record.timestamp, it)
                },
                label = { Text("Motif (e.g. Bouteille d'eau, écran)") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BlameStatus.entries.forEach { status ->
                    FilterChip(
                        selected = record.blameStatus == status,
                        onClick = {
                            onSetBlameStatus(
                                record.timestamp,
                                status,
                                if (status == BlameStatus.TIGED) tigDuration else null,
                            )
                        },
                        label = { Text(status.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = onBackground,
                            iconColor = onBackground,
                            selectedContainerColor = onBackground.copy(alpha = 0.25f),
                            selectedLabelColor = onBackground,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (record.blameStatus == BlameStatus.TIGED) {
                OutlinedTextField(
                    value = tigDuration,
                    onValueChange = {
                        tigDuration = it
                        onSetBlameStatus(record.timestamp, BlameStatus.TIGED, it)
                    },
                    label = { Text("Durée du TIG (e.g. 2h, 1 semaine)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedButton(
                onClick = { onDelete(record) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Text("Supprimer ce blâme")
            }
        }
    }
}

@Composable
private fun CodeLine(label: String, value: String, color: Color) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = color.copy(alpha = 0.7f), fontSize = 14.sp)
        Text(value, fontFamily = FontFamily.Monospace, color = color, fontSize = 14.sp)
    }
}

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(height))
}
