package cc.noharry.bothy.playground

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 模拟 | 真实 at the front of every layout's status bar (owner 2026-10-25: 「一般用户很难发现那么深的开关，布局上最好要方便
 * 切模拟和真实模式」): the simulated car and home, or real navigation where the device is. Switching also starts a new
 * conversation in Bothy ([HeadUnitModes.set]), so what was said in the other mode does not carry over.
 */
@Composable
internal fun ModeSwitch() {
    val context = LocalContext.current
    val mode by HeadUnitModes.mode.collectAsState()
    val scope = rememberCoroutineScope()
    val choose = { real: Boolean ->
        if (mode.realNav != real) {
            val said = if (real) "已切到真实模式：用设备定位导航，小博开了新会话" else "已切到模拟模式：模拟的车和家，小博开了新会话"
            Toast.makeText(context, said, Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) { HeadUnitModes.set(context, mode.copy(realNav = real)) }
        }
    }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)).padding(2.dp)) {
        ModeOption("模拟", "模拟模式：模拟的车和家", selected = !mode.realNav) { choose(false) }
        ModeOption("真实", "真实模式：用设备定位导航", selected = mode.realNav) { choose(true) }
    }
}

@Composable
private fun ModeOption(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) UnitColors.panel else Color.White,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) UnitColors.teal else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
            }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
