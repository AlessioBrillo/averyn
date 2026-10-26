package dev.averyn.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.analysis.HeartRateZones
import dev.averyn.client.AnalysisState
import dev.averyn.client.ApiFailureKind
import dev.averyn.client.DetailSnapshot
import dev.averyn.client.DeviceActivityItem
import dev.averyn.client.HistoryItem
import dev.averyn.client.HistorySnapshot
import dev.averyn.client.HistorySource
import dev.averyn.client.HistorySyncState
import dev.averyn.client.UnitFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val HISTORY_LIMIT = 20

private enum class ExportKind { GPX, DIAGNOSTICS, RAW }

/**
 * The history of the account (TDD-0011): what the server has, what this device has, and what can be read
 * without a connection.
 *
 * The list is shown from the cache first and refreshed afterwards: the user sees something at once, and the
 * screen says when what it shows is a copy.
 */
@Composable
internal fun HistorySection(
    app: AverynApplication,
    refreshKey: Any?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<HistorySnapshot?>(null) }
    var detail by remember { mutableStateOf<DetailSnapshot?>(null) }
    var deviceData by remember { mutableStateOf<List<DeviceActivityItem>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<DeviceActivityItem?>(null) }
    val format = remember(app.units) { UnitFormat(app.units) }

    /** The token of the session, or null when there is none (the list then shows the cache). */
    suspend fun token(): String? = runCatching { app.auth.freshAccessToken() }.getOrNull()

    suspend fun reload() {
        val repository = app.history()
        val accessToken = token()
        snapshot = repository.refresh(accessToken)
        deviceData = repository.deviceData()
        // The unit system of the profile, remembered on the device for the next time without a connection.
        app.units = repository.refreshProfile(accessToken).units
    }

    LaunchedEffect(refreshKey) {
        busy = true
        val repository = app.history()
        // What is on the device comes first: the list is there at once, the server fills it in.
        snapshot = repository.loadCached()
        deviceData = repository.deviceData()
        app.units = repository.cachedUnits()
        runCatching { reload() }
        busy = false
    }

    fun launcherResult(kind: ExportKind): (android.net.Uri?) -> Unit =
        { uri ->
            val id = pending
            pending = null
            if (uri != null && id != null) {
                scope.launch(Dispatchers.IO) {
                    val result =
                        runCatching {
                            when (kind) {
                                ExportKind.GPX -> exportGpx(context, app.store, id, uri)
                                ExportKind.DIAGNOSTICS -> exportDiagnostics(context, app.store, id, uri)
                                ExportKind.RAW -> exportRaw(context, app.store, id, uri)
                            }
                        }
                    message =
                        result.fold(
                            onSuccess = { exported ->
                                when {
                                    !exported.written -> "Export failed"
                                    exported.skippedRecords > 0 ->
                                        "Exported; ${exported.skippedRecords} damaged record(s) were left out"
                                    else -> "Exported"
                                }
                            },
                            onFailure = { "Export failed" },
                        )
                }
            }
        }

    val gpx =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/gpx+xml"),
            launcherResult(ExportKind.GPX),
        )
    val diagnostics =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
            launcherResult(ExportKind.DIAGNOSTICS),
        )
    val raw =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-ndjson"),
            launcherResult(ExportKind.RAW),
        )

    /** Opens an activity: its numbers first, its analysis right after. */
    fun openDetail(item: HistoryItem) {
        val serverId = item.serverId ?: return
        scope.launch {
            busy = true
            selectedId = serverId
            detail = null
            val repository = app.history()
            val accessToken = token()
            detail = repository.refreshDetail(serverId, accessToken)
            // The server computes the analysis on the first read: it may answer "in calcolo".
            var current = repository.refreshAnalysis(serverId, accessToken)
            var attempts = 0
            while (current.analysisState == AnalysisState.COMPUTING && attempts < ANALYSIS_RETRIES) {
                val wait = current.failure?.retryAfterSeconds ?: 5
                delay(wait * 1000)
                current = repository.refreshAnalysis(serverId, accessToken)
                attempts++
            }
            detail = current
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("History")
        snapshot?.let { current ->
            if (current.fromCache) {
                Text(
                    "Offline copy" +
                        (current.cachedAtMs?.let { " of ${formatDate(it)}" } ?: "") +
                        " — the server could not be reached.",
                )
            }
            if (current.needsLogin) Text("Sign in to read the activities of the account.")
            current.failure?.let { failure ->
                if (failure.kind == ApiFailureKind.UNAUTHORIZED) {
                    Text("The session expired: sign in again.")
                }
            }
        }
        val rows = snapshot?.items.orEmpty()
        if (rows.isEmpty() && !busy) Text("No activities yet")
        rows.forEach { item ->
            HistoryRowView(
                item = item,
                format = format,
                selected = item.serverId != null && item.serverId == selectedId,
                onOpen = { openDetail(item) },
            )
        }
        snapshot?.nextCursor?.let { cursor ->
            Button(onClick = {
                scope.launch {
                    busy = true
                    snapshot = app.history().loadMore(token(), cursor)
                    busy = false
                }
            }) { Text("Load more") }
        }
        detail?.let { ActivityDetailView(it, format, app.auth.mapStyleUrl) }
        if (deviceData.isNotEmpty()) {
            Text("On this device")
            deviceData.forEach { item ->
                DeviceRowView(
                    item = item,
                    format = format,
                    onExport = { kind ->
                        pending = item.activityId
                        when (kind) {
                            ExportKind.GPX -> gpx.launch("${item.activityId}.gpx")
                            ExportKind.DIAGNOSTICS -> diagnostics.launch("${item.activityId}-diagnostics.json")
                            ExportKind.RAW -> raw.launch("${item.activityId}.jsonl")
                        }
                    },
                    onDelete = { toDelete = item },
                )
            }
        }
        message?.let { Text(it) }
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete from this device?") },
            text = {
                Text(
                    "The files of this activity are removed from this device. " +
                        "What the server has is not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        app.history().deleteLocal(item.activityId)
                        deviceData = app.history().deviceData()
                        message = "Deleted from this device"
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

/** One row of the list: the numbers of the activity and where they come from. */
@Composable
private fun HistoryRowView(
    item: HistoryItem,
    format: UnitFormat,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = item.serverId != null) { onOpen() }
                .padding(vertical = 4.dp),
    ) {
        Text(
            "${item.sport} ${formatDate(item.startedAtMs)}" +
                if (selected) " (open)" else "",
        )
        Text("${format.distance(item.distanceM)} — ${formatDuration(item.elapsedMs)}")
        val pace = item.paceSecPerKm?.let { format.pace(it) }
        val hr = if (item.avgHrBpm > 0) "HR ${item.avgHrBpm}/${item.maxHrBpm} bpm" else null
        val badges =
            listOfNotNull(
                when (item.source) {
                    HistorySource.DEVICE -> null
                    HistorySource.GPX -> "imported"
                    HistorySource.LOCAL -> "on this device"
                },
                item.syncState?.let { syncLabel(it) },
                if (item.localEstimate) "local estimate" else null,
            )
        Text(listOfNotNull(pace, hr).joinToString(" · "))
        if (badges.isNotEmpty()) Text(badges.joinToString(" · "))
    }
}

/** The numbers and the analysis of one activity. */
@Composable
private fun ActivityDetailView(
    detail: DetailSnapshot,
    format: UnitFormat,
    mapStyleUrl: String?,
) {
    val summary = detail.summary
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Activity")
        if (detail.deletedOnServer) {
            Text("The server does not have this activity any more. The file on this device is untouched.")
            return@Column
        }
        summary?.let {
            Text("${format.distance(it.distanceM)} — ${formatDuration(it.elapsedMs)}")
            Text("Moving ${formatDuration(it.movingMs)} · Paused ${formatDuration(it.pausedMs)}")
            it.paceSecPerKm?.let { pace -> Text("Pace ${format.pace(pace)}") }
            if (it.avgHrBpm > 0) Text("HR ${it.avgHrBpm}/${it.maxHrBpm} bpm (${it.hrSource})")
            Text("Quality ${it.qualityGrade} · ${it.metricsAlgorithmVersion}")
        }
        // The track on a map (TDD-0012): from the copy of the server, so it is there offline too.
        detail.track?.let { track ->
            if (mapStyleUrl != null) {
                TrackMapView(track, mapStyleUrl)
            } else {
                Text("The map needs the map style of the server: it is read when the app reaches the server.")
            }
        }
        when (detail.analysisState) {
            AnalysisState.READY -> detail.analysis?.let { AnalysisView(it, format) }
            AnalysisState.COMPUTING -> Text("The analysis is being computed…")
            AnalysisState.NOT_CACHED -> Text("The analysis is not on this device.")
            AnalysisState.NOT_SYNCED -> Text("The analysis comes with the upload of the activity.")
            AnalysisState.UNAVAILABLE -> Text("There is no analysis for this activity.")
        }
        if (detail.fromCache) {
            Text("Offline copy" + (detail.cachedAtMs?.let { " of ${formatDate(it)}" } ?: ""))
        }
    }
}

/** The splits and the two curves of the analysis (TDD-0009): the numbers, not a map. */
@Composable
private fun AnalysisView(
    analysis: ActivityAnalysis,
    format: UnitFormat,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (analysis.splits.isNotEmpty()) {
            Text("Splits")
            analysis.splits.forEach { split ->
                Text(
                    "#${split.index} ${format.distance(split.endDistanceM - split.startDistanceM)} — " +
                        "${formatDuration(split.movingMs)}" +
                        (split.avgHrBpm?.let { " — $it bpm" } ?: "") +
                        if (split.partial) " (partial)" else "",
                )
            }
        }
        analysis.elevation?.let { elevation ->
            Text(
                "Elevation: ${format.elevation(
                    elevation.gainM,
                    signed = true,
                )} / -${format.elevation(elevation.lossM)}",
            )
        }
        val series = analysis.series
        val elevationPoints = series.elevationM.filterNotNull()
        if (elevationPoints.size > 1) {
            Text(
                "Elevation profile (${format.elevation(elevationPoints.min())}" +
                    "–${format.elevation(elevationPoints.max())})",
            )
            SeriesChart(elevationPoints.map { it.toFloat() }, series.segment)
        }
        val heartRate = series.heartRate
        if (heartRate.bpm.size > 1) {
            Text("Heart rate (${heartRate.bpm.min()}–${heartRate.bpm.max()} bpm)")
            SeriesChart(heartRate.bpm.map { it.toFloat() }, heartRate.segment)
            ZonesView(analysis.heartRateZones)
        }
    }
}

