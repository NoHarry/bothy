@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.a2a
import cc.noharry.bothy.server.runtime.*
import cc.noharry.agent.appserver.runtime.RuntimeRunScope
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.reliability.*
import cc.noharry.bothy.server.voice.BothyVoice
import cc.noharry.bothy.server.voice.tts.TtsAnnouncer
import cc.noharry.bothy.server.voice.tts.TtsConfig
import cc.noharry.bothy.server.voice.tts.toolStarted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * What Bothy's voice says of a run as text (the speech another agent's task receives, and the console shows), with no
 * audio set up. What the task shows besides speech is the A2A plugin's (`A2aRuntimeOutputTest` in agent-app-a2a).
 */
class A2aOutputTest {
    @Test fun bothysVoiceHandsItsSpeechOverAsTextWithoutAudio() = runBlocking {
        // What an A2A run says comes from Bothy's voice (the server asks it for a text announcer), audio set up or not.
        val texts = mutableListOf<Pair<String, Boolean>>()
        val announcer = requireNotNull(BothyVoice.textAnnouncer(this) { text, final -> texts += text to final })
        listOf(testSpokenNote("r", "正在核对目的地信息。"), toolStarted("r"), result(RuntimeCompletionOutcome.SUCCEEDED, "地点信息已查到。"))
            .forEach { announcer.observe(RuntimeRunScope("r", "s"), it) { true } }
        assertEquals(listOf("正在核对目的地信息。" to false, "地点信息已查到。" to true), texts)
        announcer.stop()
    }

    @Test fun speechTextWorksWithTtsDisabledAndNoAudioDependencies() = runBlocking {
        val texts = mutableListOf<Pair<String, Boolean>>()
        val announcer = TtsAnnouncer(this, textOutput = { text, final -> texts += text to final })
        val scope = RuntimeRunScope("r", "s")
        val events = listOf(
            testSpokenNote("r", "正在为你查询路线。"),
            toolStarted("r"),
            result(RuntimeCompletionOutcome.SUCCEEDED, "路线查询完成。\n\n- 还有一些详情"),
        )
        events.forEach { announcer.observe(scope, it, TtsConfig()) { true } }
        assertEquals(listOf("正在为你查询路线。" to false, "路线查询完成。" to true), texts)
        announcer.stop()
    }

    @Test fun finalSpeechIsNotCutAtListMarkersOrDecimalsAndDropsMarkdownEmphasis() = runBlocking {
        val cases = listOf(
            "周末短途出行准备建议（三条）：\n\n1. **车辆状态先过一遍**：检查胎压。\n2. **路线**：准备备选路线。" to
                "周末短途出行准备建议（三条）： 1. 车辆状态先过一遍：检查胎压。",
            // A lead too long to listen to falls back to its first sentence.
            "全程 12.5 公里，约 25 分钟。途经的肯德基、加油站和停车场也都排进路线了，每一站的距离、预计到达时间和营业情况都已整理好，" +
                "出发后如果想调整顺序或换一家，随时告诉我就行，路上遇到拥堵我也会提醒你绕行，到了停车场附近还会再报一次剩余车位，" +
                "有什么想改的直接说，回程要不要提前规划我也可以先帮你看看路况和沿途的补能站点，到时候直接出发就行。" to
                "全程 12.5 公里，约 25 分钟。",
            "## 路线\nRoute is ready.\n\n- Details are on screen." to "路线 Route is ready.",
        )
        for ((response, expected) in cases) {
            val texts = mutableListOf<String>()
            val announcer = TtsAnnouncer(this, textOutput = { text, final -> if (final) texts += text })
            announcer.observe(RuntimeRunScope("r", "s"), result(RuntimeCompletionOutcome.SUCCEEDED, response),
                TtsConfig()) { true }
            assertEquals(listOf(expected), texts)
            announcer.stop()
        }
    }

