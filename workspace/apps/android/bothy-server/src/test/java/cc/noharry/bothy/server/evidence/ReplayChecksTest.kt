package cc.noharry.bothy.server.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Bothy's bench counts as done: the car's requests must act, and the name-and-route answer must answer. */
class ReplayChecksTest {
    @Test
    fun theCarsRequestsMustAct() {
        for (request in listOf("我想去广州塔，在那找一个停车场", "播放周杰伦的歌", "还是很热", "最近有个很火的电视剧，主题曲是王菲唱的，你知道是什么歌吗", "Navigate home", "导航去公司")) {
            assertTrue(request, requiresCarAction(request))
        }
        assertFalse(requiresCarAction("介绍一下你自己"))
    }

    @Test
    fun processNarrationFailsTheNameAndRouteRequest() {
        val problems = nameRouteProblems(NAME_ROUTE, "All tasks completed! Let me update the todo and provide the final response.")
        assertTrue(problems.failures.single().contains("process narration"))
    }

    @Test
    fun narrationWithTheResultsPasses() {
        val problems = nameRouteProblems(
            NAME_ROUTE,
            "Let me now call finish with a final response: 我是小博，名字代表随时响应。" +
                "已规划不走高速路线去广州塔，途中去深圳北站附近肯德基，找停车场和沿途厕所，并播放周杰伦的歌。",
        )
        assertTrue(problems.failures.isEmpty() && problems.warnings.isEmpty())
    }

    @Test
    fun aMissingNameOrRouteOnlyWarns() {
        val problems = nameRouteProblems(NAME_ROUTE, "已完成。")
        assertTrue(problems.failures.isEmpty())
        assertEquals(listOf("final response is missing name explanation", "final response is missing route key info"), problems.warnings)
        assertTrue(nameRouteProblems(NAME_ROUTE, "").failures.single().contains("missing"))
    }

    @Test
    fun aDirectCompleteAnswerAndOtherRequestsPass() {
        val answer = "我是小博，这个名字代表我会贴近你的日常任务、快速响应。已为你规划不走高速路线去广州塔，途中去深圳北站附近肯德基用餐，" +
            "同时查找广州塔停车场和沿途厕所；开始导航后会播放周杰伦的歌。"
        assertTrue(nameRouteProblems(NAME_ROUTE, answer).let { it.failures.isEmpty() && it.warnings.isEmpty() })
        assertTrue(nameRouteProblems("导航去公司", "").failures.isEmpty())
    }

    private companion object {
        const val NAME_ROUTE =
            "介绍下你自己，为什么叫这个名字，我想去广州塔，在那找一个停车场，中间去一下深圳北站附近的肯德基吃个饭，" +
                "不走高速，出了肯德基之后找一下沿途的厕所，开始导航后放首周杰伦的歌"
    }
}
