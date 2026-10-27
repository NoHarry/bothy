package cc.noharry.bothy.playground.headunit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.CallRecord
import cc.noharry.bothy.playground.PlaygroundScene
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.ServerState
import cc.noharry.bothy.playground.ServerStatus
import cc.noharry.bothy.playground.cabin.CabinControls
import cc.noharry.bothy.playground.cabin.summary
import cc.noharry.bothy.playground.home.HomeControls
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.home.summary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Beside the scene: the latest calls and changes, what to say, every control by hand (which also reads out the state),
 * and how to reach the servers from Bothy.
 */
@Composable
internal fun SidePanel(scene: PlaygroundScene, world: World, calls: List<CallRecord>, server: ServerState, flash: Float, store: PlaygroundStore, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("最近") {
            if (calls.isEmpty()) Text("还没有调用。在 Bothy 里连上「${scene.label}」，然后跟小博说一句。", color = PlaygroundColors.muted, fontSize = 13.sp)
            calls.take(RECENT_CALLS).forEachIndexed { i, call -> CallRow(call, if (i == 0) flash else 0f) }
        }
        Card("控制（手动改，Bothy 的计划也看得到）") {
            if (scene == PlaygroundScene.CABIN) CabinControls(world.cabin, store) else HomeControls(world.home, store)
        }
        Card("试试对小博说") {
            EXAMPLES.getValue(scene).forEach { Text("「$it」", color = PlaygroundColors.text, fontSize = 14.sp, lineHeight = 21.sp) }
            Text(GESTURES.getValue(scene), color = PlaygroundColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Card("连接") {
            Text(
                "在 Bothy 的「连接应用」里连上「虚拟座舱」和「虚拟小家」（同一台设备，不用 key）。" +
                    if (server.status == ServerStatus.RUNNING) "地址：${server.address}/cabin/mcp、/home/mcp" else "",
                color = PlaygroundColors.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PlaygroundColors.panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = PlaygroundColors.sage, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun CallRow(call: CallRecord, flash: Float) {
    val back = PlaygroundColors.glow.copy(alpha = 0.18f * flash)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (flash > 0f) back else Color.Transparent).padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(TIME.get()!!.format(Date(call.atMillis)), color = PlaygroundColors.faint, fontSize = 12.sp)
        val (tag, color) = when {
            call.fromAssistant -> "小博" to PlaygroundColors.glow
            call.tool == PlaygroundStore.MANUAL -> "手动" to PlaygroundColors.sage
            else -> "模拟" to PlaygroundColors.faint
        }
        Text(tag, color = color, fontSize = 12.sp)
        Text(call.summary, color = PlaygroundColors.text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

private val EXAMPLES = mapOf(
    PlaygroundScene.CABIN to listOf(
        "你好小博，我有点冷",
        "把主驾座椅加热开到二档",
        "氛围灯换成森林绿",
        "副驾车窗开一半",
        "放点轻松的音乐",
        "导航去西湖",
        "车里降到 24 度以下就把车窗都关上",
        "我这边好热",
        "打开后备箱",
        "屏幕太亮了，音量也小一点",
        "把副驾车门打开",
        "给后排乘客的座椅都开通风",
    ),
    PlaygroundScene.HOME to listOf(
        "我要睡觉了",
        "把客厅的灯调暗一点",
        "拉上卧室的窗帘",
        "让扫地机扫一下厨房",
        "客厅超过 28 度就开空调",
        "我出门了",
        "卧室有人的时候就把卧室灯开到 30%",
    ),
)

private val GESTURES = mapOf(
    PlaygroundScene.CABIN to "在图上：点座位让人上下车，长按座位让那里的人说话（「我」就是他），点车窗开关窗，长按车窗开关车门，点车尾开关后备箱。",
    PlaygroundScene.HOME to "在图上：点房间开关灯，长按房间切换有没有人，点窗帘轨道开关窗帘，点门开关门锁。",
)

private const val RECENT_CALLS = 4
private val TIME = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
}
