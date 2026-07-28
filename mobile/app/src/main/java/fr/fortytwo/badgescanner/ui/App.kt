package fr.fortytwo.badgescanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.fortytwo.badgescanner.scan.ScanState
import fr.fortytwo.badgescanner.scan.ScanViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    Scan("Scan", Icons.Filled.Nfc),
    Users("Users", Icons.Filled.Groups),
    Cluster("Cluster", Icons.Filled.GridView),
    History("History", Icons.Filled.History),
    Settings("Settings", Icons.Filled.Settings),
}

private val FtGreen = Color(0xFF00BABC)

@Composable
fun App(viewModel: ScanViewModel, nfcAvailable: Boolean) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = FtGreen, secondary = FtGreen)
    } else {
        lightColorScheme(primary = Color(0xFF00888A), secondary = Color(0xFF00888A))
    }

    MaterialTheme(colorScheme = colorScheme) {
        var currentTab by rememberSaveable { mutableIntStateOf(0) }

        val scanState by viewModel.scanState.collectAsStateWithLifecycle()
        val appSettings by viewModel.settings.collectAsStateWithLifecycle()
        val historyTabIndex = Tab.entries.indexOf(Tab.History)
        val clusterTabIndex = Tab.entries.indexOf(Tab.Cluster)

        // A scan starting moves us to History (behind the result overlay), so
        // the Scan tab always stays on the idle "hold a badge" prompt.
        val scanActive = scanState !is ScanState.Idle
        LaunchedEffect(scanActive) { if (scanActive) currentTab = historyTabIndex }

      Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = currentTab == index,
                            onClick = { currentTab = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            val modifier = Modifier.padding(innerPadding)
            when (Tab.entries[currentTab]) {
                Tab.Scan -> ScanIdleScreen(nfcAvailable = nfcAvailable, modifier = modifier)
                Tab.Users -> {
                    val users by viewModel.userRows.collectAsStateWithLifecycle()
                    val filters by viewModel.userFilters.collectAsStateWithLifecycle()
                    val coalitions by viewModel.availableCoalitions.collectAsStateWithLifecycle()
                    UsersScreen(
                        users = users,
                        filters = filters,
                        coalitions = coalitions,
                        onQuery = viewModel::setUserQuery,
                        onType = viewModel::setUserType,
                        onScannedOnly = viewModel::setUserScannedOnly,
                        onErrorOnly = viewModel::setUserErrorOnly,
                        onCoalition = viewModel::setUserCoalition,
                        onOrder = viewModel::setUserOrder,
                        onOpenUser = viewModel::openUser,
                        modifier = modifier,
                    )
                }
                Tab.Cluster -> {
                    val clusters by viewModel.clusters.collectAsStateWithLifecycle()
                    val clusterLayouts by viewModel.clusterLayouts.collectAsStateWithLifecycle()
                    val clusterOccupants by viewModel.clusterOccupants.collectAsStateWithLifecycle()
                    val clusterLoading by viewModel.clusterLoading.collectAsStateWithLifecycle()
                    val clusterMessage by viewModel.clusterMessage.collectAsStateWithLifecycle()
                    val clusterLocateRequest by viewModel.clusterLocateRequest.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { viewModel.loadClusters() }
                    ClusterScreen(
                        clusters = clusters,
                        layouts = clusterLayouts,
                        occupants = clusterOccupants,
                        loading = clusterLoading,
                        message = clusterMessage,
                        onRefresh = { viewModel.loadClusters(force = true) },
                        onOpenLogin = viewModel::openUserByLogin,
                        locateHost = clusterLocateRequest,
                        onLocateHandled = viewModel::clearClusterLocateRequest,
                        modifier = modifier,
                    )
                }
                Tab.History -> {
                    val history by viewModel.history.collectAsStateWithLifecycle()
                    HistoryScreen(
                        history = history,
                        onClear = viewModel::clearHistory,
                        onDeleteEntry = viewModel::deleteHistoryEntry,
                        onOpenEntry = viewModel::openRecord,
                        modifier = modifier,
                    )
                }
                Tab.Settings -> {
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    val directoryInfo by viewModel.directoryInfo.collectAsStateWithLifecycle()
                    val directoryProgress by viewModel.directoryProgress.collectAsStateWithLifecycle()
                    val directoryMessage by viewModel.directoryMessage.collectAsStateWithLifecycle()
                    val profileProgress by viewModel.profileProgress.collectAsStateWithLifecycle()
                    val profileMessage by viewModel.profileMessage.collectAsStateWithLifecycle()
                    val coalitionProgress by viewModel.coalitionProgress.collectAsStateWithLifecycle()
                    val coalitionFetchMessage by viewModel.coalitionFetchMessage.collectAsStateWithLifecycle()
                    SettingsScreen(
                        settings = settings,
                        onSave = viewModel::saveSettings,
                        directoryInfo = directoryInfo,
                        directoryProgress = directoryProgress,
                        directoryMessage = directoryMessage,
                        onRefetchDirectory = viewModel::refetchCaDirectory,
                        profileProgress = profileProgress,
                        profileMessage = profileMessage,
                        onRefetchProfiles = viewModel::refetchUserProfiles,
                        coalitionProgress = coalitionProgress,
                        coalitionMessage = coalitionFetchMessage,
                        onRefetchCoalitions = viewModel::refetchCoalitions,
                        modifier = modifier,
                    )
                }
            }
        }

        // Selected CA user detail, full-screen over everything
        val selectedUser by viewModel.selectedUser.collectAsStateWithLifecycle()
        selectedUser?.let { detail ->
            val coalitionActionMessage by viewModel.coalitionActionMessage.collectAsStateWithLifecycle()
            val coalitionActionSending by viewModel.coalitionActionSending.collectAsStateWithLifecycle()
            val coalitionActionSuccessTick by viewModel.coalitionActionSuccessTick.collectAsStateWithLifecycle()
            val tigActionMessage by viewModel.tigActionMessage.collectAsStateWithLifecycle()
            UserDetailScreen(
                detail = detail,
                onClose = viewModel::closeUser,
                onOpenScan = viewModel::openRecord,
                onDeleteUser = { viewModel.deleteUser(detail.entry) },
                onBlame = { viewModel.addManualBlame(detail) },
                onGivePoints = { value, reason -> viewModel.giveCoalitionPoints(detail, value, reason) },
                coalitionActionMessage = coalitionActionMessage,
                coalitionActionSending = coalitionActionSending,
                coalitionActionSuccessTick = coalitionActionSuccessTick,
                onGiveTig = { duration, reason -> viewModel.giveTig(detail, duration, reason) },
                tigActionMessage = tigActionMessage,
                tigEnabled = appSettings.tigConfigured,
                onRefetchCa = viewModel::refetchCaDirectory,
                // Menu actions on a user's own page only refetch that user,
                // not the whole CA population.
                onRefetchProfiles = { viewModel.refetchCurrentUserProfile(detail.entry) },
                onRefetchCoalitions = { viewModel.refetchCurrentUserCoalition(detail.entry) },
                onLocateOnCluster = { host ->
                    viewModel.closeUser()
                    currentTab = clusterTabIndex
                    viewModel.locateOnCluster(host)
                },
            )
        }

        // A scan opened full-screen from history/user detail.
        val opened by viewModel.openedRecord.collectAsStateWithLifecycle()
        opened?.let { record ->
            BadgeResultScreen(
                record = record,
                actionLabel = "Close",
                onAction = viewModel::closeRecord,
                onReasonChange = viewModel::setScanReason,
                onSetBlameStatus = viewModel::setBlameStatus,
                onDelete = viewModel::deleteHistoryEntry,
                onAssociate = viewModel::startAssociation,
                // Unknown badges always show details
                showDetails = appSettings.displayDetailedScans || record.login == null,
            )
        }

        // Live scan progress/result — topmost overlay.
        if (scanState !is ScanState.Idle) {
            ScanOverlay(
                state = scanState,
                showDetailsSetting = appSettings.displayDetailedScans,
                onReset = viewModel::resetScan,
                onAssociate = viewModel::startAssociation,
            )
        }

        // Manual badge → student association (for unrecognized badges).
        val associating by viewModel.associating.collectAsStateWithLifecycle()
        associating?.let { record ->
            val candidates by viewModel.allUserRows.collectAsStateWithLifecycle()
            AssociateScreen(
                badgeHex = record.mifareHex,
                candidates = candidates,
                onPick = { login -> viewModel.associateBadge(record, login) },
                onCancel = viewModel::cancelAssociation,
            )
        }

        // Android back: close the topmost overlay first (live scan result,
        // reopened scan, user detail), then fall back to the Scan tab, so
        // back never quits the app straight from another screen.
        val scanResultShown = scanState is ScanState.Success || scanState is ScanState.Failure
        BackHandler(enabled = associating != null) { viewModel.cancelAssociation() }
        BackHandler(enabled = associating == null && scanResultShown) { viewModel.resetScan() }
        // Swallow back while a scan is still processing
        BackHandler(enabled = associating == null && scanActive && !scanResultShown) { }
        BackHandler(enabled = associating == null && !scanActive && opened != null) {
            viewModel.closeRecord()
        }
        BackHandler(enabled = associating == null && !scanActive && opened == null && selectedUser != null) {
            viewModel.closeUser()
        }
        BackHandler(
            enabled = associating == null && !scanActive && opened == null &&
                selectedUser == null && currentTab != 0,
        ) {
            currentTab = 0
        }
      }
    }
}
