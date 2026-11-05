package cc.noharry.bothy.app.memory
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.UserMemory
import cc.noharry.bothy.app.overlay.stepPhrase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class UserMemoryUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = 1_000L

    @Test
    fun theHeaderNamesTheUserAndCountsTheDays() {
        val day = 24L * 60 * 60 * 1000
        assertEquals("还没有记住什么", memorySummary(context, emptyList(), 0L).line)
        val memories = listOf(
            memory("m1", MemoryCategory.IDENTITY, "以后叫我老王，别叫全名", MemorySource.SAID, clock),
            memory("m2", MemoryCategory.PLACES, "公司在望京 SOHO", MemorySource.TYPED, clock + 2 * day),
        )
        val summary = memorySummary(context, memories, clock + 2 * day + day / 2)
        assertEquals("老王", summary.name)
        assertEquals("小博记住了 2 件事 · 认识你 3 天", summary.line)
        assertEquals(listOf("2", "1", "2"), summary.metrics.take(3).map { it.first })
        assertEquals("正在记下来", "Running mcp__memory__user_memory".stepPhrase(context.resources))
        // What a model wrote on the bench (2026-10-15), and other ways of putting it.
        listOf(
            "称呼为老王" to "老王", "用户希望被称为小李" to "小李", "我叫王磊，是程序员" to "王磊", "称呼：老张" to "老张", "用户名叫杜强" to "杜强",
            "名字是张三" to "张三", "以后叫我老王吧" to "老王", "叫我老王就行" to "老王", "我叫王磊是程序员" to "王磊", "别叫我老王" to null,
            "不用叫我王总" to null, "不要再叫我老王" to null,
        ).forEach { (words, name) ->
            val memory = UserMemory("m", MemoryCategory.IDENTITY, words, MemorySource.SAID, null, 0L, 0L)
            assertEquals(words, name, memorySummary(context, listOf(memory), 0L).name)
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun theHeaderCountsInEnglishWithSingularAndPlural() {
        val first = memory("m1", MemoryCategory.IDENTITY, "叫我老王", MemorySource.SAID, clock)
        val one = memorySummary(context, listOf(first), clock).line
        assertEquals("Bothy remembers 1 thing · has known you for 1 day", one)
        val second = memory("m2", MemoryCategory.PLACES, "家在望京花园", MemorySource.SAID, clock)
        val two = memorySummary(context, listOf(first, second), clock + 2L * 24 * 60 * 60 * 1000).line
        assertEquals("Bothy remembers 2 things · has known you for 3 days", two)
    }

    private fun memory(id: String, category: MemoryCategory, content: String, source: MemorySource, at: Long) =
        UserMemory(id, category, content, source, null, at, at)
}
