package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.Serializable

/** How a wake came: the wake words, a long press or tap on the assistant, adb, or a head unit's own surface. */
@Serializable
enum class WakeOrigin { WORD, ENGLISH_WORD, LONG_PRESS, TAP, ADB, SURFACE }
