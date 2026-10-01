package dev.averyn.android

import android.app.Application
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import kotlinx.io.files.Path

class AverynApplication : Application() {
    val store: ActivityStore by lazy { ActivityStore(Path(filesDir.absolutePath, "activities")) }
    val recorder: ActivityRecorder by lazy { ActivityRecorder(store) }
}
