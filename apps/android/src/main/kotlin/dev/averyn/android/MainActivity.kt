package dev.averyn.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.averyn.domain.ActivityState
import dev.averyn.domain.Sport
import dev.averyn.sync.SyncStatusStore
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.DeviceInfo
import dev.averyn.tracking.DiagnosticsReport
import dev.averyn.tracking.LiveSnapshot
import dev.averyn.tracking.RecoveryResult
import dev.averyn.tracking.toJson
import dev.averyn.tracking.writeGpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.OutputStreamWriter
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RecordScreen(application as AverynApplication)
            }
        }
    }
}

private fun now() = SystemClock.elapsedRealtime() to System.currentTimeMillis()

@Composable
private fun RecordScreen(app: AverynApplication) {
    val context = LocalContext.current
    val recorder = app.recorder
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(recorder.snapshot()) }
    var pendingRecovery by remember { mutableStateOf<ActivityMetadata?>(null) }
    var exportActivityId by remember { mutableStateOf<String?>(null) }
    var completedDiagnostics by remember { mutableStateOf<DiagnosticsReport?>(null) }
    var pendingSport by remember { mutableStateOf(Sport.RUN) }
    // F5: a recovery that lost store lines is surfaced, never silent — see RecoveryResult.droppedRecordCount.
    var recoveryWarning by remember { mutableStateOf<String?>(null) }

    DisposableEffect(recorder) {
        recorder.listener = { snapshot = it }
        onDispose { recorder.listener = null }
    }
    // Only offered once, right after launch — this is TDD-0001 F4's "offered for resume or finish" flow.
    LaunchedEffect(Unit) {
        if (recorder.currentState == ActivityState.IDLE) pendingRecovery = app.store.interrupted().firstOrNull()
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording(context, app, pendingSport)
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            val id = exportActivityId
            if (uri != null && id != null) {
                // Streams and re-serializes every stored sample: real I/O, kept off the main thread.
                scope.launch(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        writeGpx(app.store, id, out.asSink().buffered())
                    }
                }
            }
            exportActivityId = null
        }
    val exportDiagnosticsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val diagnostics = completedDiagnostics
            if (uri != null && diagnostics != null) {
                val json = diagnostics.toJson(deviceInfo(context))
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    OutputStreamWriter(out).use { it.write(json) }
                }
            }
        }

    pendingRecovery?.let { metadata ->
        RecoveryDialog(
            metadata = metadata,
            onResume = {
                val (elapsedMs, timeMs) = now()
                when (val result = recorder.recover(metadata.activityId, elapsedMs, timeMs)) {
                    is RecoveryResult.Resumed -> {
                        snapshot = result.snapshot
                        recoveryWarning = droppedRecordWarning(result.droppedRecordCount)
                        context.startForegroundService(Intent(context, TrackingService::class.java))
                    }
                    is RecoveryResult.Completed -> {
                        exportActivityId = result.activity.activityId
                        completedDiagnostics = result.activity.diagnostics
                        recoveryWarning = droppedRecordWarning(result.droppedRecordCount)
                    }
                }
                pendingRecovery = null
            },
            onFinish = {
                val (elapsedMs, timeMs) = now()
                when (val result = recorder.recover(metadata.activityId, elapsedMs, timeMs)) {
                    is RecoveryResult.Resumed -> {
                        // The last known activity time, not "now": the app can be reopened long after the
                        // kill, and stopping at wall-clock now would count that whole gap as moving time.
                        val completed = recorder.stop(recorder.lastKnownElapsedRealtimeMs, recorder.lastKnownTimeMs)
                        exportActivityId = completed.activityId
                        completedDiagnostics = completed.diagnostics
                        recoveryWarning = droppedRecordWarning(result.droppedRecordCount)
                        enqueueSync(context)
                    }
                    is RecoveryResult.Completed -> {
                        exportActivityId = metadata.activityId
                        completedDiagnostics = result.activity.diagnostics
                        recoveryWarning = droppedRecordWarning(result.droppedRecordCount)
                        enqueueSync(context)
                    }
                }
                pendingRecovery = null
            },
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            recoveryWarning?.let { Text(it) }
            LiveMetrics(snapshot)
            Controls(
                snapshot = snapshot,
                onStart = { sport ->
                    if (hasLocationPermission(context)) {
                        startRecording(context, app, sport)
                    } else {
                        pendingSport = sport
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                onPause = {
                    val (e, t) = now()
                    recorder.pause(e, t)
                },
                onResume = {
                    val (e, t) = now()
                    recorder.resume(e, t)
                },
                onStop = {
                    val (e, t) = now()
                    val completed = recorder.stop(e, t)
                    context.stopService(Intent(context, TrackingService::class.java))
                    exportActivityId = completed.activityId
                    completedDiagnostics = completed.diagnostics
                    enqueueSync(context)
                },
            )
            if (exportActivityId != null) {
                Button(onClick = { exportLauncher.launch("activity.gpx") }) { Text("Export GPX") }
            }
            completedDiagnostics?.let { diagnostics ->
                DiagnosticsSummary(diagnostics)
                Button(onClick = { exportDiagnosticsLauncher.launch("${diagnostics.activityId}-diagnostics.json") }) {
                    Text("Export diagnostics")
                }
            }
            AccountSection(app)
        }
    }
}

/** TDD-0002: which server to sync to, sign-in with its identity provider, and where the latest activity stands. */
@Composable
private fun AccountSection(app: AverynApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(app.auth.serverUrl.orEmpty()) }
    var signedIn by remember { mutableStateOf(app.auth.isSignedIn) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncText by remember { mutableStateOf("") }

    val signInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    app.auth.completeSignIn(data)
                    signedIn = true
                    message = null
                    enqueueSync(context)
                } catch (e: Exception) {
                    message = "Sign-in failed"
                }
            }
        }
    // Cheap to poll: a finished, uploaded activity answers from its small status file.
    LaunchedEffect(Unit) {
        while (true) {
            syncText = withContext(Dispatchers.IO) { latestSyncText(app) }
            delay(3_000)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (signedIn) {
                Button(onClick = {
                    app.auth.signOut()
                    signedIn = false
                }) { Text("Sign out") }
            } else {
                Button(onClick = {
                    app.auth.serverUrl = serverUrl
                    scope.launch {
                        try {
                            signInLauncher.launch(app.auth.signInIntent())
                            message = null
                        } catch (e: Exception) {
                            message = "Could not reach the server"
                        }
                    }
                }) { Text("Sign in") }
            }
            Button(onClick = {
                app.auth.serverUrl = serverUrl
                enqueueSync(context)
            }) { Text("Sync now") }
        }
        message?.let { Text(it) }
        Text(syncText)
    }
}

