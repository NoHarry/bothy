package cc.noharry.bothy.app.a2a
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.A2aClientItem
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.QUESTION_PENDING
import cc.noharry.agent.appserver.protocol.view.OverlayPhase
import cc.noharry.bothy.app.overlay.a2aHudTexts
import cc.noharry.bothy.protocol.view.overlayHud
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Under Robolectric with the Chinese resources: the notices and the overlay's words come from them. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class A2aClientSessionUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun overlayHudShowsWireOnlyStage() {
        val state = A2aClientState(
            running = true,
            activeTaskId = "t1",
            runStartedAtEpochMillis = 1_000,
            items = listOf(
                A2aClientItem.User(1, "导航", A2aInputSource.OVERLAY, A2aClientItem.User.Kind.MESSAGE),
                A2aClientItem.Speech(2, "t1", "好的", false),
                A2aClientItem.Tool(3, "t1", "c", "mcp__device__navi", "started", 1_000, null),
                A2aClientItem.Question(4, "t1", "q1", "走高速吗？", listOf("是"), QUESTION_PENDING),
            ),
            nextItemId = 5,
        )

        val hud = state.overlayHud(nowEpochMillis = 4_000, texts = context.resources.a2aHudTexts())

        assertEquals(OverlayPhase.LIVE, hud.phase)
        assertEquals("Speech", hud.planTitle)
        assertEquals("等待回答：走高速吗？", hud.headline)
        assertEquals(listOf("🔊 好的", "问：走高速吗？"), hud.planRows.map { it.text })
        assertEquals("navi", hud.toolLines.single().name)
        assertEquals("好的", hud.resultText)
        assertEquals("3s", hud.elapsedLabel)
        assertEquals("暂无播报（只播报 channel=speech）", A2aClientState().overlayHud(texts = context.resources.a2aHudTexts()).planEmptyText)
    }
}
