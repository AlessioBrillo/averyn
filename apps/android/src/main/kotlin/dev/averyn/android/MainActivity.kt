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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.averyn.domain.ActivityState
import dev.averyn.domain.Sport
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.LiveSnapshot
import dev.averyn.tracking.RecoveryResult
import dev.averyn.tracking.writeGpx
import kotlinx.io.asSink
import kotlinx.io.buffered
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
    var snapshot by remember { mutableStateOf(recorder.snapshot()) }
    var pendingRecovery by remember { mutableStateOf<ActivityMetadata?>(null) }
    var exportActivityId by remember { mutableStateOf<String?>(null) }
    var pendingSport by remember { mutableStateOf(Sport.RUN) }

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
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    writeGpx(app.store, id, out.asSink().buffered())
                }
            }
            exportActivityId = null
        }

    pendingRecovery?.let { metadata ->
        RecoveryDialog(
            metadata = metadata,
            onResume = {
                val (elapsedMs, timeMs) = now()
                when (val result = recorder.recover(metadata.activityId, elapsedMs, timeMs)) {
                    is RecoveryResult.Resumed -> {
                        snapshot = result.snapshot
                        context.startForegroundService(Intent(context, TrackingService::class.java))
                    }
                    is RecoveryResult.Completed -> exportActivityId = result.activity.activityId
                }
                pendingRecovery = null
            },
            onFinish = {
                val (elapsedMs, timeMs) = now()
                when (recorder.recover(metadata.activityId, elapsedMs, timeMs)) {
                    is RecoveryResult.Resumed -> exportActivityId = recorder.stop(elapsedMs, timeMs).activityId
                    is RecoveryResult.Completed -> exportActivityId = metadata.activityId
                }
                pendingRecovery = null
            },
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                },
            )
            if (exportActivityId != null) {
                Button(onClick = { exportLauncher.launch("activity.gpx") }) { Text("Export GPX") }
            }
        }
    }
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

private fun hasLocationPermission(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun startRecording(
    context: android.content.Context,
    app: AverynApplication,
    sport: Sport,
) {
    val (elapsedMs, timeMs) = now()
    app.recorder.start(UUID.randomUUID().toString(), sport, elapsedMs, timeMs)
    context.startForegroundService(Intent(context, TrackingService::class.java))
}
