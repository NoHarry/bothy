@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.reliability.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DebugA2aOutputTest {
    @Test fun speechTextWorksWithTtsDisabledAndNoAudioDependencies() = runBlocking {
        val texts = mutableListOf<Pair<String, Boolean>>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> texts += text to final })
        val scope = DebugRuntimeRunScope("r", "s")
        val events = listOf(
            AgentEvent.TextDelta("r", "T"),
            AgentEvent.TextDelta("r", "TS: 正在为你查询路线。"),
            toolStarted("r"),
            result(RuntimeCompletionOutcome.SUCCEEDED, "路线查询完成。\n\n- 还有一些详情"),
        )
        events.forEach { announcer.observe(scope, it, DebugTtsConfig()) { true } }
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
            val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> if (final) texts += text })
            announcer.observe(DebugRuntimeRunScope("r", "s"), result(RuntimeCompletionOutcome.SUCCEEDED, response),
                DebugTtsConfig()) { true }
            assertEquals(listOf(expected), texts)
            announcer.stop()
        }
    }

    @Test fun speechDropsParenthesizedResultCodesButKeepsOrdinaryNotes() = runBlocking {
        // Responses recorded on device 5681350; the codes were read out before this fix.
        val cases = listOf(
            "TTS: 已为你打开导航地图。\n\n地图已打开：打开导航地图主页的请求已受理（resultCode=10000），导航应用当前处于运行状态。" +
                "当前没有进行中的导航流程。\n\n需要我帮你搜索地点或规划路线吗？" to
                "地图已打开：打开导航地图主页的请求已受理，导航应用当前处于运行状态。当前没有进行中的导航流程。 需要我帮你搜索地点或规划路线吗？",
            "TTS: 酷我音乐需要先登录，暂时无法点播。\n\n酷我（KUWO）点播未成功：两次尝试分别返回上游错误（20002，无结果）和未登录" +
                "（need_login，20003），当前音源显示为 QQ音乐（type=1），也未能切到酷我。\n\n建议：先登录酷我音乐。" to
                "酷我（KUWO）点播未成功：两次尝试分别返回上游错误和未登录，当前音源显示为 QQ音乐，也未能切到酷我。 建议：先登录酷我音乐。",
            "已打开多媒体（QQ音乐，com.example.media）。\n\n- 其余详情请看屏幕" to "已打开多媒体。",
            "建议错峰出发（如周六上午 9–11 点），2026 年假期（2026年）多，音量为最大值的25%（设备最大值40，即10）。" to
                "建议错峰出发（如周六上午 9–11 点），2026 年假期（2026年）多，音量为最大值的25%（设备最大值40，即10）。",
        )
        for ((response, expected) in cases) {
            val texts = mutableListOf<String>()
            val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> if (final) texts += text })
            announcer.observe(DebugRuntimeRunScope("r", "s"), result(RuntimeCompletionOutcome.SUCCEEDED, response),
                DebugTtsConfig()) { true }
            assertEquals(listOf(expected), texts)
            announcer.stop()
        }
        val progress = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> if (!final) progress += text })
        announcer.observe(DebugRuntimeRunScope("r", "s"), AgentEvent.TextDelta("r", "TTS: 音量已提交（status=ACCEPTED），正在读取读数。"),
            DebugTtsConfig()) { true }
        announcer.observe(DebugRuntimeRunScope("r", "s"), toolStarted("r"), DebugTtsConfig()) { true }
        assertEquals(listOf("音量已提交，正在读取读数。"), progress)
        announcer.stop()
    }

    @Test fun speakThenShowSpeaksTheLeadAndShowsTheCardWithoutProgressMarkers() = runBlocking {
        val response = "TTS: 找到几个北站停车场了。\n\n深圳北站有4个停车场，推荐东广场北区那个，离进站口最近，就去那儿吗？\n\n" +
            "1. 东广场北区地下停车场（推荐，离进站口最近）\n2. 西广场地下停车场"
        val speech = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> if (final) speech += text })
        val event = result(RuntimeCompletionOutcome.SUCCEEDED, response)
        announcer.observe(DebugRuntimeRunScope("r", "s"), event, DebugTtsConfig()) { true }
        assertEquals(listOf("深圳北站有4个停车场，推荐东广场北区那个，离进站口最近，就去那儿吗？"), speech)
        val display = (a2aRuntimeOutput(event) as A2aOutput.Result).text
        assertEquals(response.substringAfter("\n\n"), display)
        announcer.stop()
    }

    @Test fun progressWrittenInTheClosingRoundIsLeftToTheFinalReply() = runBlocking {
        // Device 5681350: "屏幕调亮了，音量也降了一点。" was spoken right before "亮度和音量都调好了…".
        val speech = mutableListOf<Pair<String, Boolean>>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> speech += text to final })
        val scope = DebugRuntimeRunScope("r", "s")
        listOf(
            AgentEvent.TextDelta("r", "TTS: 我先调亮屏幕，再把音量降一点。\n"),
            toolStarted("r"),
            AgentEvent.TextDelta("r", "TTS: 屏幕调亮了，音量也降了一点。\n"),
            AgentEvent.NativeToolCallStarted("r", "finish", "{}"),
            result(RuntimeCompletionOutcome.SUCCEEDED, "亮度和音量都调好了。"),
        ).forEach { announcer.observe(scope, it, DebugTtsConfig()) { true } }
        assertEquals(listOf("我先调亮屏幕，再把音量降一点。" to false, "亮度和音量都调好了。" to true), speech)
        announcer.stop()
        // Hosted web search emits no NativeToolCallStarted; its activity still releases the line.
        val searched = mutableListOf<String>()
        val second = DebugTtsAnnouncer(this, textOutput = { text, final -> if (!final) searched += text })
        second.observe(scope, AgentEvent.TextDelta("r", "TTS: 我先上网查一下深圳北站的营业时间。\n"), DebugTtsConfig()) { true }
        second.observe(scope, AgentEvent.SearchActivity("r", "anthropic"), DebugTtsConfig()) { true }
        assertEquals(listOf("我先上网查一下深圳北站的营业时间。"), searched)
        second.stop()
    }

    @Test fun rewordedRepeatProgressIsSpokenOnce() = runBlocking {
        val progress = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> if (!final) progress += text })
        // One model round on device 5681350 emitted the first two lines back to back.
        listOf("我先看下当前音源，帮你找林俊杰的歌。", "我先看下当前用的音源，再帮你找林俊杰的歌。", "当前音源是QQ音乐，我搜一下林俊杰的歌。")
            .forEach {
                announcer.observe(DebugRuntimeRunScope("r", "s"), AgentEvent.TextDelta("r", "TTS: $it\n"), DebugTtsConfig()) { true }
                announcer.observe(DebugRuntimeRunScope("r", "s"), toolStarted("r"), DebugTtsConfig()) { true }
            }
        assertEquals(listOf("我先看下当前音源，帮你找林俊杰的歌。", "当前音源是QQ音乐，我搜一下林俊杰的歌。"), progress)
        announcer.stop()
    }

    @Test fun unknownOrFailedBusinessOutcomeNeverBecomesCompletedOrSuccessSpeech() = runBlocking {
        val texts = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, _ -> texts += text })
        listOf(RuntimeCompletionOutcome.UNKNOWN, RuntimeCompletionOutcome.FAILED).forEach { outcome ->
            val event = result(outcome, "不能当作成功")
            announcer.observe(DebugRuntimeRunScope("r", "s"), event, DebugTtsConfig()) { true }
            assertEquals(A2aOutput.State.FAILED, (a2aRuntimeOutput(event) as A2aOutput.Result).state)
        }
        assertTrue(texts.isEmpty())
        assertEquals(A2aOutput.State.COMPLETED, (a2aRuntimeOutput(result(RuntimeCompletionOutcome.SUCCEEDED, "好了")) as A2aOutput.Result).state)
    }

    @Test fun aReplyThatAsksTheDriverWaitsForAnAnswerInsteadOfFailing() {
        fun state(outcome: RuntimeCompletionOutcome, response: String) = (a2aRuntimeOutput(result(outcome, response)) as A2aOutput.Result).state

        assertEquals(A2aOutput.State.INPUT_REQUIRED, state(RuntimeCompletionOutcome.PARTIAL, "想怎么调呢？说个数值，或者我给你拖个滑块。"))
        assertEquals(A2aOutput.State.INPUT_REQUIRED, state(RuntimeCompletionOutcome.BLOCKED, "播放模式有三种。\n\n1. 顺序播放\n2. 单曲循环（推荐）"))
        assertEquals(A2aOutput.State.FAILED, state(RuntimeCompletionOutcome.PARTIAL, "导航开了，空调没有响应。"))
        assertEquals(A2aOutput.State.FAILED, state(RuntimeCompletionOutcome.FAILED, "要重试吗？"))
    }

    @Test fun typedToolStatusIncludesNestedCallsButNeverTheirPayload() = runBlocking {
        val outputs = mutableListOf<A2aOutput>()
        val subscription = DebugExecutionEvents.subscribe("r")
        val hook = DebugExecutionToolHook()
        val call = ToolUse("navigate", """{"token":"secret"}""", ToolSource.BACKEND, "c1")
        val context = HookContext("s", "r", "request")
        hook.onPreToolUse(call, context)
        hook.onPostToolUse(call, ToolUseResult(ok = false, resultJson = "private-result"), context)
        repeat(2) { a2aExecutionOutput(subscription.events.receive())?.let { outputs += it } }
        subscription.close()
        assertEquals(listOf("started", "failed"), outputs.map { (it as A2aOutput.Status).phase })
        assertTrue(outputs.all { (it as A2aOutput.Status).toolCallId == "c1" })
        assertFalse(outputs.toString().contains("secret"))
        assertFalse(outputs.toString().contains("private-result"))
    }

    @Test fun thinkingArgumentsAndDetailedFailuresAreNotProjected() {
        assertNull(a2aRuntimeOutput(AgentEvent.ThinkingDelta("r", "private thought")))
        assertNull(a2aRuntimeOutput(AgentEvent.NativeToolCallStarted("r", "tool", "private args")))
        assertNull(a2aRuntimeOutput(AgentEvent.Failed("r", "HTTP", "credential-bearing diagnostic", false)))
        assertEquals("retry", (a2aRuntimeOutput(AgentEvent.RetryAttempted("r", 2, 10)) as A2aOutput.Status).phase)
    }

    private fun result(outcome: RuntimeCompletionOutcome, response: String) = AgentEvent.ResultAvailable(
        RuntimeResult("r", RuntimeRunStatus.COMPLETED, response, completion = RuntimeCompletionSummary(outcome)),
    )
}
