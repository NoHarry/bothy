package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.evidence.RunEvidenceRecorder
import cc.noharry.bothy.app.evidence.requiresBusinessAction
import cc.noharry.bothy.app.session.SessionStore
import cc.noharry.bothy.app.session.SessionTurn
import cc.noharry.bothy.app.session.SessionTurnRecorder
import cc.noharry.bothy.app.session.isFirstPartyReusableHistory
import cc.noharry.bothy.app.session.isSemanticConversationHistory
import cc.noharry.bothy.app.session.modelContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Car session 2026-09-28: "要" after a day-trip offer was bound to an offer three turns older. */
class FollowUpReplyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun shortReplyCarriesTheImmediatelyPrecedingOfferAndHistoryMarksOlderOffersSuperseded() {
        val store = SessionStore(temporary.newFolder())
        store.appendTurn(turn("offer-old", "不是，就是有邓小平像的那个地方", OLD_OFFER, "PARTIAL"))
        store.appendTurn(turn("done", "是吧之前的改革开放的点换成邓小平的，其他不变", "已把第一个途经点换成莲花山公园邓小平雕像。"))
        store.appendTurn(turn("trip", "给我推荐一个深圳开车一日游的路线", "上午：莲花山公园。\n\n$TRIP_OFFER"))

        val (input, previous) = store.prepareRunInput(SESSION, "要")

        assertEquals("trip", previous?.turnId)
        assertTrue(input.startsWith(FOLLOW_UP_FRAME_HEADER))
        assertTrue(input.contains(TRIP_OFFER))
        assertFalse(input.contains("改到莲花山山顶广场"))
        assertTrue(input.endsWith("User reply: 要"))
        val context = store.modelContext(SESSION)
        assertTrue(context.contains("offerPolicy="))
        assertTrue(context.lines().single { "user=给我推荐" in it }.contains("offers=current"))
        assertTrue(context.lines().single { "user=不是，就是有邓小平像" in it }.contains("offers=superseded"))
    }

    @Test fun blockedQuestionIsStoredAsTheTurnReplyAndBecomesTheReplyTarget() {
        val store = SessionStore(temporary.newFolder())
        store.appendTurn(turn("seat", "我屁股有点热", "可选：1) 重试座椅通风；3) 打开座椅设置界面。告诉我选哪项即可。", "PARTIAL"))
        val recorder = SessionTurnRecorder(AgentRequest("ask", SESSION, "3", "debug-console"), "3")
        recorder.recordEvent(
            AgentEvent.RuntimeBlocked(RuntimeBlocker("ask-run", RuntimeErrorCode.WAITING_USER_INPUT.wireCode, ASK, SuggestedNextAction.ASK_USER)),
        )
        recorder.recordCancelled()
        store.appendTurn(recorder.snapshot())

        val stored = store.readTurns(SESSION).last()
        assertEquals(RuntimeRunStatus.WAITING_USER_INPUT.name, stored.finalStatus)
        assertEquals(ASK, stored.finalResponse)
        assertEquals("3", stored.userText)
        assertTrue(stored.isSemanticConversationHistory())
        assertFalse(stored.isFirstPartyReusableHistory())
        val context = store.modelContext(SESSION)
        assertTrue(context.lines().single { "user=3 " in it }.contains("offers=current"))
        assertTrue(context.lines().single { "user=我屁股有点热" in it }.contains("offers=superseded"))
        val (input, previous) = store.prepareRunInput(SESSION, "确认")
        assertEquals(ASK, previous?.assistantText)
        assertTrue(input.contains(ASK) && input.endsWith("User reply: 确认"))
    }

    @Test fun newRequestsLongTextAndUnanswerableTurnsKeepTheExactInput() {
        val answered = PreviousExchange(SESSION, "t", "u", TRIP_OFFER, "COMPLETED", "SUCCEEDED")
        for (text in listOf("给我推荐一个深圳开车一日游的路线吧谢谢", "要\n还有加油站", " ", "")) {
            assertSame(text, appModelInput(text, answered))
        }
        assertSame("要", appModelInput("要", null))
        assertSame("要", appModelInput("要", answered.copy(status = "CANCELLED", assistantText = "")))
        assertSame("要", appModelInput("要", answered.copy(status = "FAILED")))
        assertSame("要", appModelInput("要", answered.copy(assistantText = "  ")))
        assertTrue(appModelInput(" 要 ", answered).endsWith("User reply: 要"))
    }

    @Test fun longReplyIsQuotedFromASentenceBoundaryNearItsEnd() {
        val reply = "景点介绍。".repeat(400) + TRIP_OFFER
        val input = appModelInput("好的", PreviousExchange(SESSION, "t", "u", reply, "COMPLETED", "SUCCEEDED"))
        val quoted = input.lines()[1]
        assertTrue(quoted.startsWith("… 景点介绍。") || quoted.startsWith("… 要我"))
        assertTrue(quoted.endsWith(TRIP_OFFER))
        assertTrue(quoted.length <= 362)
    }

    @Test fun replayJudgesActionFromTypedTextNotFromTheQuotedFrame() {
        val framed = appModelInput("不要", PreviousExchange(SESSION, "t", "u", TRIP_OFFER, "COMPLETED", "SUCCEEDED"))
        val request = AgentRequest("r", SESSION, framed, "debug-console")
        fun recorder(userText: String) = RunEvidenceRecorder(temporary.newFolder(), request, "p", "s", "b", userText = userText)
        assertTrue(framed.requiresBusinessAction())
        assertFalse(recorder("不要").snapshot().requiresBusinessAction)
        assertTrue(recorder("导航去欢乐谷").snapshot().requiresBusinessAction)
    }

    @Test fun excerptNeverStartsInsideASurrogatePair() {
        val reply = "a" + "😀".repeat(400)
        val input = appModelInput("好", PreviousExchange(SESSION, "t", "u", reply, "COMPLETED", "SUCCEEDED"))
        assertFalse(String(input.toByteArray(Charsets.UTF_8), Charsets.UTF_8).contains('�'))
        assertTrue(input.lines()[1].startsWith("… 😀"))
    }

    private fun turn(id: String, user: String, assistant: String, outcome: String = "SUCCEEDED") = SessionTurn(
        id, id, id, SESSION, "debug-console", user, 100, 200, "COMPLETED", assistant, emptyList(), outcome,
    )

    private companion object {
        const val SESSION = "a2a-followup"
        const val OLD_OFFER = "要我把终点改成这里吗？可以直接说\"改到莲花山山顶广场\"，我就重新算路。"
        const val TRIP_OFFER = "要我把这条路线设为导航（起点当前位置 → 莲花山公园 → 世界之窗 → 欢乐谷），或直接替换其中某一站再算路吗？"
        const val ASK = "您发送的“3”我理解为方案3：打开座椅设置界面。确认的话我就为您打开，可以吗？"
    }
}