    @Test fun speechDropsParenthesizedResultCodesButKeepsOrdinaryNotes() = runBlocking {
        // Responses recorded on device 5681350; the codes were read out before this fix.
        val cases = listOf(
            "地图已打开：打开导航地图主页的请求已受理（resultCode=10000），导航应用当前处于运行状态。" +
                "当前没有进行中的导航流程。\n\n需要我帮你搜索地点或规划路线吗？" to
                "地图已打开：打开导航地图主页的请求已受理，导航应用当前处于运行状态。当前没有进行中的导航流程。 需要我帮你搜索地点或规划路线吗？",
            "酷我（KUWO）点播未成功：两次尝试分别返回上游错误（20002，无结果）和未登录" +
                "（need_login，20003），当前音源显示为 QQ音乐（type=1），也未能切到酷我。\n\n建议：先登录酷我音乐。" to
                "酷我（KUWO）点播未成功：两次尝试分别返回上游错误和未登录，当前音源显示为 QQ音乐，也未能切到酷我。 建议：先登录酷我音乐。",
            "已打开多媒体（QQ音乐，com.example.media）。\n\n- 其余详情请看屏幕" to "已打开多媒体。",
            "建议错峰出发（如周六上午 9–11 点），2026 年假期（2026年）多，音量为最大值的25%（设备最大值40，即10）。" to
                "建议错峰出发（如周六上午 9–11 点），2026 年假期（2026年）多，音量为最大值的25%（设备最大值40，即10）。",
        )
        for ((response, expected) in cases) {
            val texts = mutableListOf<String>()
            val announcer = TtsAnnouncer(this, textOutput = { text, final -> if (final) texts += text })
            announcer.observe(RuntimeRunScope("r", "s"), result(RuntimeCompletionOutcome.SUCCEEDED, response),
                TtsConfig()) { true }
            assertEquals(listOf(expected), texts)
            announcer.stop()
        }
        val progress = mutableListOf<String>()
        val announcer = TtsAnnouncer(this, textOutput = { text, final -> if (!final) progress += text })
        announcer.observe(RuntimeRunScope("r", "s"), testSpokenNote("r", "音量已提交（status=ACCEPTED），正在读取读数。"),
            TtsConfig()) { true }
        announcer.observe(RuntimeRunScope("r", "s"), toolStarted("r"), TtsConfig()) { true }
        assertEquals(listOf("音量已提交，正在读取读数。"), progress)
        announcer.stop()
    }

    @Test fun speakThenShowSpeaksTheLeadAndShowsTheCard() = runBlocking {
        val response = "深圳北站有4个停车场，推荐东广场北区那个，离进站口最近，就去那儿吗？\n\n" +
            "1. 东广场北区地下停车场（推荐，离进站口最近）\n2. 西广场地下停车场"
        val speech = mutableListOf<String>()
        val announcer = TtsAnnouncer(this, textOutput = { text, final -> if (final) speech += text })
        val event = result(RuntimeCompletionOutcome.SUCCEEDED, response)
        announcer.observe(RuntimeRunScope("r", "s"), event, TtsConfig()) { true }
        assertEquals(listOf("深圳北站有4个停车场，推荐东广场北区那个，离进站口最近，就去那儿吗？"), speech)
        announcer.stop()
    }

    @Test fun aNoteIsSaidOnlyOnACallThatDoesRealWork() = runBlocking {
        // Device 5681350: "屏幕调亮了，音量也降了一点。" was spoken right before "亮度和音量都调好了…".
        val speech = mutableListOf<Pair<String, Boolean>>()
        val announcer = TtsAnnouncer(this, textOutput = { text, final -> speech += text to final })
        val scope = RuntimeRunScope("r", "s")
        listOf(
            testSpokenNote("r", "我先调亮屏幕，再把音量降一点。\n"),
            toolStarted("r"),
            testSpokenNote("r", "屏幕调亮了，音量也降了一点。\n"),
            testToolStarted("r", "finish", "{}"),
            result(RuntimeCompletionOutcome.SUCCEEDED, "亮度和音量都调好了。"),
        ).forEach { announcer.observe(scope, it, TtsConfig()) { true } }
        assertEquals(listOf("我先调亮屏幕，再把音量降一点。" to false, "亮度和音量都调好了。" to true), speech)
        announcer.stop()
    }

    @Test fun rewordedRepeatProgressIsSpokenOnce() = runBlocking {
        val progress = mutableListOf<String>()
        val announcer = TtsAnnouncer(this, textOutput = { text, final -> if (!final) progress += text })
        // One model round on device 5681350 emitted the first two lines back to back.
        listOf("我先看下当前音源，帮你找林俊杰的歌。", "我先看下当前用的音源，再帮你找林俊杰的歌。", "当前音源是QQ音乐，我搜一下林俊杰的歌。")
            .forEach {
                announcer.observe(RuntimeRunScope("r", "s"), testSpokenNote("r", "$it\n"), TtsConfig()) { true }
                announcer.observe(RuntimeRunScope("r", "s"), toolStarted("r"), TtsConfig()) { true }
            }
        assertEquals(listOf("我先看下当前音源，帮你找林俊杰的歌。", "当前音源是QQ音乐，我搜一下林俊杰的歌。"), progress)
        announcer.stop()
    }

    @Test fun unknownOrFailedBusinessOutcomeNeverBecomesSuccessSpeech() = runBlocking {
        val texts = mutableListOf<String>()
        val announcer = TtsAnnouncer(this, textOutput = { text, _ -> texts += text })
        listOf(RuntimeCompletionOutcome.UNKNOWN, RuntimeCompletionOutcome.FAILED).forEach { outcome ->
            val event = result(outcome, "不能当作成功")
            announcer.observe(RuntimeRunScope("r", "s"), event, TtsConfig()) { true }
        }
        assertTrue(texts.isEmpty())
    }

    private fun result(outcome: RuntimeCompletionOutcome, response: String) = testTurnCompleted(
        RuntimeResult("r", RuntimeRunStatus.COMPLETED, response, completion = RuntimeCompletionSummary(outcome)),
    )
}
