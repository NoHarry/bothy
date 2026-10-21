package cc.noharry.bothy.sample

import android.media.AudioAttributes
import android.util.Log

/**
 * 小博's sound on this device: media playback, as on a tablet or a phone. A host that routes its assistant's voice
 * elsewhere (an assistant usage, its own audio bus) changes [SPEECH_USAGES]; the first usage the platform takes is kept
 * for the process.
 */
internal object DebugSpeechAudio {
    @Volatile private var chosen: Int? = null

    /** The usage speech plays with now, for checks and logs. */
    val usage: Int get() = chosen ?: SPEECH_USAGES.first()

    /** For a player that fails late (MediaPlayer): the usage that has worked, else the best one built without error. */
    fun attributes(contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH): AudioAttributes =
        candidates().firstNotNullOfOrNull { usage -> build(usage, contentType) } ?: build(AudioAttributes.USAGE_MEDIA, contentType)!!

    /** Builds a player with each usage in turn until one is made; null when none is. */
    fun <T : Any> open(contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH, create: (AudioAttributes) -> T): T? {
        for (usage in candidates()) {
            val attributes = build(usage, contentType) ?: continue
            val player = runCatching { create(attributes) }
                .onFailure { Log.w(TAG, "speech player refused usage $usage: ${it.message}") }
                .getOrNull() ?: continue
            if (chosen != usage) Log.i(TAG, "speech plays with usage $usage")
            chosen = usage
            return player
        }
        return null
    }

    private fun candidates(): List<Int> = chosen?.let(::listOf) ?: SPEECH_USAGES

    private fun build(usage: Int, contentType: Int): AudioAttributes? = runCatching {
        AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
    }.getOrNull()?.takeIf { it.usage == usage }
}

/** Usages to play 小博 with, best first. */
private val SPEECH_USAGES = listOf(AudioAttributes.USAGE_MEDIA)
private const val TAG = "DebugSpeechAudio"
