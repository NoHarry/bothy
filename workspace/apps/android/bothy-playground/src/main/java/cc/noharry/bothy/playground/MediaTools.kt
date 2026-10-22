package cc.noharry.bothy.playground

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

/** What [control_media][mediaTools] can be asked to do. */
internal enum class MediaAction(val id: String) {
    STATUS("status"), PLAY("play"), PAUSE("pause"), NEXT("next"), PREVIOUS("previous"), STOP("stop"),
    VOLUME_UP("volume_up"), VOLUME_DOWN("volume_down"), SET_VOLUME("set_volume"),
}

/**
 * 小博's hold on the device's media (owner 2026-10-22: 「加一个让小博控制媒体的工具」), through the playing app's media
 * session as the play bar's ([NowPlaying]), whether or not the head unit is in front: each call looks at the sessions
 * anew. Real navigation only: the simulated car has its own music (play_music).
 */
internal fun mediaTools(context: Context): List<McpTool> = listOf(
    McpTool(
        name = "control_media",
        title = "控制播放",
        description = "控制平板上正在放的音乐、播客或视频（网易云音乐等 App）：status 看在放什么；play 继续播放，pause 暂停，" +
            "next 下一首，previous 上一首，stop 停止；volume_up / volume_down 把媒体音量调大调小一档，set_volume 设到 volume（0–100）。" +
            "媒体音量也是小博说话的音量。不能按歌名点播：用户要听某首歌，告诉他在 App 里选好，这里可以接着控制。",
        inputSchema = objectSchema(
            "action" to property("string", "要做什么", MediaAction.entries.map { it.id }),
            "volume" to property("integer", "set_volume 时的音量，0–100", min = 0, max = 100),
            required = listOf("action"),
        ),
        direct = true,
    ) { world, args ->
        val action = args.choice("action", MediaAction.entries) { listOf(it.id) } ?: throw ToolRefusal("给出 action")
        ToolOutcome(world, mediaCommand(context.applicationContext, action, args.int("volume", 0..100)))
    },
)

/** Does [action] to the device's media: its volume, or the playing app's session, looked at anew ([mediaTools]). */
internal fun mediaCommand(context: Context, action: MediaAction, volume: Int?): String {
    val audio = context.getSystemService(AudioManager::class.java)
    return when (action) {
        MediaAction.VOLUME_UP, MediaAction.VOLUME_DOWN -> {
            val direction = if (action == MediaAction.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            "媒体音量现在是 ${audio.mediaPercent()}%。"
        }
        MediaAction.SET_VOLUME -> {
            val percent = volume ?: throw ToolRefusal("set_volume 要给 volume（0–100）")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * percent + 50) / 100, 0)
            "媒体音量设到了 ${audio.mediaPercent()}%。"
        }
        else -> controlSession(context, audio, action)
    }
}

/** Plays, pauses, skips or stops the playing (else the latest) app, then says where it ended up. */
private fun controlSession(context: Context, audio: AudioManager, action: MediaAction): String {
    val controller = playingSession(context) ?: return "现在没有在放的媒体，也没有暂停着的：先在网易云音乐等 App 里放一首。"
    val before = controller.title()
    val controls = controller.transportControls
    val done = when (action) {
        MediaAction.PLAY -> "继续播放".also { controls.play() }
        MediaAction.PAUSE -> "暂停了".also { controls.pause() }
        MediaAction.NEXT -> "切到下一首".also { controls.skipToNext() }
        MediaAction.PREVIOUS -> "切到上一首".also { controls.skipToPrevious() }
        MediaAction.STOP -> "停止了".also { controls.stop() }
        else -> null
    }
    settle(controller, action, before)
    return listOfNotNull(done?.let { "$it。" }, controller.describe(context, audio)).joinToString("")
}

/** The app takes a moment to play, pause or change the track: waited for, up to [SETTLE_MS]. */
private fun settle(controller: MediaController, action: MediaAction, before: String) {
    val settled: (MediaController) -> Boolean = when (action) {
        MediaAction.PLAY -> { c -> c.playbackState?.state == PlaybackState.STATE_PLAYING }
        MediaAction.PAUSE, MediaAction.STOP -> { c -> c.playbackState?.state != PlaybackState.STATE_PLAYING }
        MediaAction.NEXT, MediaAction.PREVIOUS -> { c -> c.title() != before }
        else -> { _ -> true }
    }
    val deadline = SystemClock.elapsedRealtime() + SETTLE_MS
    while (!settled(controller) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(SETTLE_POLL_MS)
}

/** The session playing, else the most recently active one; refused without notification access. */
private fun playingSession(context: Context): MediaController? {
    val sessions = try {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, MediaListener::class.java))
    } catch (_: SecurityException) {
        throw ToolRefusal("车机还没有通知使用权，看不到别的 App 的播放：请用户点车机底栏播放条上的「开启播放控制」")
    }
    return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
}

private fun MediaController.describe(context: Context, audio: AudioManager): String {
    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
    val state = if (playbackState?.state == PlaybackState.STATE_PLAYING) "正在放" else "暂停在"
    val what = listOf(title(), artist).filter(String::isNotBlank).joinToString(" - ").ifEmpty { "（没有曲名）" }
    return "${appName(context)}$state：$what；媒体音量 ${audio.mediaPercent()}%。"
}

private fun MediaController.title(): String = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()

private fun MediaController.appName(context: Context): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

private fun AudioManager.mediaPercent(): Int =
    getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

/** How long an answer waits for the app to have done it, and how often it looks. */
private const val SETTLE_MS = 2_000L
private const val SETTLE_POLL_MS = 100L
