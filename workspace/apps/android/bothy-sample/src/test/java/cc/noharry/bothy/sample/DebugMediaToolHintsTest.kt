package cc.noharry.bothy.sample

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugMediaToolHintsTest {
    @Test
    fun playbackWordsBringTheMediaTools() {
        listOf("暂停一下", "快进三十秒", "继续播放", "放点音乐", "下一首", "这首歌叫什么").forEach {
            assertTrue(it, debugAboutPlayback(it))
        }
    }

    @Test
    fun otherRequestsDoNot() {
        listOf("导航去莲花山公园", "我有点冷", "帮我用计算器算一下二十三乘以四十七").forEach {
            assertFalse(it, debugAboutPlayback(it))
        }
    }
}
