package dev.averyn.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot an interrupted activity cannot be resumed from here (a location foreground service may not be
 * started from the boot broadcast on current Android), so the user is told and one tap on the notification
 * opens the app, which resumes it (TDD-0006 §5.1, scenario S6).
 *
 * Also handles ACTION_MY_PACKAGE_REPLACED (S10): after an app update the recording process is killed, so the
 * interrupted activity must be surfaced on the next launch just like after a reboot.
 *
 * The store scan runs off the broadcast's main thread so a long activity file never risks blocking the
 * ~10 s broadcast budget.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as AverynApplication
        // Off the broadcast thread: listing + replaying every interrupted activity can take seconds
        // for long runs (S11), well inside the 10 s broadcast budget only if we don't block it.
        java.lang
            .Thread({
                if (app.store.interrupted().isNotEmpty()) postInterruptedNotification(context)
            })
            .start()
    }
}
