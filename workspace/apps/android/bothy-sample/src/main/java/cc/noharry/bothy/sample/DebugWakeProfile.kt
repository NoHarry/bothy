package cc.noharry.bothy.sample

import cc.noharry.agent.voice.DECOY_MARK
import cc.noharry.agent.voice.VoiceAssistantIdentity
import cc.noharry.agent.voice.WakePhrase
import cc.noharry.agent.voice.WakeWordProfile

/** 你好，小博: 博 is bó, so 小伯 and 小泊 wake it too. */
internal const val XIAOBO_WAKE_PHRASE = "你好小博"
internal const val XIAOBO_WAKE_TOKENS = "n ǐ h ǎo x iǎo b ó"

/**
 * 小博's wake phrases: 你好小博 and 「hi 小博」 (嗨 in either tone, or 嘿) at every level, 博 heard as 波 at the low one, and
 * near misses that win on their own words so they never wake. Inside a turn the recognizer may split the phrase with
 * punctuation ("你好，小博", "Hi，小伯") and write 博 as any homophone; 宝 is left out, as "你好小宝" greets a person.
 */
internal val XIAOBO_WAKE_PROFILE = WakeWordProfile(
    phrases = listOf(
        WakePhrase(XIAOBO_WAKE_TOKENS, XIAOBO_WAKE_PHRASE),
        WakePhrase("h āi x iǎo b ó", "嗨小博"),
        WakePhrase("h ài x iǎo b ó", "嗨小博"),
        WakePhrase("h ēi x iǎo b ó", "嘿小博"),
    ),
    lenientPhrases = listOf(WakePhrase("n ǐ h ǎo x iǎo b ō", "你好小波")),
    decoys = listOf(
        WakePhrase("n ǐ h ǎo x iǎo w áng", "${DECOY_MARK}你好小王"),
        WakePhrase("n ǐ h ǎo x iǎo g ē", "${DECOY_MARK}你好小哥"),
        WakePhrase("n ǐ h ǎo x iǎo m ěi", "${DECOY_MARK}你好小美"),
        WakePhrase("n ǐ h ǎo x iǎo p éng", "${DECOY_MARK}你好小朋"),
        WakePhrase("n ǐ h ǎo x iǎo b ǎo", "${DECOY_MARK}你好小宝"),
        WakePhrase("n ǐ h ǎo x iǎo j iě", "${DECOY_MARK}你好小姐"),
        WakePhrase("n ǐ h ǎo x iǎo q ì", "${DECOY_MARK}你好小气"),
        WakePhrase("n ǐ h ǎo x iǎo q ū", "${DECOY_MARK}你好小区"),
    ),
    heardInTranscript = Regex("(?:你好|[嗨嘿]|hi|hey)[，,、。.！!？?\\s]*[小晓][博伯泊勃铂]", RegexOption.IGNORE_CASE),
)

/** Who the turn judge is told the user talks to. */
internal val XIAOBO_VOICE_IDENTITY = VoiceAssistantIdentity(name = "小博", spokenAs = "Xiaobo", wakePhrase = XIAOBO_WAKE_PHRASE)
