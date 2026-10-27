package cc.noharry.bothy.app.voice
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the microphone usable behind other apps while 语音唤醒 is on: a foreground service of the microphone
 * type. Android grants that type only while the app is in front, and the access it gives lasts for the whole process,
 * so the overlay may close and restart in the background (it steps aside for the console and comes back when the
 * console leaves) and its wake word and voice session still hear. Without it the system silences every recording
 * once the app is behind others. [sync] starts it from the console and the settings switch, both in front.
 */
class VoiceForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!holdMicrophone()) stopSelf()
        return START_NOT_STICKY
    }

    private fun holdMicrophone(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(WAKE_CHANNEL, "小博语音唤醒", NotificationManager.IMPORTANCE_MIN))
            val notification = Notification.Builder(this, WAKE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("小博语音唤醒")
                .setContentText("说「你好，小博」或「OK Bothy」唤醒；在 设置 → 语音 关闭")
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(WAKE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(WAKE_NOTIFICATION_ID, notification)
            }
        }
        true
    } catch (refused: RuntimeException) {
        Log.w(TAG, "microphone foreground refused: ${refused.message}")
        false
    }

    companion object {
        /** Runs it exactly while the switch is on; call while the app is in front, or the start is refused. */
        internal fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, VoiceForegroundService::class.java)
            val wanted = loadVoiceConfig(app).overlayWake &&
                app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            runCatching { if (wanted) app.startService(intent) else app.stopService(intent) }
                .onFailure { Log.w(TAG, "microphone service ${if (wanted) "start" else "stop"} refused: ${it.message}") }
        }

        private const val TAG = "DebugVoice"
        private const val WAKE_CHANNEL = "voice_wake"
        private const val WAKE_NOTIFICATION_ID = 43
    }
}