/**
 * The time in each heart-rate zone (`hr-zones-v1`, TDD-0012), or the reason there is none: the zones are cut from
 * the maximum heart rate of the profile, so without one the screen says where to set it.
 */
@Composable
private fun ZonesView(zones: HeartRateZones?) {
    if (zones == null) {
        Text("Set your maximum heart rate in the account to see the time in each zone.")
        return
    }
    Text("Time in each zone (maximum ${zones.maxHrBpm} bpm)")
    zones.zones.forEach { zone ->
        val range = zone.toBpm?.let { "${zone.fromBpm}–$it" } ?: "${zone.fromBpm}+"
        Text("Z${zone.zone} $range bpm — ${formatDuration(zone.timeMs)}")
    }
}

/**
 * One curve of the analysis: the points of a series, with the pauses as breaks (TDD-0009).
 *
 * A drawing, not a measurement: the numbers above are the ones to trust.
 */
@Composable
private fun SeriesChart(
    values: List<Float>,
    segments: List<Int>,
) {
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(80.dp),
    ) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1).toFloat()
        val path = Path()
        var started = false
        values.forEachIndexed { index, value ->
            val segment = segments.getOrElse(index) { segments.lastOrNull() ?: 0 }
            val previousSegment = segments.getOrElse(index - 1) { segment }
            val x = index * stepX
            val y = size.height - ((value - min) / span) * size.height
            if (!started || segment != previousSegment) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(
            path,
            color =
                androidx.compose.ui.graphics
                    .Color(0xFF2E7D32),
            style = Stroke(width = 2f),
        )
    }
}

