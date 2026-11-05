package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.WakeOrigin
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.protocol.text
import kotlinx.serialization.Serializable

@Serializable
enum class VoiceStatus {
    IDLE,
    STARTING,
    LISTENING,
    BUSY,
    ERROR,
    ;

    /** Whether a session is up: starting, listening or working on what it heard. */
    val isOn: Boolean get() = this == STARTING || this == LISTENING || this == BUSY
}

/** Who a heard stretch of speech was for: the assistant, people talking among themselves, or not sure. */
@Serializable
enum class SegmentLabel { AGENT, SIDE, UNCERTAIN }

/** How a stretch for the assistant was handed over: a new turn, added to the run going on, or replacing its request. */
@Serializable
enum class SegmentHandOver { NEW_TURN, APPEND, REPLACE }

/** Jev's scores for a stretch: done, still holding the floor, meant for the assistant, and the optional ones. */
@Serializable
data class VoiceJudgmentView(
    val turnComplete: Double,
    val holdingFloor: Double,
    val addressedToAgent: Double,
    val takingFloor: Double? = null,
    val replacesTask: Double? = null,
)

/** What the platform runs on the microphone: its echo canceller, or the gate that mutes it while the reply plays. */
@Serializable
data class VoiceEffectsView(val echoCanceller: Boolean, val echoGate: Boolean = false)

/**
 * The last hand-over worth a notice: [text] added to or replacing the running task ([fellBack]: it had ended), and
 * what the assistant said back to an append ([ack]).
 */
@Serializable
data class HandOverNoticeView(
    val handOver: SegmentHandOver,
    val text: String,
    val atEpochMillis: Long,
    val fellBack: Boolean = false,
    val ack: String? = null,
)

/** A finished stretch of speech, as a front end shows it. Times are epoch milliseconds. */
@Serializable
data class VoiceSegmentView(
    val id: Long,
    val text: String,
    val label: SegmentLabel,
    /** Why it ended, a voice end reason name (JUDGED, SILENCE_CAP, ECHO, ...). */
    val reason: String,
    val endedAtEpochMillis: Long,
    val submitted: Boolean = false,
    val handOver: SegmentHandOver? = null,
    val dismissed: Boolean = false,
    val judgment: VoiceJudgmentView? = null,
)

/**
 * The voice session and what surrounds it, as a front end draws it: the session's own state (what is heard, the
 * microphone's loudness, recent stretches), whether 小博 listens, holds to talk or speaks, the wake word switch, and a
 * notice to show once.
 */
@Serializable
data class VoiceView(
    val status: VoiceStatus = VoiceStatus.IDLE,
    /** The session's recognizer, and its model on 百炼; null until a session starts. */
    val engine: VoiceEngine? = null,
    val asrModel: String = "",
    val liveText: String = "",
    val liveJudgment: VoiceJudgmentView? = null,
    val micActive: Boolean = false,
    val micLevels: List<Float> = emptyList(),
    val segments: List<VoiceSegmentView> = emptyList(),
    /** What went wrong with the session (the microphone stopped, recognition failed), or a voice service's own words. */
    val problem: Finding? = null,
    val fullDuplex: Boolean = false,
    val effects: VoiceEffectsView? = null,
    /** Jev's calls this session, how many failed, the last one's latency; echoes dropped and interruptions. */
    val judgeCount: Int = 0,
    val judgeFailures: Int = 0,
    val lastJudgeLatencyMillis: Long? = null,
    val echoes: Int = 0,
    val interruptions: Int = 0,
    val agentWorking: Boolean = false,
    val assistantSpeaking: Boolean = false,
    /** A session is up (or an end-to-end conversation). */
    val listening: Boolean = false,
    /** The session reacts to speech now (not paused for the assistant's own voice). */
    val active: Boolean = false,
    /** Held to talk: the press decides when the turn ends. */
    val holding: Boolean = false,
    /** Let go, and what was said is still being sent. */
    val finishingHold: Boolean = false,
    /** A portrait phone's mic is held to talk (not in an end-to-end conversation, which is tapped on and off). */
    val holdsToTalk: Boolean = true,
    val handOver: HandOverNoticeView? = null,
    /** Started by a wake, so it ends by itself when idle. */
    val wakeSession: Boolean = false,
    /** Why listening could not start, or a front end's own note, to show once. */
    val notice: Finding? = null,
    /** The assistant is speaking a reply. */
    val speaking: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    /** Where wake-word listening stands (listening, or why not), and whether the spotter hears now. */
    val wakeStatus: Finding? = null,
    val wakeListening: Boolean = false,
    /** The wake word was switched off by an update, and the person has not acknowledged the notice yet. */
    val wakeMigrated: Boolean = false,
    /** In an end-to-end conversation, a turn the voice model answered itself: what it heard and said. */
    val realtime: RealtimeExchangeView? = null,
)