private fun latestSyncText(app: AverynApplication): String {
    val latest = app.store.list().maxByOrNull { it.startedAtMs } ?: return "Sync: no activities yet"
    val status = SyncStatusStore(app.store).statusOf(latest.activityId)
    return "Sync: ${status.state}" + (status.lastError?.let { " ($it)" } ?: "")
}

@Composable
private fun LiveMetrics(snapshot: LiveSnapshot) {
    Text("State: ${snapshot.state}")
    Text("Distance: %.0f m".format(snapshot.metrics.distanceM))
    Text("Elapsed: %.0f s".format(snapshot.metrics.elapsedMs / 1000.0))
    Text("Pace: ${snapshot.metrics.paceSecPerKm?.let { "%.0f s/km".format(it) } ?: "—"}")
    Text("GPS quality: ${snapshot.quality.grade}")
}

@Composable
private fun Controls(
    snapshot: LiveSnapshot,
    onStart: (Sport) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    when (snapshot.state) {
        ActivityState.IDLE, ActivityState.FAILED, ActivityState.COMPLETED ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sport.entries.forEach { sport -> Button(onClick = { onStart(sport) }) { Text(sport.name) } }
            }
        ActivityState.PREPARING -> Text("Waiting for GPS…")
        ActivityState.RECORDING ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPause) { Text("Pause") }
                Button(onClick = onStop) { Text("Stop") }
            }
        ActivityState.PAUSED ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("Resume") }
                Button(onClick = onStop) { Text("Stop") }
            }
        ActivityState.STOPPING -> Text("Finishing…")
    }
}

/** TDD-0001 F8: the aggregate report, for filing a docs/testing/device-matrix.md row after a test run. */
@Composable
private fun DiagnosticsSummary(diagnostics: DiagnosticsReport) {
    Text("Quality: ${diagnostics.quality.grade} (${diagnostics.quality.algorithmVersion})")
    Text("Samples: ${diagnostics.recordingSamples}/${diagnostics.totalSamples}, losses: ${diagnostics.recordLosses}")
    Text("Longest gap: ${diagnostics.quality.longestGapMs / 1000} s")
    Text("Accuracy ≤ 20 m: %.0f%%".format(diagnostics.quality.accuracyWithin20mPercent))
    val drain = diagnostics.batteryDrainPercentPerHour
    Text(
        "Battery: ${diagnostics.batteryStartPercent ?: "—"}% → ${diagnostics.batteryEndPercent ?: "—"}%" +
            (drain?.let { " (%.1f%%/h)".format(it) } ?: ""),
    )
}

@Composable
private fun RecoveryDialog(
    metadata: ActivityMetadata,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Interrupted activity found") },
        text = { Text("A ${metadata.sport} activity was interrupted. Resume it or finish it now?") },
        confirmButton = { TextButton(onClick = onResume) { Text("Resume") } },
        dismissButton = { TextButton(onClick = onFinish) { Text("Finish now") } },
    )
}

private fun droppedRecordWarning(droppedRecordCount: Int): String? =
    if (droppedRecordCount > 0) {
        "Recovery lost $droppedRecordCount corrupted record(s) from the interrupted activity"
    } else {
        null
    }

private fun hasLocationPermission(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** No device identifiers (docs/privacy/data-classification.md) — just what a device-matrix row needs. */
private fun deviceInfo(context: android.content.Context): DeviceInfo {
    val appVersion =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    return DeviceInfo(
        model = android.os.Build.MODEL,
        osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
        appVersion = appVersion,
    )
}

private fun startRecording(
    context: android.content.Context,
    app: AverynApplication,
    sport: Sport,
) {
    val (elapsedMs, timeMs) = now()
    app.recorder.start(UUID.randomUUID().toString(), sport, elapsedMs, timeMs)
    context.startForegroundService(Intent(context, TrackingService::class.java))
}
