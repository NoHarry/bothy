package cc.noharry.bothy.playground

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Notification access, which Android asks of an app that sees other apps' media sessions ([NowPlaying]). It reads no
 * notification; being enabled is all it is for.
 */
class MediaListener : NotificationListenerService()

/** What plays on the device now, as the head unit's play bar shows it. */
data class MediaNow(
    /** Notification access granted ([MediaListener]): without it no session can be seen. */
    val access: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val app: String = "",
    val playing: Boolean = false,
) {
    val any: Boolean get() = title.isNotBlank() || app.isNotBlank()
}

/**
 * The device's media through its sessions (owner 2026-10-22: 「都要加上一个播放状态条，后续准备通过mediasession控制多媒体」):
 * the session playing (else the most recent), what it plays, and its transport controls. Main thread.
 */
object NowPlaying {
    private val current = MutableStateFlow(MediaNow())
    val state: StateFlow<MediaNow> = current
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var appContext: Context? = null

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> follow(controllers.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Another session may have started playing meanwhile: the bar follows the one that plays.
            manager?.let { m -> component()?.let { follow(runCatching { m.getActiveSessions(it) }.getOrDefault(emptyList())) } } ?: publish()
        }
        override fun onSessionDestroyed() {
            controller = null
            publish()
        }
    }

    /** Starts (or, back from the settings, starts again) following the device's sessions. */
    fun start(context: Context) {
        appContext = context.applicationContext
        stop()
        val component = component() ?: return
        if (!hasAccess(context)) {
            current.value = MediaNow(access = false)
            return
        }
        val sessionManager = context.applicationContext.getSystemService(MediaSessionManager::class.java) ?: return
        manager = sessionManager
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessions, component, main)
            follow(sessionManager.getActiveSessions(component))
        }.onFailure { current.value = MediaNow(access = false) }
    }

    fun stop() {
        manager?.let { runCatching { it.removeOnActiveSessionsChangedListener(sessions) } }
        manager = null
        controller?.unregisterCallback(callback)
        controller = null
    }

    fun playPause() {
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        controller?.transportControls?.let { if (playing) it.pause() else it.play() }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    /** Android's page where the user grants the head unit notification access. */
    fun openAccessSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
    }

    private fun component(): ComponentName? = appContext?.let { ComponentName(it, MediaListener::class.java) }

    /** The session playing, else the first (the most recently active). */
    private fun follow(controllers: List<MediaController>) {
        val next = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val active = controller
        val metadata = active?.metadata
        val label = active?.packageName?.let { name ->
            runCatching {
                val pm = appContext!!.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
            }.getOrDefault(name)
        }.orEmpty()
        current.value = MediaNow(
            access = true,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            app = label,
            playing = active?.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }
}
