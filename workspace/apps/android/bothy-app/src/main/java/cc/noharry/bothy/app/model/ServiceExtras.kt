package cc.noharry.bothy.app.model
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import cc.noharry.agent.appserver.protocol.ServiceBalanceParams
import cc.noharry.agent.appserver.protocol.ServiceCheckParams
import cc.noharry.agent.appserver.protocol.ServiceCheckPart
import cc.noharry.agent.appserver.protocol.ServiceCheckView
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.balanceWords
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.words

/**
 * What a service answers beyond chat, as `services/list` says it does: on a service with a balance ([ServiceView.balance]),
 * its balance; on a saved profile at an address no service is known at (a relay), each relay check
 * ([ServiceView.relayCheck]) of what that service's own API is reachable through it. DeepSeek is the one with both today.
 */
@Composable
internal fun ServiceExtras(form: ProviderFormState, isNew: Boolean, profileId: String, services: List<ServiceView>) {
    val on = services.named(form.service)
    when {
        on?.balance == true -> ServiceBalanceRow(
            on.label,
            ServiceBalanceParams(on.id, apiKey = form.apiKey, connection = form.probeConnection(profileId).takeIf { form.hasSavedKey }),
        )
        !isNew && on == null -> services.filter { it.relayCheck }.forEach { RelayCheckRow(form, profileId, it) }
    }
}

/**
 * A service key's balance, asked on tap with the key typed in the form, else the one saved for the profile; or a
 * relay's, at the balance path a check found ([RelayCheckRow]), with the key the server has for the connection.
 */
@Composable
private fun ServiceBalanceRow(service: String, ask: ServiceBalanceParams) {
    val server = LocalServer.current
    val resources = LocalResources.current
    var asking by remember { mutableStateOf(false) }
    var shown by remember(ask) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Text(
            shown.ifBlank { stringResource(R.string.model_service_balance_hint, service) },
            style = ConsoleType.meta,
            color = if (shown.isBlank()) ConsoleColors.muted else ConsoleColors.text,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(enabled = !asking && (ask.connection != null || ask.apiKey.isNotBlank()), onClick = {
            asking = true
            server.send(
                ServiceRequests.BALANCE,
                ask,
                onRefused = { shown = it.message.orEmpty(); asking = false },
            ) { answer ->
                shown = answer.balance?.let { resources.getString(R.string.model_balance, resources.balanceWords(it)) }
                    ?: answer.failure?.let(resources::words).orEmpty()
                asking = false
            }
        }) { Text(stringResource(if (asking) R.string.model_balance_checking else R.string.model_balance_check)) }
    }
}

/**
 * A profile at an address no known service is at, that may have [service] behind it (a bench relay, a gateway on an
 * official key): 检测 checks the address and key in the form and, for this profile, unlocks what passed. The runtime goes
 * by the saved profile, so a check of unsaved values counts once they are saved; one made for other values shows as out
 * of date.
 */
@Composable
private fun RelayCheckRow(form: ProviderFormState, profileId: String, service: ServiceView) {
    val server = LocalServer.current
    var checking by remember { mutableStateOf(false) }
    var probe by remember(profileId) { mutableStateOf<ServiceCheckView?>(null) }
    val connection = form.probeConnection(profileId)
    // The stored check, measured against the connection as now typed.
    LaunchedEffect(connection) { server.send(ServiceRequests.CHECKED, ServiceCheckParams(service.id, connection)) { probe = it } }
    val current = probe?.takeIf { it.current }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            RelayCheckTitle(service.label, probe, current != null, Modifier.weight(1f))
            OutlinedButton(enabled = !checking && form.baseUrl.isNotBlank() && probe?.hasKey == true && probe?.savedKeyRefused != true, onClick = {
                checking = true
                server.send(
                    ServiceRequests.CHECK,
                    ServiceCheckParams(service.id, connection),
                    onRefused = { checking = false },
                ) { probe = it; checking = false }
            }) { Text(stringResource(if (checking) R.string.model_probe_checking else R.string.model_probe_check)) }
        }
        current?.let { result -> RelayCheckResult(result, service, ServiceBalanceParams(service.id, connection = connection)) }
    }
}

@Composable
private fun RelayCheckTitle(service: String, probe: ServiceCheckView?, current: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.model_relay_features, service), style = ConsoleType.body, color = ConsoleColors.text)
        Text(
            when {
                probe?.savedKeyRefused == true -> stringResource(R.string.model_saved_key_elsewhere)
                probe?.checked != true -> stringResource(R.string.model_relay_probe_intro, service)
                !current -> stringResource(R.string.model_relay_probe_stale)
                else -> stringResource(R.string.model_relay_probe_current)
            },
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
    }
}

/** What a current check found, part by part, and the relay's balance where its balance path passed. */
@Composable
private fun RelayCheckResult(result: ServiceCheckView, service: ServiceView, balance: ServiceBalanceParams) {
    result.balance?.let { ProbeLine(stringResource(R.string.model_probe_balance), it) }
    result.lookup?.let { ProbeLine(stringResource(R.string.model_probe_tools), it) }
    result.nativeUpdates?.let { ProbeLine(stringResource(R.string.model_probe_passthrough), it) }
        ?: Text(
            stringResource(R.string.model_probe_passthrough_messages_only),
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
    result.balance?.url?.let { ServiceBalanceRow(service.label, balance.copy(url = it)) }
}

@Composable
private fun ProbeLine(name: String, check: ServiceCheckPart) {
    Text(
        stringResource(
            if (check.passed) R.string.model_probe_line_passed else R.string.model_probe_line_failed, name, words(check.note),
        ),
        style = ConsoleType.meta,
        color = if (check.passed) ConsoleColors.success else ConsoleColors.danger,
    )
}
