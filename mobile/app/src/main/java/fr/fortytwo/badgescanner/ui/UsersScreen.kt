package fr.fortytwo.badgescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.fortytwo.badgescanner.api.CaDirEntry
import fr.fortytwo.badgescanner.scan.ScanViewModel.UserFilters
import fr.fortytwo.badgescanner.scan.ScanViewModel.UserOrder
import fr.fortytwo.badgescanner.scan.ScanViewModel.UserRow

@Composable
fun UsersScreen(
    users: List<UserRow>,
    filters: UserFilters,
    coalitions: List<String>,
    onQuery: (String) -> Unit,
    onType: (String?) -> Unit,
    onScannedOnly: (Boolean) -> Unit,
    onErrorOnly: (Boolean) -> Unit,
    onCoalition: (String?) -> Unit,
    onOrder: (UserOrder) -> Unit,
    onOpenUser: (CaDirEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = onQuery,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    }
                }
            },
            placeholder = { Text("Search login or name") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filters.type == "Student",
                onClick = { onType(if (filters.type == "Student") null else "Student") },
                label = { Text("Student") },
            )
            FilterChip(
                selected = filters.type == "Piscine",
                onClick = { onType(if (filters.type == "Piscine") null else "Piscine") },
                label = { Text("Piscine") },
            )
            FilterChip(
                selected = filters.scannedOnly,
                onClick = { onScannedOnly(!filters.scannedOnly) },
                label = { Text("Blamed") },
            )
            FilterChip(
                selected = filters.errorOnly,
                onClick = { onErrorOnly(!filters.errorOnly) },
                label = { Text("Error") },
            )
            DropdownChip(
                label = filters.coalition ?: "Coalition",
                selected = filters.coalition != null,
                options = listOf("All coalitions") + coalitions,
                onSelect = { index -> onCoalition(if (index == 0) null else coalitions[index - 1]) },
            )
            DropdownChip(
                label = "Sort: ${filters.order.label}",
                selected = filters.order != UserOrder.Alphabetical,
                options = UserOrder.entries.map { it.label },
                onSelect = { index -> onOrder(UserOrder.entries[index]) },
            )
        }

        Text(
            text = "${users.size} user${if (users.size > 1) "s" else ""}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        if (users.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No users — refetch CA users in Settings, or loosen the filters",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            val listState = rememberLazyListState()
            // Any filter/sort/search change scrolls the list back to the top
            LaunchedEffect(filters) { listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, bottom = 16.dp,
                ),
            ) {
                items(users, key = { it.entry.pk }) { row ->
                    UserListRow(row, onClick = { onOpenUser(row.entry) })
                }
            }
        }
    }
}

@Composable
private fun DropdownChip(
    label: String,
    selected: Boolean,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { open = false; onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun UserListRow(row: UserRow, onClick: () -> Unit) {
    val coalitionColor = parseCoalitionColor(row.coalitionColor)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatarModifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(
                    if (coalitionColor != null) Modifier.border(2.dp, coalitionColor, CircleShape)
                    else Modifier,
                )
            if (row.photoUrl != null) {
                AsyncImage(
                    model = row.photoUrl,
                    contentDescription = row.login,
                    contentScale = ContentScale.Crop,
                    modifier = avatarModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Box(
                    modifier = avatarModifier.background(
                        coalitionColor ?: MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (row.login ?: row.entry.fullName).take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (coalitionColor != null) onColorFor(coalitionColor)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.login ?: row.entry.fullName.ifBlank { "user ${row.entry.pk}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = listOfNotNull(row.userType, row.coalitionName).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = coalitionColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                } else if (row.entry.fullName.isNotBlank() && row.login != null) {
                    Text(
                        text = row.entry.fullName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (row.pendingCount > 0) {
                Badge { Text("${row.pendingCount}") }
            }
        }
    }
}
