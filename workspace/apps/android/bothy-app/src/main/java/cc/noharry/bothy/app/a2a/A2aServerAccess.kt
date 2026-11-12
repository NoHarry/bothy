package cc.noharry.bothy.app.a2a

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.frontend.LocalServer

/** The incoming server's credential stays in this page's memory and is fetched only on reveal. */
@Composable
internal fun A2aServerAccess(modifier: Modifier = Modifier) {
    val server = LocalServer.current
    val context = LocalContext.current
    var token by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(stringResource(R.string.a2a_server_title), style = ConsoleType.body, color = ConsoleColors.text)
        Text(stringResource(R.string.a2a_server_hint), style = ConsoleType.meta, color = ConsoleColors.muted)
        token?.let { Text(it, style = ConsoleType.meta, fontFamily = FontFamily.Monospace, color = ConsoleColors.text) }
        if (failed) Text(stringResource(R.string.a2a_server_read_failed), style = ConsoleType.meta, color = ConsoleColors.danger)
        Row {
            TextButton(enabled = token != null || (!busy && server.state.connected), onClick = {
                copied = false
                if (token != null) token = null else {
                    busy = true
                    failed = false
                    server.send(SettingsRequests.A2A_SERVER_TOKEN, Empty, onRefused = { busy = false; failed = true }) {
                        token = it.text
                        busy = false
                    }
                }
            }) {
                Text(stringResource(if (busy) R.string.common_loading else if (token == null) R.string.a2a_server_show else R.string.a2a_server_hide))
            }
            TextButton(enabled = token != null, onClick = {
                token?.let { copied = copyA2aServerToken(context, context.getString(R.string.a2a_server_title), it) }
            }) {
                Text(stringResource(if (copied) R.string.a2a_server_copied else R.string.a2a_server_copy))
            }
        }
    }
}

/** Marks clipboard content sensitive so Android does not preview the credential. */
internal fun copyA2aServerToken(context: Context, label: String, token: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val clip = ClipData.newPlainText(label, token)
    clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE_CLIPBOARD_KEY, true) }
    clipboard.setPrimaryClip(clip)
    return true
}

private const val SENSITIVE_CLIPBOARD_KEY = "android.content.extra.IS_SENSITIVE"
