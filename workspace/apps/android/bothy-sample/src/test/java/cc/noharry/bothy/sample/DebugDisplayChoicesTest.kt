@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.a2a.A2aTask
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugDisplayChoicesTest {
    @Test
    fun recommendedNumberedOptionsBecomeChoicesAndLeaveTheSpokenLead() {
        val reply = (
            "沿途有3个厕所，推荐猎德大道那个，要加上吗？\n\n" +
                "1. 猎德大道公厕（推荐，离肯德基最近）\n" +
                "2. 花城广场公厕\n" +
                "3. **海心沙公厕**"
            ).displayChoiceReply()

        assertEquals("沿途有3个厕所，推荐猎德大道那个，要加上吗？", reply.text)
        assertEquals(listOf(1, 2, 3), reply.choices.map { it.ordinal })
        assertEquals("猎德大道公厕（推荐，离肯德基最近）", reply.choices[0].label)
        assertEquals(listOf(true, false, false), reply.choices.map { it.recommended })
        assertEquals("海心沙公厕", reply.choices[2].label)
        assertEquals("第2个", reply.choices[1].replyText)
    }

    @Test
    fun aListBesideATripCardIsNoPick() {
        val text = "行程排好了，要不要设成提醒？\n\n1. 墨白山居民宿（推荐，免费停车）\n2. 林墅酒店\n3. 望狮山居民宿"
        val trip = DebugCard("上海⇄莫干山 · 2 日行程", null, "2 天", null, emptyList(), emptyList(), layout = DebugCardLayout.TRIP)

        val beside = debugPickableReply(1L, text, DebugOffer("s", "t", emptyList(), 0L, cards = listOf(trip)))!!

        assertTrue(beside.reply.choices.isEmpty())
        assertEquals(listOf(trip), beside.cards)
    }

    @Test
    fun aRecommendedListBesideACardIsAPickOnlyWhenAskedOrOffered() {
        val hotels = DebugCard("西湖湖滨 · 400–800元/晚", null, "5 家", null, emptyList(), emptyList(), layout = DebugCardLayout.LIST)
        val listed = "湖滨这一带有 5 家，最推荐桔子。\n\n1. 桔子酒店（推荐，免费停车）\n2. 全季酒店\n3. 希诺酒店"
        val asked = "湖滨这一带有 3 家，你想住哪家？\n\n1. 桔子酒店（推荐，免费停车）\n2. 全季酒店\n3. 希诺酒店"

        assertTrue(debugPickableReply(1L, listed, DebugOffer("s", "t", emptyList(), 0L, cards = listOf(hotels)))!!.reply.choices.isEmpty())
        assertEquals(3, debugPickableReply(1L, asked, DebugOffer("s", "t", emptyList(), 0L, cards = listOf(hotels)))!!.reply.choices.size)
        // Without a card the mark is still the skill's pick.
        assertEquals(3, debugPickableReply(1L, listed, null)!!.reply.choices.size)
        // The same hotels registered with offer_options are no pick either while picking one does nothing ...
        val named = listOf("桔子酒店", "全季酒店", "希诺酒店").map { DebugOfferOption(it, null, "home") }
        assertTrue(debugPickableReply(1L, listed, DebugOffer("s", "t", named, 0L, cards = listOf(hotels)))!!.reply.choices.isEmpty())
        // ... and are one when a pick does something, such as navigating there.
        val go = named.mapIndexed { index, option -> if (index == 0) option.copy(call = DebugBoundCall("amap", "navigate", "mcp__amap__navigate", "{}")) else option }
        assertEquals(3, debugPickableReply(1L, listed, DebugOffer("s", "t", go, 0L, cards = listOf(hotels)))!!.reply.choices.size)
    }

    @Test
    fun choiceQuestionWithoutRecommendationStillOffersPicks() {
        val reply = "找到两首《晴天》，你要哪个版本？\n1、周杰伦\n2、王心凌".displayChoiceReply()

        assertEquals(listOf("周杰伦", "王心凌"), reply.choices.map { it.label })
    }

    @Test
    fun parenthesizedMarkersAndBlankLinesInsideTheBlockAreAccepted() {
        val reply = "选一个停车场？\n（1）东广场\n\n（2）西广场\n还有问题随时说。".displayChoiceReply()

        assertEquals(listOf("东广场", "西广场"), reply.choices.map { it.label })
        assertEquals("选一个停车场？\n还有问题随时说。", reply.text)
    }

    @Test
    fun listedResultsWithAGenericQuestionStayText() {
        val text = "都办好了，还要别的吗？\n\n1. 导航已开启，约三个半小时\n2. 《晴天》已开始播放"

        assertNoChoices(text)
    }

    @Test
    fun malformedOrOutOfRangeListsStayText() {
        assertNoChoices("选哪个？\n1. 东广场")
        assertNoChoices("选哪个？\n1. 东广场\n3. 西广场")
        assertNoChoices("选哪个？\n2. 东广场\n3. 西广场")
        assertNoChoices("选哪个？\n- 东广场\n- 西广场")
        assertNoChoices("选哪个？\n1. 东广场\n   约740米\n2. 西广场")
        assertNoChoices("选哪个？\n" + (1..9).joinToString("\n") { "$it. 停车场$it" })
    }

    @Test
    fun decimalsAreNotOptionMarkers() {
        assertNoChoices("全程12.5公里，选哪条路线？\n12.5公里走高速")
    }

    @Test
    fun tappedOrdinalIsFramedAgainstTheReplyItAnswers() {
        val previous = DebugPreviousExchange("s", "t", "找厕所", "要加上哪个？\n1. 猎德大道公厕（推荐）\n2. 花城广场公厕",
            RuntimeRunStatus.COMPLETED.name, "PARTIAL")

        val input = debugModelInput(DebugDisplayChoice(2, "花城广场公厕", false).replyText, previous)

        assertTrue(input.startsWith(FOLLOW_UP_FRAME_HEADER))
        assertTrue(input.endsWith("User reply: 第2个"))
    }

    @Test
    fun clientOverlayOffersPicksOnlyAfterTheTaskEnds() {
        val ended = clientTurn(A2aTask.Status.INPUT_REQUIRED, running = false)
        val hud = ended.overlayHud(nowEpochMillis = 5_000)

        assertEquals(listOf("东广场停车场（推荐，离进站口近）", "西广场停车场"), hud.choices.map { it.label })
        assertEquals("深圳北站有两个停车场，选哪个？", hud.resultText)

        assertEquals(emptyList<DebugDisplayChoice>(), clientTurn(A2aTask.Status.CANCELLED, running = false).overlayHud(5_000).choices)
        assertEquals(emptyList<DebugDisplayChoice>(), clientTurn(null, running = true).overlayHud(5_000).choices)
    }

    private fun clientTurn(terminal: A2aTask.Status?, running: Boolean): DebugA2aClientState {
        var state = DebugA2aClientState(running = running, activeTaskId = "t1", runStartedAtEpochMillis = 1_000)
            .append { DebugA2aClientItem.User(it, "找停车场", DebugA2aInputSource.OVERLAY, DebugA2aClientItem.User.Kind.MESSAGE) }
            .append { DebugA2aClientItem.Display(it, "t1", "深圳北站有两个停车场，选哪个？\n1. 东广场停车场（推荐，离进站口近）\n2. 西广场停车场") }
        if (terminal != null) state = state.append { DebugA2aClientItem.Terminal(it, "t1", terminal, "", 2_000) }
        return state
    }

    private fun assertNoChoices(text: String) {
        val reply = text.displayChoiceReply()
        assertEquals(emptyList<DebugDisplayChoice>(), reply.choices)
        assertEquals(text, reply.text)
    }
}
