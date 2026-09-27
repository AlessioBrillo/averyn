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
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.tracking.ActivityRecorder

private const val NOTIFICATION_CHANNEL_ID = "tracking"
private const val NOTIFICATION_ID = 1
private const val LOCATION_INTERVAL_MS = 1_000L

/**
 * Foreground service driving the shared [ActivityRecorder] (TDD-0001 §5.1: native code is thin, it only
 * converts platform callbacks to [LocationSample] and calls into shared/). Uses the platform
 * [LocationManager] directly — no Google Play Services dependency, so the app works on de-Googled devices.
 */
class TrackingService : Service() {
    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private val recorder: ActivityRecorder get() = (application as AverynApplication).recorder

    inner class LocalBinder : Binder() {
        val service: TrackingService get() = this@TrackingService
    }

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (recorder.currentState == ActivityState.PREPARING) {
                    recorder.gpsReady(SystemClock.elapsedRealtime(), System.currentTimeMillis())
                }
                if (recorder.currentState == ActivityState.RECORDING) {
                    recorder.onSample(location.toSample())
                }
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
                // resumes explicitly once the provider is back (no auto-resume: less surprising).
                if (recorder.currentState == ActivityState.RECORDING) {
                    recorder.pause(SystemClock.elapsedRealtime(), System.currentTimeMillis(), Reason.PROVIDER_DISABLED)
                }
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
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        locationManager.removeUpdates(listener)
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        val hasPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            recorder.fail(SystemClock.elapsedRealtime(), System.currentTimeMillis(), Reason.PERMISSION_REVOKED)
            stopSelf()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            recorder.fail(SystemClock.elapsedRealtime(), System.currentTimeMillis(), Reason.PROVIDER_DISABLED)
            stopSelf()
            return
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, listener)
    }

    private fun Location.toSample() =
        LocationSample(
            timeMs = time,
            elapsedRealtimeMs = elapsedRealtimeNanos / 1_000_000,
            latitude = latitude,
            longitude = longitude,
            // No accuracy reported is treated as very poor (flagged POOR_ACCURACY downstream), never as 0/perfect.
            horizontalAccuracyM = if (hasAccuracy()) accuracy.toDouble() else 9_999.0,
            altitudeM = if (hasAltitude()) altitude else null,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        )

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Activity tracking", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Recording activity")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
}
