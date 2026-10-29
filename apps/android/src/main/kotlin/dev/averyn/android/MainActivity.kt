package dev.averyn.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.averyn.api.ClientCompatibility
import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
import dev.averyn.api.parseMaxHr
import dev.averyn.domain.ActivityState
import dev.averyn.domain.Sport
import dev.averyn.sync.SyncStatusStore
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.DiagnosticsReport
import dev.averyn.tracking.LiveSnapshot
import dev.averyn.tracking.RecoveryResult
import dev.averyn.tracking.ResumeAction
import dev.averyn.tracking.finishOlderInterrupted
import dev.averyn.tracking.resumeDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val resources = LocalResources.current
    val recorder = app.recorder
    var snapshot by remember { mutableStateOf(recorder.snapshot()) }
    var pendingRecovery by remember { mutableStateOf<ActivityMetadata?>(null) }
    var completedDiagnostics by remember { mutableStateOf<DiagnosticsReport?>(null) }
    var pendingSport by remember { mutableStateOf(Sport.RUN) }
    // F5: a recovery that lost store lines is surfaced, never silent — see RecoveryResult.droppedRecordCount.
    var recoveryWarning by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    // Reloads the history when something was finished or recovered.
    var historyVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    DisposableEffect(recorder) {
        recorder.listener = { snapshot = it }
        onDispose { recorder.listener = null }
    }

    /** Recovers [metadata] into the app's recorder; [finishNow] closes it at its last known activity time.
     * The recover() replay itself is IO-bound (whole stored file): call it off the main thread.
     * Returns the result so the caller can start the foreground service on the main thread after IO. */
    fun applyRecovery(
        metadata: ActivityMetadata,
        finishNow: Boolean,
    ): RecoveryResult? {
        val (elapsedMs, timeMs) = now()
        val result = recorder.recover(metadata.activityId, elapsedMs, timeMs)
        val dropped =
            when (result) {
                is RecoveryResult.Resumed -> {
                    if (finishNow) {
                        // The last known activity time, not "now": the app can be reopened long after the
                        // kill, and stopping at wall-clock now would count that whole gap as moving time.
                        val completed = recorder.stop(recorder.lastKnownElapsedRealtimeMs, recorder.lastKnownTimeMs)
                        completedDiagnostics = completed.diagnostics
                        enqueueSync(context)
                    }
                    result.droppedRecordCount
                }
                is RecoveryResult.Completed -> {
                    completedDiagnostics = result.activity.diagnostics
                    enqueueSync(context)
                    result.droppedRecordCount
                }
            }
        recoveryWarning = droppedRecordWarning(resources, dropped)
        historyVersion++
        // The "tap to resume" notification is no longer needed once the activity has been recovered.
        // InterruptedNotification.NOTIFICATION_ID == 2 (same module, same channel).
        NotificationManagerCompat.from(context).cancel(2)
        return result
    }

    // TDD-0001 F4 / TDD-0006 §5.1: an interruption is resumed by itself when it is recent and precise location is
    // granted (the service normally got there first); anything else is put to the user.
    LaunchedEffect(Unit) {
        if (recorder.currentState != ActivityState.IDLE) return@LaunchedEffect
        val (elapsedMs, timeMs) = now()
        val decision =
            withContext(Dispatchers.IO) { app.store.resumeDecision(timeMs, hasPreciseLocation(context)) }
                ?: return@LaunchedEffect
        val finishedOlder =
            withContext(Dispatchers.IO) {
                app.store.finishOlderInterrupted(decision.metadata.activityId, elapsedMs, timeMs)
            }
        if (finishedOlder > 0) {
            enqueueSync(context)
            historyVersion++
        }
        // The tracking service restarted by the system may have recovered the same activity while the reads above
        // ran: recovering it twice would log a second RECOVERED event and restart the service for nothing.
        if (recorder.currentState != ActivityState.IDLE) return@LaunchedEffect
        if (decision.action == ResumeAction.RESUME_NOW) {
            // recover() replays the whole stored file: do it off the main thread so a long activity
            // (S11: ~10 k lines for 3 h) doesn't ANR the launcher. Start the foreground service back
            // on the main thread once IO is done.
            val result =
                withContext(Dispatchers.IO) {
                    applyRecovery(decision.metadata, finishNow = false)
                }
            if (result is RecoveryResult.Resumed) {
                context.startForegroundService(Intent(context, TrackingService::class.java))
            }
            notice = resources.getString(R.string.resumed_notice)
        } else {
            pendingRecovery = decision.metadata
        }
    }

    /** Completes an in-flight start/resume after the location grant; never starts a second activity
     * on top of one that is already PAUSED/RECORDING (that would throw IllegalStateException). */
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val hasPrecise = fineGranted || hasPreciseLocation(context)
            permissionMessage = null
            if (!hasPrecise) {
                permissionMessage =
                    resources.getString(R.string.precise_location_needed)
                return@rememberLauncherForActivityResult
            }
            when (recorder.currentState) {
                ActivityState.IDLE -> startRecording(context, app, pendingSport)
                ActivityState.PAUSED -> {
                    val (e, t) = now()
                    recorder.resume(e, t)
                    context.startForegroundService(Intent(context, TrackingService::class.java))
                }
                else -> {
                    // RECORDING/PREPARING/STOPPING/COMPLETED/FAILED: nothing to start or resume here.
                    // A pending recovery was already applied before the permission request.
                }
            }
        }

    /** Fine + coarse go together (Android 12+ ignores a fine-only request); notifications only where they exist. */
    fun requestPermissions() {
        val wanted =
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                // The heart-rate strap needs the Bluetooth permissions on Android 12+ (ADR-0020). They are
                // asked together with the location ones: refusing them only means the strap is not used, the
                // recording continues with GPS.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    pendingRecovery?.let { metadata ->
        RecoveryDialog(
            metadata = metadata,
            onResume = {
                pendingRecovery = null
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            applyRecovery(metadata, finishNow = false)
                        }
                    if (result is RecoveryResult.Resumed) {
                        context.startForegroundService(Intent(context, TrackingService::class.java))
                    }
                    // Without precise location the service pauses the activity again at once: ask now, and the
                    // user resumes from the activity's own controls once it is granted.
                    if (!hasPreciseLocation(context)) requestPermissions()
                }
            },
            onFinish = {
                pendingRecovery = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        applyRecovery(metadata, finishNow = true)
                    }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            notice?.let { Text(it) }
            recoveryWarning?.let { Text(it) }
            permissionMessage?.let { Text(it) }
            LiveMetrics(snapshot)
            Controls(
                snapshot = snapshot,
                onStart = { sport ->
                    pendingSport = sport
                    if (hasPreciseLocation(context)) {
                        permissionMessage = null
                        startRecording(context, app, sport)
                    } else {
                        requestPermissions()
                    }
                },
                onPause = {
                    val (e, t) = now()
                    recorder.pause(e, t)
                },
                onResume = {
                    if (hasPreciseLocation(context)) {
                        val (e, t) = now()
                        recorder.resume(e, t)
                        // The service may have stopped itself while the activity was paused for a missing
                        // permission or a disabled provider: starting it again is a no-op when it is running.
                        context.startForegroundService(Intent(context, TrackingService::class.java))
                    } else {
                        requestPermissions()
                    }
                },
                onStop = {
                    val (e, t) = now()
                    val completed = recorder.stop(e, t)
                    context.stopService(Intent(context, TrackingService::class.java))
                    completedDiagnostics = completed.diagnostics
                    historyVersion++
                    enqueueSync(context)
                },
                onCancel = {
                    recorder.cancel()
                    context.stopService(Intent(context, TrackingService::class.java))
                    historyVersion++
                },
            )
            completedDiagnostics?.let { DiagnosticsSummary(it) }
            BatteryGuidance()
            HistorySection(app, refreshKey = historyVersion to snapshot.state)
            AccountSection(app)
        }
    }
}

