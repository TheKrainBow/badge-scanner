package fr.fortytwo.badgescanner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.fortytwo.badgescanner.api.ClusterInfo
import fr.fortytwo.badgescanner.api.ClusterLayout
import fr.fortytwo.badgescanner.api.ClusterSeat
import fr.fortytwo.badgescanner.scan.ScanViewModel.ClusterOccupant
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val SeatBackground = Color(0xFFE5E5E5)
private val SeatBorder = Color(0xFF7F7F7F)
private val RowLabelColor = Color(0xFF9E9E9E)
private val SeatNumberColor = Color(0xFFB2B2B2)
private val HighlightColor = Color(0xFFFFC107)

/**
 * The Cluster tab: one sub-tab per cluster room on the campus, each showing
 * a seat map (from the cluster's own SVG, see [fr.fortytwo.badgescanner.api.parseClusterSvg])
 * with the profile photo of whoever currently occupies a seat. Tapping an
 * occupied seat opens that student's own CA page.
 */
@Composable
fun ClusterScreen(
    clusters: List<ClusterInfo>,
    layouts: Map<Int, ClusterLayout>,
    occupants: Map<String, ClusterOccupant>,
    loading: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onOpenLogin: (String) -> Unit,
    // Host (e.g. "c1r3p6") to jump to and center on, requested from a user's
    // own page — null the rest of the time. Consumed once, then cleared.
    locateHost: String? = null,
    onLocateHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val index = selectedIndex.coerceIn(0, (clusters.size - 1).coerceAtLeast(0))

    // Jump to the sub-tab of the cluster the requested seat belongs to
    // (host "c1r3p6" -> cluster name "c1"); centering on the seat itself is
    // ClusterMap's job, once that cluster's map is actually composed.
    LaunchedEffect(locateHost, clusters) {
        val host = locateHost ?: return@LaunchedEffect
        val clusterName = host.substringBefore('r')
        val targetIndex = clusters.indexOfFirst { it.name.equals(clusterName, ignoreCase = true) }
        if (targetIndex >= 0) selectedIndex = targetIndex
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (clusters.isNotEmpty()) {
            ScrollableTabRow(selectedTabIndex = index, edgePadding = 12.dp) {
                clusters.forEachIndexed { i, cluster ->
                    Tab(
                        selected = index == i,
                        onClick = { selectedIndex = i },
                        text = { Text(cluster.name.uppercase()) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${occupants.size} en ligne",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Rafraîchir")
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val cluster = clusters.getOrNull(index)
        val layout = cluster?.let { layouts[it.id] }
        if (cluster == null || layout == null) {
            if (!loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucun cluster — vérifie le Campus ID dans Réglages, ou rafraîchis",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            ClusterMap(
                layout = layout,
                occupants = occupants,
                onOpenLogin = onOpenLogin,
                locateHost = locateHost,
                onLocateHandled = onLocateHandled,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

/**
 * Renders the seat map fit to the available width at zoom 1, then lets the
 * user pinch-zoom (anchored on the pinch centroid) and drag to pan around,
 * with inertia on release. Panning also covers what a plain vertical scroll
 * used to do (the map is usually taller than the viewport).
 *
 * Taps and pan/zoom are detected by a single custom gesture handler
 * ([detectSeatGestures]) instead of per-seat `clickable` + a separate
 * `transformable` modifier — with two independent gesture detectors,
 * starting a pinch with one finger already down on a seat's photo let the
 * seat's own tap detector steal that first pointer, breaking the pinch.
 */
@Composable
private fun ClusterMap(
    layout: ClusterLayout,
    occupants: Map<String, ClusterOccupant>,
    onOpenLogin: (String) -> Unit,
    locateHost: String? = null,
    onLocateHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Keyed on the layout so switching cluster tabs resets zoom/pan, but a
    // plain occupants refresh (same layout instance) leaves it untouched.
    var zoom by remember(layout) { mutableFloatStateOf(1f) }
    var pan by remember(layout) { mutableStateOf(Offset.Zero) }
    var flingJob by remember(layout) { mutableStateOf<Job?>(null) }
    var highlightedHost by remember(layout) { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val fitScale = maxWidth.value / layout.viewBoxWidth
        val baseHeight = (layout.viewBoxHeight * fitScale).dp

        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val baseHeightPx = with(density) { baseHeight.toPx() }

        fun panBounds(z: Float): Pair<Offset, Offset> {
            val minX = (viewportWidthPx - viewportWidthPx * z).coerceAtMost(0f)
            val minY = (viewportHeightPx - baseHeightPx * z).coerceAtMost(0f)
            return Offset(minX, minY) to Offset(0f, 0f)
        }

        fun clamp(target: Offset, z: Float): Offset {
            val (lower, upper) = panBounds(z)
            return Offset(target.x.coerceIn(lower.x, upper.x), target.y.coerceIn(lower.y, upper.y))
        }

        fun findSeatAt(position: Offset): ClusterSeat? {
            // position is in viewport px; undo pan+zoom, then px->dp->svg units.
            val contentPx = (position - pan) / zoom
            val contentDp = with(density) { Offset(contentPx.x.toDp().value, contentPx.y.toDp().value) }
            val svgX = contentDp.x / fitScale
            val svgY = contentDp.y / fitScale
            return layout.seats.firstOrNull { s ->
                svgX in s.x..(s.x + s.width) && svgY in s.y..(s.y + s.height)
            }
        }

        // Center + zoom on the requested seat once it's known to belong to
        // this cluster's layout (ClusterScreen already switched to the
        // right sub-tab by the time this composes for that cluster).
        LaunchedEffect(locateHost, layout) {
            val host = locateHost ?: return@LaunchedEffect
            val seat = layout.seats.firstOrNull { it.host == host } ?: return@LaunchedEffect
            flingJob?.cancel()
            val targetZoom = 3f
            val seatCenterPx = with(density) {
                Offset(
                    ((seat.x + seat.width / 2f) * fitScale).dp.toPx(),
                    ((seat.y + seat.height / 2f) * fitScale).dp.toPx(),
                )
            }
            zoom = targetZoom
            pan = clamp(
                Offset(
                    viewportWidthPx / 2f - targetZoom * seatCenterPx.x,
                    viewportHeightPx / 2f - targetZoom * seatCenterPx.y,
                ),
                targetZoom,
            )
            highlightedHost = host
            onLocateHandled()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(layout) {
                    // Gesture callbacks below must stay non-suspend: the
                    // pointer-event loop they run in is a restricted
                    // coroutine that can't call arbitrary suspend functions
                    // (Animatable.snapTo included) — only plain state writes
                    // and non-suspend calls like Job.cancel().
                    detectSeatGestures(
                        onDown = { flingJob?.cancel() },
                        onTap = { position ->
                            findSeatAt(position)?.let { occupants[it.host] }?.let { onOpenLogin(it.login) }
                        },
                        onTransform = { centroid, panChange, zoomChange ->
                            val oldZoom = zoom
                            val newZoom = (oldZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            // Anchor the zoom on the pinch centroid: the content
                            // point currently under the fingers stays under them.
                            val focal = (centroid - pan) / oldZoom
                            val newPan = centroid + panChange - focal * newZoom
                            zoom = newZoom
                            pan = clamp(newPan, newZoom)
                        },
                        onTransformEnd = { velocity ->
                            flingJob?.cancel()
                            flingJob = scope.launch {
                                val anim = Animatable(pan, Offset.VectorConverter)
                                val (lower, upper) = panBounds(zoom)
                                anim.updateBounds(lower, upper)
                                anim.animateDecay(velocity, exponentialDecay()) { pan = value }
                            }
                        },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(baseHeight)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                layout.rowLabels.forEach { label ->
                    val labelHeight = (20 * fitScale).dp
                    Text(
                        text = label.text,
                        fontSize = (20 * fitScale).sp,
                        fontWeight = FontWeight.Bold,
                        color = RowLabelColor,
                        modifier = Modifier.offset(
                            x = (label.x * fitScale).dp,
                            y = (label.y * fitScale).dp - labelHeight,
                        ),
                    )
                }
                layout.seats.forEach { seat ->
                    val occupant = occupants[seat.host]
                    val isHighlighted = seat.host == highlightedHost
                    val borderColor = if (isHighlighted) HighlightColor else SeatBorder
                    // This border gets scaled up by the map's own zoom
                    // (graphicsLayer wraps the whole seat grid), so even a
                    // small dp value here reads as a thick ring once zoomed
                    // in on a located seat — keep it barely above the
                    // default seat outline.
                    val borderWidth = if (isHighlighted) 1.5.dp else 1.dp
                    val seatModifier = Modifier
                        .offset(x = (seat.x * fitScale).dp, y = (seat.y * fitScale).dp)
                        .size((seat.width * fitScale).dp, (seat.height * fitScale).dp)
                    if (occupant != null) {
                        AsyncImage(
                            model = occupant.photoUrl,
                            contentDescription = occupant.login,
                            contentScale = ContentScale.Crop,
                            modifier = seatModifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(SeatBackground)
                                .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(2.dp)),
                        )
                    } else {
                        Box(
                            modifier = seatModifier
                                .background(SeatBackground)
                                .border(BorderStroke(borderWidth, borderColor)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = seat.host.substringAfterLast('p').padStart(2, '0'),
                                fontSize = (10 * fitScale).sp,
                                color = SeatNumberColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single gesture detector handling both taps and pan/zoom, so a pinch
 * starting on top of a seat (its own tap target) isn't stolen by a separate
 * click detector — see [ClusterMap]'s kdoc.
 */
private suspend fun PointerInputScope.detectSeatGestures(
    onDown: () -> Unit,
    onTap: (Offset) -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onTransformEnd: (velocity: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDown()
        val velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, down.position)
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        while (true) {
            val event = awaitPointerEvent()
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()

            if (!pastTouchSlop &&
                (zoomChange != 1f || panChange.getDistance() > touchSlop || event.changes.size > 1)
            ) {
                pastTouchSlop = true
            }

            if (pastTouchSlop) {
                val centroid = event.calculateCentroid(useCurrent = false)
                if (centroid.isSpecified) {
                    onTransform(centroid, panChange, zoomChange)
                }
                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
            }

            event.changes.firstOrNull { it.id == down.id }?.let {
                velocityTracker.addPosition(it.uptimeMillis, it.position)
            }

            if (event.changes.none { it.pressed }) break
        }

        if (pastTouchSlop) {
            val velocity = velocityTracker.calculateVelocity()
            onTransformEnd(Offset(velocity.x, velocity.y))
        } else {
            onTap(down.position)
        }
    }
}