@Serializable
data class RealtimeExchangeView(val heard: String, val reply: String = "")

/** The voice session and the wake word ([ExtensionSection] [BothyExtensions.VOICE]). */
@Serializable
data class VoiceSection(val voice: VoiceView = VoiceView())

/** A wake to run; with [onlyIfCanListen] nothing happens when listening is impossible, and the answer says why. */
@Serializable
data class VoiceWakeParams(val source: WakeOrigin, val onlyIfCanListen: Boolean = false)

/** Whether a session was started, and why it could not listen (it may have started and then failed). */
@Serializable
data class VoiceWakeResult(val started: Boolean, val failure: Finding? = null)

/** Requests for the voice session and the wake word. */
object VoiceRequests {
    /** Stops what 小博 is doing, answers, and starts or restarts a woken session. */
    val WAKE = RequestMethod("voice/wake", VoiceWakeParams.serializer(), VoiceWakeResult.serializer())

    /** The person closed the card: the session ends with it. */
    val CLOSED_BY_HAND = RequestMethod("voice/closedByHand", Empty.serializer(), Empty.serializer())

    /** Ends a session a wake started; one held or tapped on goes on. */
    val END_WAKE_SESSION = RequestMethod("voice/endWakeSession", Empty.serializer(), Empty.serializer())

    /** The notice was shown; clears it when it is still this one. */
    val CLEAR_NOTICE = RequestMethod("voice/clearNotice", Finding.serializer(), Empty.serializer())

    /** Switches the wake word, which also settles the update's notice; why it could not be saved, in words. */
    val SET_WAKE_WORD = RequestMethod("voice/setWakeWord", FlagParams.serializer(), SaveResult.serializer())

    /** The person read the notice that an update switched the wake word off. */
    val CLEAR_WAKE_MIGRATION = RequestMethod("voice/clearWakeMigration", Empty.serializer(), Empty.serializer())

    /** Stops the reply being spoken. */
    val INTERRUPT_SPEECH = RequestMethod("voice/interruptSpeech", Empty.serializer(), Empty.serializer())

    /** The mic button: starts a session, or ends the one listening. */
    val TOGGLE = RequestMethod("voice/toggle", Empty.serializer(), Empty.serializer())
    val STOP = RequestMethod("voice/stop", Empty.serializer(), Empty.serializer())

    /** Press to talk: listens while held; why it could not start. */
    val BEGIN_PRESS = RequestMethod("voice/beginPress", Empty.serializer(), VoiceWakeResult.serializer())

    /** Let go: sends what was said. */
    val RELEASE_PRESS = RequestMethod("voice/releasePress", Empty.serializer(), Empty.serializer())

    /** Slid away: drops what was said. */
    val CANCEL_PRESS = RequestMethod("voice/cancelPress", Empty.serializer(), Empty.serializer())

    /** A line for the voice strip (a front end's own hint, such as the microphone being refused). */
    val NOTICE = RequestMethod("voice/notice", TextParams.serializer(), Empty.serializer())
}

/** Notifications about voice. */
object VoiceNotifications {
    /** The speaker sent a woken session away by voice ("退下吧"): a card showing it folds. */
    val DISMISSED = NotificationMethod("voice/dismissed", Empty.serializer())
}
