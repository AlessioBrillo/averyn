package dev.averyn.android

import android.app.Application
import android.os.BatteryManager
import dev.averyn.android.auth.AuthManager
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import kotlinx.io.files.Path

class AverynApplication : Application() {
    val auth: AuthManager by lazy { AuthManager(this) }
    val store: ActivityStore by lazy { ActivityStore(Path(filesDir.absolutePath, "activities")) }
    val recorder: ActivityRecorder by lazy {
        ActivityRecorder(store).apply {
            batteryPercent = {
                val percent =
                    getSystemService(BatteryManager::class.java)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                percent.takeIf { it in 0..100 } // the API returns a negative value when unsupported
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Anything finished earlier and not uploaded yet (offline, killed, signed out) goes up now.
        if (auth.serverUrl != null) enqueueSync(this)
    }
}