/** Calls [action] every time the screen comes back to the foreground (e.g. from a system settings page). */
@Composable
private fun OnResume(action: () -> Unit) {
    val activity = LocalActivity.current as ComponentActivity
    val latest by rememberUpdatedState(action)
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest() }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}

/**
 * TDD-0001 §8 (OEM battery killers): when the system may put the app to sleep, say so and open the right
 * settings page. It does not ask for the exemption permission itself (Play policy, TDD-0006 §5.7).
 */
@Composable
private fun BatteryGuidance() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    OnResume { exempt = isIgnoringBatteryOptimizations(context) }
    if (exempt) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.battery_guidance))
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }) { Text(stringResource(R.string.battery_settings)) }
    }
}

/** TDD-0002: which server to sync to, sign-in with its identity provider, and where the latest activity stands. */
@Composable
private fun AccountSection(app: AverynApplication) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current as ComponentActivity
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(app.auth.serverUrl.orEmpty()) }
    var signedIn by remember { mutableStateOf(app.auth.isSignedIn) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncText by remember { mutableStateOf("") }
    // How this app and the server fit together (TDD-0011): a warning, never a refusal.
    var compatibility by remember { mutableStateOf(app.auth.compatibility()) }
    var cacheSize by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    // The configuration of the server is public: it can be read without a session, and it says which versions
    // are in play. A failure is not fatal: the last known answer is kept.
    LaunchedEffect(Unit) {
        runCatching { app.auth.refreshServerConfig() }
        compatibility = app.auth.compatibility()
        cacheSize = cacheSizeText(resources, app)
    }

    val signInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    app.auth.completeSignIn(data)
                    signedIn = true
                    message = null
                    compatibility = app.auth.compatibility()
                    enqueueSync(context)
                } catch (e: Exception) {
                    message = resources.getString(R.string.sign_in_failed)
                }
            }
        }
    // Cheap to poll (a finished, uploaded activity answers from its small status file), and only while the screen
    // is in front: nothing here is worth CPU or battery while a recording runs with the screen off.
    LaunchedEffect(Unit) {
        while (true) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                syncText = withContext(Dispatchers.IO) { latestSyncText(resources, app) }
            }
            delay(3_000)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(stringResource(R.string.server_url)) },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (signedIn) {
                Button(onClick = {
                    app.auth.signOut()
                    signedIn = false
                    scope.launch { cacheSize = cacheSizeText(resources, app) }
                }) { Text(stringResource(R.string.sign_out)) }
            } else {
                Button(onClick = {
                    app.auth.serverUrl = serverUrl
                    scope.launch {
                        try {
                            signInLauncher.launch(app.auth.signInIntent())
                            message = null
                        } catch (e: Exception) {
                            message = resources.getString(R.string.server_unreachable)
                        }
                    }
                }) { Text(stringResource(R.string.sign_in)) }
            }
            Button(onClick = {
                app.auth.serverUrl = serverUrl
                enqueueSync(context)
            }) { Text(stringResource(R.string.sync_now)) }
        }
        compatibilityText(resources, compatibility)?.let { Text(it) }
        if (cacheSize.isNotEmpty()) {
            Text(cacheSize)
            Button(onClick = {
                scope.launch {
                    app.history().clearCache()
                    cacheSize = cacheSizeText(resources, app)
                    message = resources.getString(R.string.offline_copy_deleted)
                }
            }) { Text(stringResource(R.string.delete_offline_copy)) }
        }
        if (signedIn) {
            Button(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete_my_account)) }
        }
        // The operator's own documents (the controller of the data of this instance), when it published them.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.auth.privacyPolicyUrl?.let { url ->
                TextButton(onClick = { openLink(context, url) }) { Text(stringResource(R.string.privacy_policy)) }
            }
            app.auth.termsUrl?.let { url ->
                TextButton(onClick = { openLink(context, url) }) { Text(stringResource(R.string.terms_of_service)) }
            }
        }
        message?.let { Text(it) }
        Text(syncText)
        ProfileSection(app, signedIn)
    }

    if (confirmDelete) {
        DeleteAccountDialog(
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    val token = runCatching { app.auth.freshAccessToken() }.getOrNull()
                    if (token == null) {
                        message = resources.getString(R.string.sign_in_again_delete)
                        return@launch
                    }
                    if (app.history().deleteAccount(token).failure == null) {
                        app.auth.signOut()
                        signedIn = false
                        cacheSize = ""
                        message = resources.getString(R.string.account_deleted)
                    } else {
                        message = resources.getString(R.string.account_delete_failed)
                    }
                }
            },
        )
    }
}

