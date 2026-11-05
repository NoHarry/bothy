package cc.noharry.bothy.app.phoneassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.overlay.OverlayTaskSignal
import cc.noharry.bothy.app.overlay.OverlayTaskTone

/**
 * Android 16's Live Updates for what 小博 does in the background (owner 2026-11-05, after Eta): the status bar's chip,
 * the lock screen, and on some phones the vendor's island, with Stop on it, in place of the capsule's window. Only for
 * work going on; a result waiting is not a live update and keeps the capsule. Where the system offers none (before
 * Android 16, notifications or the channel off, promotion turned off for the app) [takes] is false and the capsule shows.
 *
 * With no window of the app on screen nothing else keeps the [service] running, so the live update is its foreground
 * notification, unless it runs in the foreground already ([inForeground], for an A2A caller).
 */
internal class PhoneLiveUpdate(private val service: Service, private val inForeground: () -> Boolean) {
    private val manager = service.getSystemService(NotificationManager::class.java)
    private var posted: String? = null
    private var holdsForeground = false

    init {
        // One left by a process that was ended while it showed.
        manager.cancel(NOTIFICATION_ID)
    }

    val shown: Boolean get() = posted != null

    /** Whether [signal] can be a live update here. */
    fun takes(signal: OverlayTaskSignal): Boolean =
        signal.tone != OverlayTaskTone.RESULT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
            // Android 16's first release has the call but no promotion (it says no); a build without it at all says no too.
            runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)

    /** Shows [signal]; false when the system would not let the service keep it in the foreground (the capsule shows). */
    fun show(signal: OverlayTaskSignal): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        if (posted == signal.key + signal.detail) return true
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, service.getString(R.string.phone_assist_live_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = service.liveNotification(signal)
        if (holdsForeground || inForeground()) {
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            if (!service.holdForeground(notification)) return false
            holdsForeground = true
        }
        posted = signal.key + signal.detail
        return true
    }

    fun clear() {
        if (posted == null) return
        posted = null
        if (holdsForeground && !inForeground()) service.stopForeground(Service.STOP_FOREGROUND_REMOVE) else manager.cancel(NOTIFICATION_ID)
        holdsForeground = false
    }
}

/** Keeps the service in the foreground with [notification]; false when the system refuses it now. */
private fun Service.holdForeground(notification: Notification): Boolean = try {
    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    true
} catch (refused: RuntimeException) {
    Log.w(TAG, "live update refused the foreground: ${refused.message}")
    false
}

/** The live update: what 小博 does, tapping opens the sheet, Stop stops the task, a chip in the status bar. */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private fun Context.liveNotification(signal: OverlayTaskSignal): Notification =
    Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_bothy)
        .setContentTitle(signal.title)
        .setContentText(signal.detail.ifBlank { null })
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setContentIntent(starting(PhoneAssistant.intent(this, open = true), REQUEST_OPEN))
        .addAction(Notification.Action.Builder(null, getString(R.string.phone_assist_stop), starting(stopIntent(this), REQUEST_STOP)).build())
        .setRequestPromotedOngoing(true)
        .setShortCriticalText(getString(R.string.phone_assist_live_chip))
        .build()

private fun Context.starting(intent: Intent, request: Int): PendingIntent =
    PendingIntent.getService(this, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** The live update's Stop: the task going on stops. */
internal fun stopIntent(context: Context): Intent = Intent(context, PhoneAssistantService::class.java).setAction(ACTION_STOP_TASK)

internal const val ACTION_STOP_TASK = "cc.noharry.bothy.STOP_PHONE_TASK"
private const val CHANNEL = "phone_assist_live"
private const val NOTIFICATION_ID = 0x5A0B
private const val REQUEST_OPEN = 1
private const val REQUEST_STOP = 2
private const val TAG = "PhoneAssist"
