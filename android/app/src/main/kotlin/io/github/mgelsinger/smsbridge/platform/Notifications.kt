package io.github.mgelsinger.smsbridge.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.mgelsinger.smsbridge.MainActivity

object Notifications {
    const val SERVICE_ID = 1
    private const val PAIRING_ID = 2
    private const val SERVICE_CHANNEL = "service"
    private const val PAIRING_CHANNEL = "pairing"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Bridge running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the bridge is available to your PC"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(PAIRING_CHANNEL, "Pairing requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks you to approve a PC that wants to pair"
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun service(context: Context, text: String): Notification =
        Notification.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("SMS Bridge")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .build()

    fun updateService(context: Context, text: String) {
        if (!canPost(context)) return
        context.getSystemService(NotificationManager::class.java).notify(SERVICE_ID, service(context, text))
    }

    fun pairingRequest(context: Context, clientName: String) {
        if (!canPost(context)) return
        val n = Notification.Builder(context, PAIRING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Approve pairing?")
            .setContentText("\"$clientName\" wants to send and read texts through this phone")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PAIRING_ID, n)
    }

    fun cancelPairingRequest(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PAIRING_ID)
    }

    private fun canPost(context: Context) =
        android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