/** Opens a link of the operator in the browser; a phone without one just does nothing. */
private fun openLink(
    context: android.content.Context,
    url: String,
) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/**
 * Asks for the last confirmation of an account deletion (ADR-0017): the user types the word, so a stray tap cannot
 * delete an account. What is deleted and what is not is written where the choice is made.
 */
@Composable
private fun DeleteAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_account_text))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.type_delete)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim().equals("delete", ignoreCase = true)) {
                Text(stringResource(R.string.delete_my_account))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The settings of the account (TDD-0012): the unit system and the maximum heart rate. They live on the server, so
 * the section needs a session; the unit system is also remembered on the device and holds offline.
 */
@Composable
private fun ProfileSection(
    app: AverynApplication,
    signedIn: Boolean,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var units by remember { mutableStateOf(app.units) }
    var maxHr by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val input = parseMaxHr(maxHr)

    LaunchedEffect(signedIn) {
        if (!signedIn) {
            loaded = false
            return@LaunchedEffect
        }
        val token = runCatching { app.auth.freshAccessToken() }.getOrNull()
        val profile = app.history().refreshProfile(token).profile
        if (profile != null) {
            units = profile.units
            maxHr = profile.maxHrBpm?.toString().orEmpty()
            loaded = true
        } else {
            message = resources.getString(R.string.profile_load_failed)
        }
    }

    if (!signedIn) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.profile))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = units == UnitSystem.METRIC,
                onClick = { units = UnitSystem.METRIC },
                enabled = loaded,
                label = { Text(stringResource(R.string.units_metric)) },
            )
            FilterChip(
                selected = units == UnitSystem.IMPERIAL,
                onClick = { units = UnitSystem.IMPERIAL },
                enabled = loaded,
                label = { Text(stringResource(R.string.units_imperial)) },
            )
        }
        OutlinedTextField(
            value = maxHr,
            onValueChange = { maxHr = it },
            label = { Text(stringResource(R.string.max_hr)) },
            singleLine = true,
            enabled = loaded,
            isError = !input.valid,
            supportingText = {
                if (!input.valid) {
                    Text(
                        stringResource(R.string.max_hr_invalid, UserProfile.MIN_MAX_HR_BPM, UserProfile.MAX_MAX_HR_BPM),
                    )
                }
            },
        )
        Button(
            enabled = loaded && input.valid,
            onClick = {
                scope.launch {
                    val token = runCatching { app.auth.freshAccessToken() }.getOrNull()
                    if (token == null) {
                        message = resources.getString(R.string.sign_in_again_profile)
                        return@launch
                    }
                    val saved = app.history().saveProfile(token, UserProfile(units, input.value))
                    if (saved.profile != null) {
                        app.units = saved.units
                        message = resources.getString(R.string.saved)
                    } else {
                        message =
                            if (saved.failure?.status ==
                                422
                            ) {
                                resources.getString(R.string.profile_invalid)
                            } else {
                                resources.getString(R.string.profile_save_failed)
                            }
                    }
                }
            },
        ) { Text(stringResource(R.string.save_profile)) }
        message?.let { Text(it) }
    }
}