/** One activity stored on this device: its files, its state and the way to get rid of them. */
@Composable
private fun DeviceRowView(
    item: DeviceActivityItem,
    format: UnitFormat,
    onExport: (ExportKind) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Text("${item.sport} ${formatDate(item.startedAtMs)} — ${syncLabel(item.syncState)}")
        item.summary?.let {
            Text("${format.distance(it.distanceM)} — ${formatDuration(it.elapsedMs)}")
        }
        if (item.deletedOnServer) Text("The server does not have it any more.")
        Text("${item.localBytes / 1024} kB on this device")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onExport(ExportKind.GPX) }) { Text("GPX") }
            Button(onClick = { onExport(ExportKind.DIAGNOSTICS) }) { Text("Diagnostics") }
            Button(onClick = { onExport(ExportKind.RAW) }) { Text("Raw file") }
            Button(onClick = onDelete) { Text("Delete") }
        }
    }
}

private fun syncLabel(state: HistorySyncState): String =
    when (state) {
        HistorySyncState.LOCAL_ONLY -> "recording"
        HistorySyncState.QUEUED -> "waiting to upload"
        HistorySyncState.UPLOADING -> "uploading"
        HistorySyncState.SYNCED -> "on the server"
        HistorySyncState.FAILED_RETRYABLE -> "upload failed, will retry"
        HistorySyncState.FAILED_PERMANENT -> "the server refused it"
        HistorySyncState.DELETED_ON_SERVER -> "deleted on the server"
    }

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** How many times the app comes back for an analysis the server is still computing. */
private const val ANALYSIS_RETRIES = 3
