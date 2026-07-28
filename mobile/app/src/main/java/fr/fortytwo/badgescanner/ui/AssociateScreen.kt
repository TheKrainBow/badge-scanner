package fr.fortytwo.badgescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.fortytwo.badgescanner.scan.ScanViewModel.UserRow

/**
 * Full-screen picker to link an unrecognized badge to a student, searched by
 * login. Also allows typing a raw login if the person isn't in the directory.
 */
@Composable
fun AssociateScreen(
    badgeHex: String,
    candidates: List<UserRow>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()

    val filtered = remember(q, candidates) {
        if (q.isEmpty()) candidates.take(50)
        else candidates.filter { row ->
            (row.login?.lowercase()?.contains(q) == true) ||
                row.entry.fullName.lowercase().contains(q)
        }.take(50)
    }
    val exactExists = candidates.any { it.login.equals(query.trim(), ignoreCase = true) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel")
                }
                Text("Associate badge $badgeHex", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search a login") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, bottom = 16.dp,
                ),
            ) {
                // Free-text login option when it doesn't already match a row
                if (query.trim().isNotEmpty() && !exactExists) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(query.trim()) },
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text("Use login \"${query.trim().lowercase()}\"")
                            }
                        }
                    }
                }

                items(filtered, key = { it.entry.pk }) { row ->
                    CandidateRow(row, onClick = { onPick(row.login ?: row.entry.fullName) })
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(row: UserRow, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.photoUrl != null) {
                AsyncImage(
                    model = row.photoUrl,
                    contentDescription = row.login,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text((row.login ?: row.entry.fullName).take(1).uppercase())
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.login ?: row.entry.fullName.ifBlank { "user ${row.entry.pk}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (row.entry.fullName.isNotBlank() && row.login != null) {
                    Text(
                        text = row.entry.fullName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