/** The warning of a version that does not fit (TDD-0011): it never stops the app from working. */
private fun compatibilityText(
    resources: android.content.res.Resources,
    compatibility: ClientCompatibility,
): String? =
    when (compatibility) {
        ClientCompatibility.OK -> null
        ClientCompatibility.APP_TOO_OLD -> resources.getString(R.string.compat_app_too_old)
        ClientCompatibility.APP_BELOW_MINIMUM -> resources.getString(R.string.compat_below_minimum)
        ClientCompatibility.SERVER_TOO_OLD -> resources.getString(R.string.compat_server_too_old)
        ClientCompatibility.UNKNOWN -> null
    }

/** What the copy of the server occupies on this device. */
private suspend fun cacheSizeText(
    resources: android.content.res.Resources,
    app: AverynApplication,
): String {
    val stats = app.history().cacheStats()
    if (stats.detailCount == 0) return ""
    return resources.getString(R.string.cache_size, stats.detailCount, stats.bytes / 1024)
}

private fun latestSyncText(
    resources: android.content.res.Resources,
    app: AverynApplication,
): String {
    val latest =
        app.store.list().maxByOrNull { it.startedAtMs } ?: return resources.getString(R.string.sync_no_activities)
    // If the latest activity is being recorded right now, report from the live recorder instead of
    // re-parsing the whole store file on every 3 s poll (TDD-0006 §4: no per-second work proportional
    // to activity length).
    val recorder = app.recorder
    if (recorder.currentState != ActivityState.IDLE && recorder.currentActivityId == latest.activityId) {
        val state =
            when (recorder.currentState) {
                ActivityState.RECORDING -> "RECORDING"
                ActivityState.PAUSED -> "PAUSED"
                ActivityState.PREPARING -> "PREPARING"
                else -> recorder.currentState.toString()
            }
        return resources.getString(R.string.sync_recording, state)
    }
    val status = SyncStatusStore(app.store).statusOf(latest.activityId)
    return resources.getString(R.string.sync_state, status.state) + (status.lastError?.let { " ($it)" } ?: "")
}

