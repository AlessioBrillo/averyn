package dev.averyn.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.app.PendingIntent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.DroppedInputReason
import dev.averyn.tracking.RecoveryResult
import dev.averyn.tracking.ResumeAction
import dev.averyn.tracking.finishOlderInterrupted
import dev.averyn.tracking.resumeDecision

private const val NOTIFICATION_CHANNEL_ID = "tracking"
private const val NOTIFICATION_ID = 1
private const val LOCATION_INTERVAL_MS = 1_000L

/**
 * Foreground service driving the shared [ActivityRecorder] (TDD-0001 §5.1: native code is thin, it only
 * converts platform callbacks to [LocationSample] and calls into shared/). Uses the platform
 * [LocationManager] directly — no Google Play Services dependency, so the app works on de-Googled devices.
 *
 * START_STICKY means the system recreates this service after the process was killed, with a fresh, IDLE
 * recorder and no intent. That is the recovery path (TDD-0006 §5.1): it resumes the interrupted activity by
 * itself when the interruption is recent and precise location is granted, and otherwise asks the user with a
 * notification. It never leaves a "Recording" notification up while fixes are being thrown away.
 */
class TrackingService : Service() {
    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private val recorder: ActivityRecorder get() = (application as AverynApplication).recorder

    // Recovery after a kill/reboot replays the whole stored file: do it off the service's main thread
    // to avoid blocking onStartCommand (ANR risk on long activities, S11).
    private val recoveryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TrackingService-recovery").apply { isDaemon = true }
    }
    private val recovering = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Watchdog: if RECORDING but no fix arrives for too long, surface it instead of staying silent (S5).
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastFixMs: Long = 0
    private val WATCHDOG_GAP_MS = 5L * 60 * 1000 // no fix for 5 minutes while recording
    private val WATCHDOG_CHECK_INTERVAL_MS = 2L * 60 * 1000 // check every 2 minutes

    private val watchdogRunnable: Runnable = Runnable {
        if (recorder.currentState == ActivityState.RECORDING && lastFixMs > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFixMs > WATCHDOG_GAP_MS) {
                interruptAndStop(Reason.PROVIDER_DISABLED)
                return@Runnable
            }
        }
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)
    }

    inner class LocalBinder : Binder() {
        val service: TrackingService get() = this@TrackingService
    }

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (recorder.currentState == ActivityState.PREPARING) {
                    recorder.gpsReady(SystemClock.elapsedRealtime(), System.currentTimeMillis())
                }
                // Always forward the fix: ActivityRecorder.onSample stores it in every non-terminal state
                // (F5) and only feeds live metrics while RECORDING — gating the call itself here would
                // silently lose every fix that arrives while PAUSED instead of just excluding it from metrics.
                val sample =
                    try {
                        location.toSample()
                    } catch (e: IllegalArgumentException) {
                        // A fix the sample model rejects (NaN, out-of-range): counted, never a crash.
                        recorder.recordDroppedInput(DroppedInputReason.INVALID_SAMPLE)
                        return
                    }
                recorder.onSample(sample)
                lastFixMs = SystemClock.elapsedRealtime()
            }

            @Deprecated("Deprecated in Java") // required override pre-API 29; no-op, no status info to act on
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {
                // F9: a permission/provider change mid-activity is surfaced, not a silent stop. The user
                // resumes explicitly once the provider is back (no auto-resume: less surprising). A provider
                // lost while still PREPARING (before the first fix) has no "resume" to offer, so it fails
                // outright instead of pausing — otherwise the activity would wait for GPS forever.
                interrupt(Reason.PROVIDER_DISABLED)
            }
        }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: RuntimeException) {
            // Android 12+ refuses a foreground start from the background (a sticky restart can be one). The
            // activity stays interrupted in the store; the user is one tap away from resuming it.
            postInterruptedNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // If the recorder already serves an activity (started from the UI), just keep feeding fixes.
        if (recorder.currentState != ActivityState.IDLE) {
            startLocationUpdates()
            return START_STICKY
        }
        // System-initiated restart (sticky, boot): look for an interrupted activity off the main thread.
        if (recovering.getAndSet(true)) return START_STICKY // a recovery is already in flight
        recoveryExecutor.execute {
            try {
                val hasActivity = hasActivityToRecord()
                if (!hasActivity) {
                    mainHandler.post { stopSelf() }
                    return@execute
                }
                mainHandler.post { startLocationUpdates() }
            } finally {
                recovering.set(false)
            }
        }
        return START_STICKY
    }

    /**
     * True when the recorder has an activity this service should feed. A recorder that is IDLE here means the
     * process was recreated: look for the interrupted activity and resume it, or ask the user to.
     */
    private fun hasActivityToRecord(): Boolean {
        when (recorder.currentState) {
            ActivityState.PREPARING, ActivityState.RECORDING, ActivityState.PAUSED -> return true
            ActivityState.IDLE -> {}
            else -> return false // terminal or finishing: a stray restart of a service that is no longer needed
        }
        val app = application as AverynApplication
        val elapsedMs = SystemClock.elapsedRealtime()
        val timeMs = System.currentTimeMillis()
        val decision = app.store.resumeDecision(timeMs, hasPrecisePermission = hasPreciseLocation()) ?: return false
        if (app.store.finishOlderInterrupted(decision.metadata.activityId, elapsedMs, timeMs) > 0) enqueueSync(this)
        if (decision.action == ResumeAction.ASK_USER) {
            postInterruptedNotification(this)
            return false
        }
        return when (recorder.recover(decision.metadata.activityId, elapsedMs, timeMs)) {
            is RecoveryResult.Resumed -> true
            is RecoveryResult.Completed -> {
                enqueueSync(this)
                false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        locationManager.removeUpdates(listener)
        watchdogHandler.removeCallbacks(watchdogRunnable)
        super.onDestroy()
    }

    private fun hasPreciseLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        // Checked inline (not through hasPreciseLocation()) so lint sees the guard on requestLocationUpdates below.
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            interruptAndStop(Reason.PERMISSION_REVOKED)
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            interruptAndStop(Reason.PROVIDER_DISABLED)
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, listener)
        } catch (e: SecurityException) {
            // Android 14+: background restart with only while-in-use location throws here. The activity stays
            // interrupted in the store; the user is one tap away from resuming it — never a crash loop.
            interruptAndStop(Reason.PERMISSION_REVOKED)
        }
        // Start watching for a silent loss of fixes while recording (F1.7 / S5).
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)
    }

    /** Interrupts the activity for [reason] and stops this service: never leaves a "Recording" notification up
     * while fixes are being thrown away. */
    private fun interruptAndStop(reason: Reason) {
        interrupt(reason)
        stopSelf()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    /**
     * The location source is gone while an activity exists (F9). Before the first fix there is nothing to keep, so
     * the activity fails; once recording it is paused with the reason and the data stays resumable. Never throws:
     * the state machine has no legal move from IDLE or a terminal state, and then there is nothing to interrupt.
     */
    private fun interrupt(reason: Reason) {
        val elapsedMs = SystemClock.elapsedRealtime()
        val timeMs = System.currentTimeMillis()
        when (recorder.currentState) {
            ActivityState.PREPARING -> recorder.fail(elapsedMs, timeMs, reason)
            ActivityState.RECORDING -> recorder.pause(elapsedMs, timeMs, reason)
            else -> {}
        }
    }

    private fun Location.toSample() =
        buildLocationSample(
            timeMs = time,
            elapsedRealtimeMs = elapsedRealtimeNanos / 1_000_000,
            latitude = latitude,
            longitude = longitude,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
            altitudeM = if (hasAltitude()) altitude else null,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        )

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Activity tracking", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Recording activity")
            .setContentText("Tap to return to Averyn")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

/**
 * The platform-conversion part of `Location.toSample()`, pulled out as a pure function (no `android.location
 * .Location` dependency) so the accuracy/altitude/speed/bearing fallback logic is unit-testable without
 * Robolectric or an instrumented device (TDD-0001 §8: this component can't be fully verified on this
 * project's dev machine, so what CAN be a plain unit test should be one — see LocationSampleConversionTest).
 */
internal fun buildLocationSample(
    timeMs: Long,
    elapsedRealtimeMs: Long,
    latitude: Double,
    longitude: Double,
    accuracyM: Double?,
    altitudeM: Double?,
    speedMps: Double?,
    bearingDeg: Double?,
) = LocationSample(
    timeMs = timeMs,
    elapsedRealtimeMs = elapsedRealtimeMs,
    latitude = latitude,
    longitude = longitude,
    // No accuracy reported is treated as very poor (flagged POOR_ACCURACY downstream), never as 0/perfect.
    horizontalAccuracyM = accuracyM ?: 9_999.0,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
)
}
