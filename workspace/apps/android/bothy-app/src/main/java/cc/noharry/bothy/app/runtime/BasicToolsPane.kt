package cc.noharry.bothy.app.runtime
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.AndroidUseState
import cc.noharry.agent.appserver.protocol.BasicToolView
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.NameEnabledParams
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess

@Composable
internal fun BasicToolsPane() {
    val server = LocalServer.current
    Column(
        Modifier.widthIn(max = 880.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        Text(stringResource(R.string.runtime_basic_tools_note), color = ConsoleColors.muted)
        AndroidUseRow(server)
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        server.state.preferences.basicTools.forEach { tool ->
            BasicToolRow(tool) { enabled -> server.send(CapabilityRequests.SET_BASIC_TOOL, NameEnabledParams(tool.name, enabled)) }
            HorizontalDivider(color = ConsoleColors.borderSubtle)
        }
    }
}

/** 操作屏幕: Android Use tools (android_observe / android_action) over the accessibility service. */
@Composable
private fun AndroidUseRow(server: ServerAccess) {
    val use = server.state.preferences.androidUse
    BasicToolRow(
        BasicToolView(stringResource(R.string.runtime_android_use), stringResource(R.string.runtime_android_use_description), use.enabled),
        stateText = stringResource(use.state.shown),
    ) { checked -> server.send(CapabilityRequests.SET_ANDROID_USE, FlagParams(checked)) }
}

@Composable
private fun BasicToolRow(tool: BasicToolView, stateText: String? = null, onToggle: (Boolean) -> Unit) {
    val switchLabel = stringResource(R.string.runtime_tool_switch, tool.name)
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceMd), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tool.name, style = ConsoleType.body, color = ConsoleColors.text)
            Text(tool.description, style = ConsoleType.meta, color = ConsoleColors.muted)
            val state = when {
                tool.required -> R.string.runtime_tool_always_on
                tool.enabled -> R.string.runtime_tool_enabled
                else -> R.string.runtime_tool_disabled
            }
            Text(stateText ?: stringResource(state), color = ConsoleColors.muted)
        }
        Switch(
            checked = tool.enabled,
            onCheckedChange = onToggle,
            enabled = !tool.required,
            modifier = Modifier.semantics { contentDescription = switchLabel },
            colors = switchColors(),
        )
    }
}

/** Where operating the screen stands, as the row says it. */
@get:StringRes
private val AndroidUseState.shown: Int
    get() = when (this) {
        AndroidUseState.OFF -> R.string.androiduse_status_off
        AndroidUseState.CONNECTED -> R.string.androiduse_status_connected
        AndroidUseState.WAITING -> R.string.androiduse_status_waiting
        AndroidUseState.FAILED -> R.string.androiduse_status_failed
    }
