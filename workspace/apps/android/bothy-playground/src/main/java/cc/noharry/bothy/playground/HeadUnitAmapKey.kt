package cc.noharry.bothy.playground

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * In the map's place while there is no AMap key: what it is for and the way to one; the rest of the head unit (the car,
 * the home, 小博) works without it.
 */
@Composable
internal fun AmapKeyPrompt(modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.background(UnitColors.panel), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("地图和导航需要你自己的高德 key", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("免费申请，大约 5 分钟。虚拟车、虚拟家和小博现在就能用。", color = UnitColors.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            TextButton(onClick = { open = true }) { Text("填写高德 key", color = UnitColors.teal, fontSize = 15.sp) }
        }
    }
    if (open) AmapKeyDialog { open = false }
}

/** The settings sheet's row: whose key the head unit uses, and the dialog to type or change it. */
@Composable
internal fun AmapKeyRow() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val state = when {
        AmapKey.saved(context).isNotEmpty() -> "已填写"
        BuildConfig.AMAP_CONFIGURED -> "用打包时的 key"
        else -> "未填写：地图和导航不可用"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("高德 key", color = PlaygroundColors.text, fontSize = 14.sp)
            Text(state, color = UnitColors.muted, fontSize = 12.sp)
        }
        TextButton(onClick = { open = true }) { Text(if (AmapKey.configured(context)) "更换" else "填写") }
    }
    if (open) AmapKeyDialog { open = false }
}

/**
 * How to get a key, step by step, with what the console asks for (the package and the SHA1 of this APK's signature)
 * ready to copy, and the field for the key; saved, the head unit starts again to use it.
 */
@Composable
internal fun AmapKeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val sha1 = remember { AmapKey.sha1(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写高德 key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Step("1. 打开高德开放平台控制台并登录（个人开发者就行）。")
                TextButton(onClick = { openUrl(context, AmapKey.CONSOLE_URL) }) { Text("打开高德控制台 ↗") }
                Step("2. 「应用管理」→「我的应用」→ 创建新应用，再「添加 Key」：服务平台选「Android 平台」。")
                Step("3. 按下面填写（发布版和调试版安全码都填这个 SHA1）：")
                CopyLine(context, "PackageName", AmapKey.packageName(context))
                CopyLine(context, "安全码 SHA1", sha1.ifEmpty { "读不到签名" })
                Step("4. 提交后把生成的 Key 粘贴到这里：")
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; problem = null },
                    label = { Text("高德 Android key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, color = Color(0xFFE5483C), fontSize = 13.sp) }
                Text("保存后车机会重新启动一次。", color = UnitColors.muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank(), onClick = {
                problem = AmapKey.save(context, key)
                if (problem == null) (context as? Activity)?.let(AmapKey::restart)
            }) { Text("保存并重启") }
        },
        dismissButton = {
            Row {
                if (AmapKey.saved(context).isNotEmpty()) {
                    TextButton(onClick = {
                        AmapKey.clear(context)
                        (context as? Activity)?.let(AmapKey::restart)
                    }) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun Step(text: String) {
    Text(text, fontSize = 14.sp)
}

@Composable
private fun CopyLine(context: Context, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = UnitColors.muted)
            Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = { copy(context, label, value) }) { Text("复制") }
    }
}

private fun copy(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
