package cc.noharry.agent.appserver.image

import cc.noharry.agent.appserver.protocol.GeneratedImageResult
import cc.noharry.agent.appserver.protocol.GeneratedPicture
import cc.noharry.agent.appserver.protocol.view.IMAGE_GENERATE_TOOL
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.spi.ToolCallContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the picture tool drew goes on the board under its run's request ID, which is an A2A task's ID when another agent
 * asked, so a screen that sees only that task (client mode's card) finds it; a call that fails leaves no frame.
 */
class PictureBoardTest {
    @After
    fun generic() = AppServerTesting.reset()

    @Test
    fun aPictureIsOnTheBoardUnderItsTaskAndAFailedCallLeavesNone() = runBlocking {
        val reference = "generated-images/YTJh/img-mv0a1b2c-qrst.jpg"
        val drawing = ImageToolProvider { _, _ -> ImageResult.Success(GeneratedImageResult(reference, 1024, 1024, 1, 1, null)) }
        drawing.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"猫"}""", ToolCallContext("a2a-ctx", "run-1", "task-7"))
        assertEquals(GeneratedPicture(reference, "猫"), PictureBoard.forTask("task-7"))
        assertEquals(GeneratedPicture(reference, "猫"), PictureBoard.all.value["task-7"])

        val failing = ImageToolProvider { _, _ -> ImageResult.Failure("Network error: timeout", 120_000) }
        failing.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"狗"}""", ToolCallContext("a2a-ctx", "run-2", "task-8"))
        assertNull(PictureBoard.forTask("task-8"))
    }

    @Test
    fun aChinesePersonaReadsWhereTheHostShowsThePicture() = runBlocking {
        AppServerTesting.install(AppServerConfig(name = "test", persona = Persona("助手", "", language = "zh-CN")))
        val picture = ImageToolProvider("对话里") { _, _ -> error("nothing is drawn here") }.listTools().single().description
        assertTrue(picture, picture.contains("按文字描述生成一张图片，并直接显示在对话里。"))
        val bare = ImageToolProvider(null) { _, _ -> error("nothing is drawn here") }.listTools().single().description
        assertTrue(bare, bare.contains("按文字描述生成一张图片。prompt 写清主体"))
    }
}
