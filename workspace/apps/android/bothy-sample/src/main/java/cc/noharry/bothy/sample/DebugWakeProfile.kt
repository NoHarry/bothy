package cc.noharry.bothy.sample

import cc.noharry.agent.voice.DECOY_MARK
import cc.noharry.agent.voice.VoiceAssistantIdentity
import cc.noharry.agent.voice.WakePhrase
import cc.noharry.agent.voice.WakeWordProfile

/** 你好，小博: 博 is bó, so 小伯 and 小泊 wake it too. */
internal const val XIAOBO_WAKE_PHRASE = "你好小博"
internal const val XIAOBO_WAKE_TOKENS = "n ǐ h ǎo x iǎo b ó"

/**
 * The Chinese phrases' boost on the spotter's search (its own is 1.5). The Chinese–English model heard 你好小博 less surely
 * than the WenetSpeech one it replaced: played aloud to the tablet in 8 synthesized voices it woke 13 of 16 times against
 * 8 of 8, and 14 of 16 at 2.0, with no more near misses waking it (嘿，小伙子 woke the old model as well). A lower
 * threshold did not help and woke on 嘿，小伙子 every time.
 */
private const val XIAOBO_WAKE_BOOST = 2.0f

/**
 * OK Bothy, for English speakers (owner 2026-10-25, picked from three): "okay" stressed either way, Bothy as BAH-, BAW- or
 * BOH-thee. Hey Bothy has three syllables and woke on "hey buddy" once its threshold was eased; Hello Bothy woke on
 * "hello Bobby"; neither the th voiced (DH) nor decoys for buddy and Bobby helped, the decoys outscoring Bothy itself.
 */
internal const val BOTHY_WAKE_PHRASE = "OK_Bothy"
private val OK_TOKENS = listOf("OW1 K EY1", "OW2 K EY1")
private val BOTHY_TOKENS = listOf("B AA1 TH IY0", "B OW1 TH IY0", "B AO1 TH IY0")

/**
 * Below the spotter's 0.25, for Bothy's weak th: on 24 synthesized clips of OK Bothy (12 English voices) 21 woke against
 * 19, and none of 64 near misses (okay buddy, hey Bobby, both of them…) did either way. Played aloud to the tablet it woke
 * 6 of 8 times; "OK, both of these work" can wake it, being said almost alike.
 */
private const val BOTHY_WAKE_THRESHOLD = 0.2f

/**
 * 小博's wake phrases: 你好小博 and 「hi 小博」 (嗨 in either tone, or 嘿) at every level, and OK Bothy for English; 博 heard
 * as 波 at the low one, and near misses that win on their own words so they never wake. Inside a turn the recognizer may
 * split the phrase with punctuation ("你好，小博", "Hi，小伯", "OK, Bothy") and write 博 as any homophone; 宝 is left
 * out, as "你好小宝" greets a person. OK Bothy is told from other words by letters alone: a recognizer writes Chinese
 * straight after it ("OK BOTHY帮我导航"), which \b would count as part of the word.
 */
internal val XIAOBO_WAKE_PROFILE = WakeWordProfile(
    phrases = listOf(
        WakePhrase(XIAOBO_WAKE_TOKENS, XIAOBO_WAKE_PHRASE, boost = XIAOBO_WAKE_BOOST),
        WakePhrase("h āi x iǎo b ó", "嗨小博", boost = XIAOBO_WAKE_BOOST),
        WakePhrase("h ài x iǎo b ó", "嗨小博", boost = XIAOBO_WAKE_BOOST),
        WakePhrase("h ēi x iǎo b ó", "嘿小博", boost = XIAOBO_WAKE_BOOST),
    ) + OK_TOKENS.flatMap { ok -> BOTHY_TOKENS.map { bothy -> WakePhrase("$ok $bothy", BOTHY_WAKE_PHRASE, BOTHY_WAKE_THRESHOLD) } },
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
    heardInTranscript = Regex(
        "(?:你好|[嗨嘿]|hi|hey)[，,、。.！!？?\\s]*[小晓][博伯泊勃铂]|(?<![a-z])o\\.?k(?:ay)?[，,、。.！!？?\\s]*bothy(?![a-z])",
        RegexOption.IGNORE_CASE,
    ),
)

/** Who the turn judge is told the user talks to. */
internal val XIAOBO_VOICE_IDENTITY = VoiceAssistantIdentity(name = "小博", spokenAs = "Xiaobo, or Bothy in English", wakePhrase = XIAOBO_WAKE_PHRASE)
