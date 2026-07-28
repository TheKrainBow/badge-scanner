package fr.fortytwo.badgescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.fortytwo.badgescanner.nfc.BadgeCodes
import fr.fortytwo.badgescanner.scan.ScanState

/** The Scan tab: always the idle "hold a badge" prompt. Results/progress of a
 *  scan are shown as a full-screen overlay by [ScanOverlay]. */
@Composable
fun ScanIdleScreen(nfcAvailable: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Nfc,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = if (nfcAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (nfcAvailable) "Hold a student badge against the back of the phone"
            else "This device has no NFC hardware",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Full-screen overlay shown while a scan is processing and once it resolves.
 * Kept off the Scan tab so that tab always shows the idle prompt.
 */
@Composable
fun ScanOverlay(
    state: ScanState,
    showDetailsSetting: Boolean,
    onReset: () -> Unit,
    onAssociate: (fr.fortytwo.badgescanner.data.ScanRecord) -> Unit,
) {
    when (state) {
        // A successful scan opens the user's page directly (see
        // ScanViewModel.process) — Success is only ever reached here when a
        // manually-linked badge's student isn't in the local CA cache at all
        // (so there's no CA page to open).
        is ScanState.Success ->
            BadgeResultScreen(
                record = state.record,
                actionLabel = "Ready for next scan",
                onAction = onReset,
                onAssociate = onAssociate,
                // Unknown badges always show details
                showDetails = showDetailsSetting || state.record.login == null,
            )
        is ScanState.Failure ->
            BadgeResultScreen(
                record = state.record,
                actionLabel = "Ready for next scan",
                onAction = onReset,
                onAssociate = onAssociate,
                showDetails = showDetailsSetting || state.record.login == null,
            )
        is ScanState.Working ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(24.dp))
                Text(state.step, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (showDetailsSetting) {
                    Spacer(Modifier.height(24.dp))
                    CodesCard(state.codes)
                }
            }
        is ScanState.Idle -> Unit
    }
}

@Composable
fun CodesCard(codes: BadgeCodes, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CodeRow("UID (as read)", codes.uidHex)
            CodeRow("Badge hex", codes.mifareHex)
            CodeRow("Wiegand 26", codes.wiegand26)
            CodeRow("IXOFF", codes.wiegandUnpadded)
            CodeRow("Premium", codes.premium.toString())
            CodeRow("FC / Card", "${codes.facilityCode} / ${codes.cardNumber}")
        }
    }
}

@Composable
private fun CodeRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
