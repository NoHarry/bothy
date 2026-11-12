package cc.noharry.bothy.app.model
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.ui.PopupMenu
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel
import cc.noharry.bothy.app.console.ui.SelectorChip
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.usage.UsageSegmented

/** The model services the server knows (`services/list`): null until it answers, empty when it knows none or refuses. */
@Composable
internal fun rememberServices(): List<ServiceView>? {
    val server = LocalServer.current
    var services by remember { mutableStateOf<List<ServiceView>?>(null) }
    LaunchedEffect(Unit) {
        server.send(ServiceRequests.LIST, Empty, onRefused = { services = emptyList() }) { services = it.services }
    }
    return services
}

/**
 * 服务商: the services the server knows, then Custom ([CUSTOM_SERVICE]); with a service whose hosts are in more than one
 * region chosen, which region. Nothing is chosen until the person picks one.
 */
@Composable
internal fun ServicePicker(services: List<ServiceView>, form: ProviderFormState) {
    val custom = stringResource(R.string.model_provider_custom)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        SettingsChoiceMenu(
            label = stringResource(R.string.model_provider),
            selected = form.service,
            options = services.map { it.id } + CUSTOM_SERVICE,
            optionLabel = { id -> services.named(id)?.label ?: custom },
            placeholder = stringResource(R.string.model_provider_choose),
            optionHint = { id -> services.named(id)?.regions()?.takeIf { it.size > 1 }?.map { regionName(it) }?.joinToString(" · ") },
            onSelected = { id -> services.named(id)?.let { form.chooseService(it) } ?: form.chooseCustom() },
        )
        services.named(form.service)?.takeIf { it.regions().size > 1 }?.let { view -> RegionChoice(view, form) }
    }
}

@Composable
private fun RegionChoice(view: ServiceView, form: ProviderFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(stringResource(R.string.model_region), style = ConsoleType.meta, color = ConsoleColors.muted)
        UsageSegmented(view.regions(), form.region, { regionName(it) }) { form.chooseRegion(it) }
    }
}

/**
 * Which service a saved profile is on, as the server found it from its address (and in which region); Custom for none.
 * Nothing until the services are known, so a service's id never shows in place of its name.
 */
@Composable
internal fun ServiceLine(services: List<ServiceView>?, form: ProviderFormState) {
    if (services == null) return
    val view = services.named(form.service)
    val name = when {
        view != null -> listOfNotNull(view.label, form.region.takeIf { view.regions().size > 1 }?.let { regionName(it) }).joinToString(" · ")
        form.service.isNullOrBlank() -> stringResource(R.string.model_provider_custom_saved)
        else -> form.service.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
        Text(stringResource(R.string.model_provider), style = ConsoleType.meta, color = ConsoleColors.muted)
        Text(name, style = ConsoleType.body, color = ConsoleColors.text)
    }
}

/** A region of a service's hosts, as the settings name it; a code the app does not know shows as it is. */
@Composable
internal fun regionName(region: String): String = when (region) {
    REGION_CN -> stringResource(R.string.model_region_cn)
    REGION_GLOBAL -> stringResource(R.string.model_region_global)
    REGION_US -> stringResource(R.string.model_region_us)
    else -> region.uppercase()
}

/**
 * Where to get a key for [region] of [service], in the app's language (owner 2026-11-11: the service's own sentence is
 * English only): its key page for that region, else its page for every region, and, where its regions are separate
 * sites, that a key works only in its own region. A service that gives no page (another host's adapter) keeps its own
 * sentence; null when it has neither.
 */
internal fun Resources.keyHintText(service: ServiceView, region: String): String? {
    val page = service.keyPages[region] ?: service.keyPages[ANY_REGION] ?: return service.keyHint.takeIf { it.isNotBlank() }
    val bySite = service.keyPages.values.distinct().size > 1
    return getString(if (bySite) R.string.model_key_page_by_region else R.string.model_key_page, page)
}

/** Where to get a key ([keyHintText]), under the key field; its addresses open in the browser. */
@Composable
internal fun KeyHint(hint: String) {
    val link = ConsoleColors.accent
    val text = remember(hint, link) { linkedText(hint, SpanStyle(color = link, textDecoration = TextDecoration.Underline)) }
    Text(text, style = ConsoleType.meta, color = ConsoleColors.muted)
}

/** [text] with each web address in it a link, without the punctuation that closes the sentence around it. */
internal fun linkedText(text: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    var at = 0
    WEB_ADDRESS.findAll(text).forEach { match ->
        val url = match.value.trimEnd(*CLOSING_PUNCTUATION)
        append(text.substring(at, match.range.first))
        withLink(LinkAnnotation.Url(url, TextLinkStyles(style))) { append(url) }
        at = match.range.first + url.length
    }
    append(text.substring(at))
}

/** A labelled menu of [options] on a chip; [placeholder] shows while nothing is [selected]. */
@Composable
internal fun <T> SettingsChoiceMenu(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    placeholder: String = "",
    optionHint: @Composable (T) -> String? = { null },
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(label, style = ConsoleType.meta, color = ConsoleColors.muted)
        Box(Modifier.fillMaxWidth()) {
            SelectorChip(
                label = selected?.let { optionLabel(it) } ?: placeholder,
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                placeholder = selected == null,
            )
            PopupMenu(expanded = expanded, onDismissRequest = { expanded = false }, minWidth = MENU_MIN_WIDTH, maxWidth = MENU_MAX_WIDTH) {
                PopupSectionLabel(label)
                options.forEach { option ->
                    PopupMenuItem(
                        label = optionLabel(option),
                        selected = option == selected,
                        hint = optionHint(option),
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

/** Custom among the providers: the form as typed, on no service. */
internal const val CUSTOM_SERVICE = ""

/** A key page's region that stands for every region of its service (`ServiceView.keyPages`). */
private const val ANY_REGION = ""
private const val REGION_CN = "cn"
private const val REGION_GLOBAL = "global"
private const val REGION_US = "us"
private val WEB_ADDRESS = Regex("https?://\\S+")
private val CLOSING_PUNCTUATION = charArrayOf('.', ',', ';', ':', ')', '）', '。', '，', '；')
private val MENU_MIN_WIDTH = 220.dp
private val MENU_MAX_WIDTH = 320.dp
