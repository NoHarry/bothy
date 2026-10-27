package cc.noharry.bothy.app.tools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaToolHintsTest {
    @Test
    fun playbackWordsBringTheMediaTools() {
        listOf("暂停一下", "快进三十秒", "继续播放", "放点音乐", "下一首", "这首歌叫什么").forEach {
            assertTrue(it, aboutPlayback(it))
        }
    }

    @Test
    fun otherRequestsDoNot() {
        listOf("导航去莲花山公园", "我有点冷", "帮我用计算器算一下二十三乘以四十七").forEach {
            assertFalse(it, aboutPlayback(it))
        }
    }
}
