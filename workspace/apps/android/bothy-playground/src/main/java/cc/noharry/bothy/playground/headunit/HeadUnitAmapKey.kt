package cc.noharry.bothy.playground.headunit
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.BuildConfig
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.link.open
import cc.noharry.bothy.playground.nav.AmapKey

/**
 * In the map's place while there is no AMap key: what it is for and the way to one; the rest of the head unit (the car,
 * the home, 小博) works without it.
 */
@Composable
internal fun AmapKeyPrompt(modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.background(UnitColors.panel), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.headunit_amap_key_needed),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            val free = stringResource(R.string.headunit_amap_key_free)
            Text(free, color = UnitColors.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            TextButton(onClick = { open = true }) {
                Text(stringResource(R.string.headunit_amap_key_enter), color = UnitColors.teal, fontSize = 15.sp)
            }
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
        AmapKey.saved(context).isNotEmpty() -> stringResource(R.string.headunit_amap_key_entered)
        BuildConfig.AMAP_CONFIGURED -> stringResource(R.string.headunit_amap_key_built_in)
        else -> stringResource(R.string.headunit_amap_key_missing)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.headunit_amap_key), color = PlaygroundColors.text, fontSize = 14.sp)
            Text(state, color = UnitColors.muted, fontSize = 12.sp)
        }
        val action =
            if (AmapKey.configured(context)) R.string.headunit_amap_key_change else R.string.headunit_amap_key_fill
        TextButton(onClick = { open = true }) { Text(stringResource(action)) }
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
    var problem by remember { mutableStateOf<Int?>(null) }
    val sha1 = remember { AmapKey.sha1(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.headunit_amap_key_enter)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Step(stringResource(R.string.headunit_amap_key_step1))
                TextButton(onClick = { openUrl(context, AmapKey.CONSOLE_URL) }) { Text(stringResource(R.string.headunit_amap_key_open_console)) }
                Step(stringResource(R.string.headunit_amap_key_step2))
                Step(stringResource(R.string.headunit_amap_key_step3))
                CopyLine(context, "PackageName", AmapKey.packageName(context))
                val sha1Shown = sha1.ifEmpty { stringResource(R.string.headunit_amap_key_no_signature) }
                CopyLine(context, stringResource(R.string.headunit_amap_key_sha1), sha1Shown)
                Step(stringResource(R.string.headunit_amap_key_step4))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; problem = null },
                    label = { Text(stringResource(R.string.headunit_amap_key_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(stringResource(it), color = Color(0xFFE5483C), fontSize = 13.sp) }
                Text(stringResource(R.string.headunit_amap_key_restart_note), color = UnitColors.muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank(), onClick = {
                problem = AmapKey.save(context, key)
                if (problem == null) (context as? Activity)?.let(AmapKey::restart)
            }) { Text(stringResource(R.string.headunit_amap_key_save_restart)) }
        },
        dismissButton = {
            Row {
                if (AmapKey.saved(context).isNotEmpty()) {
                    TextButton(onClick = {
                        AmapKey.clear(context)
                        (context as? Activity)?.let(AmapKey::restart)
                    }) { Text(stringResource(R.string.headunit_amap_key_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
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
        TextButton(onClick = { copy(context, label, value) }) { Text(stringResource(R.string.common_copy)) }
    }
}

private fun copy(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, context.getString(R.string.headunit_copied_label, label), Toast.LENGTH_SHORT).show()
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
