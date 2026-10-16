package dev.averyn.android

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private const val CHANNEL_ID = "interrupted"
private const val NOTIFICATION_ID = 2

/**
 * "Tap to resume" for an activity the app could not resume by itself: after a reboot (Android 15 does not let a
 * location foreground service start from the boot broadcast) or when the OS refuses a background restart
 * (TDD-0006 §5.1). Opening the app then runs the same resume path. Silent if notifications are not allowed.
 */
internal fun postInterruptedNotification(context: Context) {
    val allowed =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    val manager = NotificationManagerCompat.from(context)
    if (!allowed || !manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(
        NotificationChannelCompat
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Interrupted activity")
            .build(),
    )
    val open =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle("Activity interrupted")
            .setContentText("Tap to resume or finish it.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    manager.notify(NOTIFICATION_ID, notification)
}
