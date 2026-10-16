package dev.averyn.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot an interrupted activity cannot be resumed from here (a location foreground service may not be
 * started from the boot broadcast on current Android), so the user is told and one tap on the notification
 * opens the app, which resumes it (TDD-0006 §5.1, scenario S6).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as AverynApplication
        if (app.store.interrupted().isNotEmpty()) postInterruptedNotification(context)
    }
}
