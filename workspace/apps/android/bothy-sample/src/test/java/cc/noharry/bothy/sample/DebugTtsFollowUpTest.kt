package cc.noharry.bothy.sample

import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.reliability.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DebugTtsFollowUpTest {
    @Test fun finalSummaryRetainsFollowingQuestionsWithAndWithoutProgress() = runBlocking {
        for (withProgress in listOf(false, true)) {
            val output = mutableListOf<Pair<String, Boolean>>()
            val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> output += text to final })
            if (withProgress) announcer.observe(scope, AgentEvent.TextDelta("r", "TTS: 我先查目的地信息。"), DebugTtsConfig()) { true }
            announcer.observe(scope, result("地址已找到。需要我继续查停车场吗？\n\n- 这里还有详细说明\n\n希望查地面还是地下停车场？"), DebugTtsConfig()) { true }
            assertEquals("地址已找到。需要我继续查停车场吗？ 希望查地面还是地下停车场？" to true, output.last())
            assertFalse(output.last().first.contains("详细说明"))
            announcer.stop()
        }
    }

    @Test fun followUpQuestionNeverAbsorbsTheListAboveItOrSplitsAtDecimals() = runBlocking {
        // Device 5681350 spoke "4 公里，预计约 31 分钟 - 顺序：… 需要我顺路找加油站或充电站吗？" before this fix.
        val cases = listOf(
            "已按“广州塔为终点、白云机场为途经点”算好路线并开始导航。\n\n- 全程剩余：约 64.9 公里，预计约 1 小时 20 分钟\n" +
                "- 到白云机场途经点：约 22.4 公里，预计约 31 分钟\n- 顺序：白云机场 → 广州塔；当前在安泰路\n\n需要我顺路找加油站或充电站吗？" to
                "已按“广州塔为终点、白云机场为途经点”算好路线并开始导航。 需要我顺路找加油站或充电站吗？",
            "导航开始了。剩余22.4公里，要不要顺路加油？\n- 要不要再找个停车场？" to
                "导航开始了。剩余22.4公里，要不要顺路加油？ 要不要再找个停车场？",
        )
        for ((response, expected) in cases) {
            val output = mutableListOf<String>()
            val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> if (final) output += text })
            announcer.observe(scope, result(response), DebugTtsConfig()) { true }
            assertEquals(listOf(expected), output)
            announcer.stop()
        }
    }

    @Test fun optionListsAreShownButOnlyTheRecommendingQuestionIsSpoken() = runBlocking {
        val listed = "深圳北站附近有4个停车场：\n1. 东广场北区地下停车场（推荐，离进站口最近）\n2. 西广场地下停车场\n" +
            "3. P4停车场\n推荐东广场北区那个，离进站口最近，选它吗？"
        val spokenFinal = mutableListOf<String>()
        val final = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) spokenFinal += text })
        final.observe(scope, result(listed), DebugTtsConfig()) { true }
        assertEquals(listOf("深圳北站附近有4个停车场： 推荐东广场北区那个，离进站口最近，选它吗？"), spokenFinal)
        final.stop()
        val unnamed = mutableListOf<String>()
        val second = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) unnamed += text })
        second.observe(scope, result("找到3个停车场。\n1. 东广场北区（推荐）\n2. 西广场\n3. P4\n选哪个？"), DebugTtsConfig()) { true }
        assertEquals(listOf("找到3个停车场。 我推荐东广场北区。 选哪个？"), unnamed)
        second.stop()
        val named = mutableListOf<String>()
        val fourth = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) named += text })
        fourth.observe(scope, result("最近的是蔚景云汽车充电站，约2公里。\n\n1. 蔚景云汽车充电站（桔利西湖充电站）（推荐，最近）\n" +
            "2. 小鹏超级充电站\n\n要去哪个？"), DebugTtsConfig()) { true }
        assertEquals(listOf("最近的是蔚景云汽车充电站，约2公里。 要去哪个？"), named)
        fourth.stop()
        // Device 5681350: the lead used the alias, and the lifted line read the option's details aloud.
        val aliased = mutableListOf<String>()
        val fifth = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) aliased += text })
        fifth.observe(scope, result("附近充电站不少，最近的是炭步大田充电站，约740米。\n\n" +
            "1. 新能汽车充电站(炭步大田充电站)——约740米，快充12个（推荐，最近）\n2. 特来电——约860米\n\n要去哪一家？"), DebugTtsConfig()) { true }
        assertEquals(listOf("附近充电站不少，最近的是炭步大田充电站，约740米。 要去哪一家？"), aliased)
        fifth.stop()
        val detailed = mutableListOf<String>()
        val sixth = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) detailed += text })
        sixth.observe(scope, result("附近有3个充电站。\n\n1. 新能汽车充电站——约740米，快充12个（推荐，最近）\n2. 特来电——约860米\n\n要去哪一家？"),
            DebugTtsConfig()) { true }
        assertEquals(listOf("附近有3个充电站。 我推荐新能汽车充电站，最近。 要去哪一家？"), detailed)
        sixth.stop()
        // Device 5681350, partial outcome: inline options were read aloud and the note said "推荐" twice.
        val partial = mutableListOf<String>()
        val third = DebugTtsAnnouncer(this, textOutput = { text, _ -> partial += text })
        third.observe(scope, result("深圳北站有多个停车场，请选一个：1. 东广场北区地下停车场 2. 西广场地下停车场。" +
            "推荐东广场北区地下停车场（推荐，靠东广场进站口），选它吗？", RuntimeCompletionOutcome.PARTIAL), DebugTtsConfig()) { true }
        assertEquals(listOf("深圳北站有多个停车场，请选一个。推荐东广场北区地下停车场，靠东广场进站口，选它吗？"), partial)
        third.stop()
        // Device 5681350, partial multi-intent reply: done parts and the pending choice are spoken together.
        val mixed = mutableListOf<String>()
        val eighth = DebugTtsAnnouncer(this, textOutput = { text, _ -> mixed += text })
        eighth.observe(scope, result("导航已经开了，周杰伦的歌也放上了。\n\n1. 猎德大道公厕（推荐，离肯德基最近）\n2. 白云汇广场洗手间\n\n" +
            "沿途厕所加哪个？", RuntimeCompletionOutcome.PARTIAL), DebugTtsConfig()) { true }
        assertEquals(listOf("导航已经开了，周杰伦的歌也放上了。 我推荐猎德大道公厕，离肯德基最近。 沿途厕所加哪个？"), mixed)
        eighth.stop()
        // Device 5681350: the pick was marked mid-bracket and the lift read "……1.5公里，，最近".
        val inner = mutableListOf<String>()
        val ninth = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) inner += text })
        ninth.observe(scope, result("附近有3个厕所。\n\n1. 公共厕所（马溪市场东路，距肯德基约1.5公里，推荐，最近）\n2. 公共厕所（华景北路）\n\n加哪个？"),
            DebugTtsConfig()) { true }
        assertEquals(listOf("附近有3个厕所。 我推荐公共厕所，最近。 加哪个？"), inner)
        ninth.stop()
    }

    @Test fun longMultiIntentLeadKeepsEveryWholeSentenceThatFits() = runBlocking {
        // Device 5681350: a 132-character lead fell back to its first sentence, so only the self-introduction was spoken.
        val lead = "我是小博，Bothy 示例助手，名字由来我这边没有资料，说不清。导航已经开了：终点深圳北站西广场地下停车场，" +
            "途经广州塔广场那家肯德基，全程不走高速，大约191公里、5小时左右，目前导航中。沿途厕所搜到12个，其中离肯德基最近的推荐这个。" +
            "周杰伦的《晴天》也给你放上了。"
        val spoken = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) spoken += text })
        announcer.observe(scope, result("$lead\n\n1. 公共厕所（马溪市场东路，推荐，最近）\n2. 公共厕所（华景北路）\n\n要我把第一个厕所加成途经点吗？"),
            DebugTtsConfig()) { true }
        assertEquals(listOf("$lead 要我把第一个厕所加成途经点吗？"), spoken)
        announcer.stop()
        val longer = mutableListOf<String>()
        val second = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) longer += text })
        second.observe(scope, result(lead + "路上要是堵车我会提醒你绕开，到了停车场附近我再报一次剩余车位，肯德基那边也可以提前帮你看看排队情况。"),
            DebugTtsConfig()) { true }
        assertEquals(listOf(lead), longer)
        second.stop()
        // Device 5681350: the model put its introduction alone in paragraph one and the results in paragraph two.
        val split = mutableListOf<String>()
        val third = DebugTtsAnnouncer(this, textOutput = { text, isFinal -> if (isFinal) split += text })
        third.observe(scope, result("我是小博，Bothy 示例助手，名字的由来我也不清楚。\n\n导航已经开了，终点深圳北站东广场停车场，途经广州塔肯德基，不走高速。" +
            "沿途厕所搜到3个。\n\n1. 猎德大道公厕（推荐，最近）\n2. 金穗路公厕\n\n加哪个？"), DebugTtsConfig()) { true }
        assertEquals(listOf("我是小博，Bothy 示例助手，名字的由来我也不清楚。 导航已经开了，终点深圳北站东广场停车场，途经广州塔肯德基，不走高速。" +
            "沿途厕所搜到3个。 我推荐猎德大道公厕，最近。 加哪个？"), split)
        third.stop()
        // Device 5681350, partial with no question mark: nothing was spoken at all.
        val silent = mutableListOf<String>()
        val seventh = DebugTtsAnnouncer(this, textOutput = { text, _ -> silent += text })
        seventh.observe(scope, result("深圳北站周边有多个停车场，先定一个我就导航过去。\n\n1. 深圳北站东广场北区地下停车场（推荐，离东广场进站口最近）\n" +
            "2. 深圳北站西广场地下停车场\n\n告诉我编号或名字就行。", RuntimeCompletionOutcome.PARTIAL), DebugTtsConfig()) { true }
        assertEquals(listOf("深圳北站周边有多个停车场，先定一个我就导航过去。 我推荐深圳北站东广场北区地下停车场，离东广场进站口最近。"), silent)
        seventh.stop()
        // Device 5681350, partial asking without a question mark or list: "你可以告诉我…" was never spoken.
        val imperative = mutableListOf<String>()
        val tenth = DebugTtsAnnouncer(this, textOutput = { text, _ -> imperative += text })
        tenth.observe(scope, result("深圳北站找到了，但中间那家麦当劳得你定一下。\n\n你可以告诉我大概想在哪个位置停一下，我再重新搜。",
            RuntimeCompletionOutcome.PARTIAL), DebugTtsConfig()) { true }
        assertEquals(listOf("深圳北站找到了，但中间那家麦当劳得你定一下。 你可以告诉我大概想在哪个位置停一下，我再重新搜。"), imperative)
        tenth.stop()

        // Blocking ask has no options field, so options arrive inside the question text.
        val asked = listOf(
            "推荐东广场北区那个，离进站口最近，选它吗？\n1. 东广场北区地下停车场（推荐）\n2. 西广场地下停车场" to
                "推荐东广场北区那个，离进站口最近，选它吗？",
            "深圳北站停车场有多个候选，你希望导航到哪个作为终点？（1）深圳北站东广场北区地下停车场（2）深圳北站西广场地下停车场" to
                "深圳北站停车场有多个候选，你希望导航到哪个作为终点？",
            // Device bde909aa (yingwu turn=3): 202 characters of nested options were read aloud until barge-in.
            "沿途餐厅搜索结果里没有麦当劳。您希望怎么加这个途经点？\n\n1. 从附近搜索到的麦当劳中选一家（距当前位置最近的三家）：\n" +
                "   - 麦当劳(花都风神大道分店)，风神大道18号，约0.8公里\n   - 麦当劳(天马丽苑得来速分店)，望亭路4号\n2. 不加麦当劳，直接走" to
                "沿途餐厅搜索结果里没有麦当劳。您希望怎么加这个途经点？",
            // Device 5681350: the pick was marked only on screen, so speech never mentioned it.
            "深圳北站有好几个停车场，你要去哪个？\n1. 深圳北站东广场北区地下停车场（推荐，地铁A口旁，东广场进站最近）\n" +
                "2. 深圳北站西广场地下停车场\n选哪个？直接说编号或名字就行。" to
                "深圳北站有好几个停车场，你要去哪个？ 我推荐深圳北站东广场北区地下停车场，地铁A口旁，东广场进站最近。 " +
                "选哪个？直接说编号或名字就行。",
        )
        for ((question, expected) in asked) {
            val output = mutableListOf<String>()
            val announcer = DebugTtsAnnouncer(this, textOutput = { text, _ -> output += text })
            val waiting = AgentEvent.ResultAvailable(RuntimeResult("r", RuntimeRunStatus.WAITING_USER_INPUT, question))
            announcer.observe(scope, waiting, DebugTtsConfig()) { true }
            assertEquals(listOf(expected), output)
            assertEquals(A2aOutput.Result(question, A2aOutput.State.INPUT_REQUIRED), a2aRuntimeOutput(waiting))
            announcer.stop()
        }
    }

    @Test fun typedQuestionSpeaksWholePromptOnceAcrossBlockerAndWaitingResult() = runBlocking {
        val output = mutableListOf<Pair<String, Boolean>>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, final -> output += text to final })
        val question = "找到了两个同名地点。一个在广州，一个在深圳。请告诉我选择哪一个。"
        val blocked = AgentEvent.RuntimeBlocked(RuntimeBlocker("r", RuntimeErrorCode.WAITING_USER_INPUT.wireCode,
            question, SuggestedNextAction.START_NEW_AGENT_REQUEST))
        val waiting = AgentEvent.ResultAvailable(RuntimeResult("r", RuntimeRunStatus.WAITING_USER_INPUT, question))
        for (event in listOf(blocked, blocked, waiting)) announcer.observe(scope, event, DebugTtsConfig()) { true }
        assertEquals(listOf(question to true), output)
        assertEquals(A2aOutput.Result(question, A2aOutput.State.INPUT_REQUIRED), a2aRuntimeOutput(blocked))
        assertEquals(A2aOutput.Result(question, A2aOutput.State.INPUT_REQUIRED), a2aRuntimeOutput(waiting))
        announcer.stop()
    }

    @Test fun partialResultSpeaksOnlyTheQuestionAndNeverUnconfirmedSuccess() = runBlocking {
        val output = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, _ -> output += text })
        announcer.observe(scope, result("都已经完成。您想选哪个目的地？", RuntimeCompletionOutcome.PARTIAL), DebugTtsConfig()) { true }
        assertEquals(listOf("您想选哪个目的地？"), output)
        announcer.observe(scope, AgentEvent.ThinkingDelta("r", "内部问题？"), DebugTtsConfig()) { true }
        announcer.observe(scope, AgentEvent.TextDelta("r", "未标记正文？"), DebugTtsConfig()) { true }
        announcer.observe(scope, AgentEvent.ResultAvailable(RuntimeResult("r", RuntimeRunStatus.FAILED, "内部错误？")), DebugTtsConfig()) { true }
        assertEquals(1, output.size)
        announcer.stop()
    }

    @Test fun alreadySpokenQuestionIsNotRepeatedAndNextRunCanAskItAgain() = runBlocking {
        val output = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(this, textOutput = { text, _ -> output += text })
        val question = "您想去哪个目的地？"
        announcer.observe(scope, AgentEvent.TextDelta("r", "TTS: $question"), DebugTtsConfig()) { true }
        val waiting = AgentEvent.ResultAvailable(RuntimeResult("r", RuntimeRunStatus.WAITING_USER_INPUT, question))
        repeat(2) { announcer.observe(scope, waiting, DebugTtsConfig()) { true } }
        assertEquals(listOf(question), output)
        announcer.observe(scope, AgentEvent.ResultAvailable(RuntimeResult("next", RuntimeRunStatus.WAITING_USER_INPUT, question)), DebugTtsConfig()) { true }
        assertEquals(listOf(question, question), output)
        announcer.stop()
    }

    @Test fun questionAfterLongDetailsSurvivesSummaryLimitAndFirstQuestionIsNotDuplicated() {
        assertEquals("去哪？", "去哪？".withFollowUpQuestions("去哪？"))
        assertEquals("信息较多。 您要查询哪里？", "信息较多。".withFollowUpQuestions("信息较多。" + "详情".repeat(500) + "。您要查询哪里？"))
    }

    private val scope = DebugRuntimeRunScope("r", "s")
    private fun result(text: String, outcome: RuntimeCompletionOutcome = RuntimeCompletionOutcome.SUCCEEDED) =
        AgentEvent.ResultAvailable(RuntimeResult("r", RuntimeRunStatus.COMPLETED, text, completion = RuntimeCompletionSummary(outcome)))
}