@Composable
private fun LiveMetrics(snapshot: LiveSnapshot) {
    Text(stringResource(R.string.live_state, snapshot.state))
    Text(stringResource(R.string.live_distance, snapshot.metrics.distanceM))
    Text(stringResource(R.string.live_elapsed, snapshot.metrics.elapsedMs / 1000.0))
    Text(stringResource(R.string.live_pace, snapshot.metrics.paceSecPerKm?.let { "%.0f s/km".format(it) } ?: "—"))
    Text(stringResource(R.string.live_quality, snapshot.quality.grade))
}

@Composable
private fun Controls(
    snapshot: LiveSnapshot,
    onStart: (Sport) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    when (snapshot.state) {
        ActivityState.IDLE, ActivityState.FAILED, ActivityState.COMPLETED ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sport.entries.forEach { sport -> Button(onClick = { onStart(sport) }) { Text(sportName(sport)) } }
            }
        ActivityState.PREPARING ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.waiting_for_gps))
                Button(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        ActivityState.RECORDING ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPause) { Text(stringResource(R.string.pause)) }
                Button(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
        ActivityState.PAUSED ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text(stringResource(R.string.resume)) }
                Button(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
        ActivityState.STOPPING -> Text(stringResource(R.string.finishing))
    }
}

@Composable
private fun sportName(sport: Sport): String =
    stringResource(
        when (sport) {
            Sport.RUN -> R.string.sport_run
            Sport.RIDE -> R.string.sport_ride
            Sport.WALK -> R.string.sport_walk
            Sport.HIKE -> R.string.sport_hike
        },
    )

/** TDD-0001 F8: the aggregate report, for filing a docs/testing/device-matrix.md row after a test run. */
@Composable
private fun DiagnosticsSummary(diagnostics: DiagnosticsReport) {
    Text("Quality: ${diagnostics.quality.grade} (${diagnostics.quality.algorithmVersion})")
    Text("Samples: ${diagnostics.recordingSamples}/${diagnostics.totalSamples}, losses: ${diagnostics.recordLosses}")
    Text("Longest gap: ${diagnostics.quality.longestGapMs / 1000} s")
    val accuracy = diagnostics.quality.accuracyWithin20mPercent
    Text(if (accuracy == null) "Accuracy ≤ 20 m: not measured" else "Accuracy ≤ 20 m: %.0f%%".format(accuracy))
    val drain = diagnostics.batteryDrainPercentPerHour
    Text(
        "Battery: ${diagnostics.batteryStartPercent ?: "—"}% → ${diagnostics.batteryEndPercent ?: "—"}%" +
            (drain?.let { " (%.1f%%/h)".format(it) } ?: ""),
    )
    diagnostics.recoveryGaps.forEach { gap ->
        val length = gap.gapMs?.let { "${it / 1000} s" } ?: "open"
        Text("Recovered: gap $length" + if (gap.rebootDetected) " (reboot)" else "")
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
        title = { Text(stringResource(R.string.recovery_title)) },
        text = { Text(stringResource(R.string.recovery_text, metadata.sport)) },
        confirmButton = { TextButton(onClick = onResume) { Text(stringResource(R.string.resume)) } },
        dismissButton = { TextButton(onClick = onFinish) { Text(stringResource(R.string.finish_now)) } },
    )
}

private fun droppedRecordWarning(
    resources: android.content.res.Resources,
    droppedRecordCount: Int,
): String? =
    if (droppedRecordCount > 0) {
        resources.getString(R.string.recovery_lost, droppedRecordCount)
    } else {
        null
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
