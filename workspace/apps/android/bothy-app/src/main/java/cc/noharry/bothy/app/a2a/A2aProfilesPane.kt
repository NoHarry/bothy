package cc.noharry.bothy.app.a2a
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsChildPanel
import cc.noharry.bothy.app.console.SettingsNavBar
import cc.noharry.bothy.app.console.SettingsStatusChip
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import cc.noharry.bothy.app.session.UiState

/** Two-step navigation inside the A2A clients page: profile list, then the add-service form. */
internal enum class A2aProfilesPage {
    LIST,
    ADD,
}

internal data class A2aProfilesNavigationState(
    val page: A2aProfilesPage = A2aProfilesPage.LIST,
) {
    fun openAdd(): A2aProfilesNavigationState = copy(page = A2aProfilesPage.ADD)

    fun back(): A2aProfilesNavigationState? =
        when (page) {
            A2aProfilesPage.LIST -> null
            A2aProfilesPage.ADD -> A2aProfilesNavigationState()
        }

    fun title(): String =
        when (page) {
            A2aProfilesPage.LIST -> "A2A clients"
            A2aProfilesPage.ADD -> "Add A2A service"
        }

    fun breadcrumb(): String =
        when (page) {
            A2aProfilesPage.LIST -> "Settings › A2A"
            A2aProfilesPage.ADD -> "Settings › A2A › Add"
        }
}

@Composable
internal fun A2aProfilesPane(
    state: UiState,
    actions: ConsoleActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    var pageName by rememberSaveable { mutableStateOf(A2aProfilesPage.LIST.name) }
    val navigation = A2aProfilesNavigationState(A2aProfilesPage.valueOf(pageName))
    // Same chrome rule as Settings: Back on the left; nested steps go up one level first.
    val goBack = {
        val parent = navigation.back()
        if (parent == null) onBack() else pageName = parent.page.name
    }
    SettingsChildPanel(modifier, embedded) {
        if (!embedded || navigation.page == A2aProfilesPage.ADD) {
        SettingsNavBar(
            title = navigation.title(),
            breadcrumb = navigation.breadcrumb(),
            backLabel = "Back",
            onBack = goBack,
            hint = "Toggles apply immediately · custom services persist on this device.",
        )
        Spacer(Modifier.height(Dimens.spaceSm))
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        Spacer(Modifier.height(Dimens.spaceMd))
        }
        when (navigation.page) {
            A2aProfilesPage.LIST -> A2aProfileListContent(
                state = state.a2aClients,
                actions = actions,
                onAdd = { pageName = navigation.openAdd().page.name },
                modifier = Modifier.weight(1f),
            )
            A2aProfilesPage.ADD -> A2aProfileAddForm(
                actions = actions,
                onDone = { pageName = A2aProfilesPage.LIST.name },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun A2aProfileListContent(
    state: A2aModuleState,
    actions: ConsoleActions,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Column(modifier) {
        // The add button leads the row: the 小博 bubble rests on the top-right corner of the screen and covered it there.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAdd) { Text("+ Add A2A service") }
            Text(
                "${state.enabledProfileIds.size} of ${state.profiles.size} enabled · " +
                    "${state.loadedModuleIds.size} loaded · ${state.errorsByProfileId.size} errors",
                style = ConsoleType.meta,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        Surface(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = A2A_PROVIDERS_CONTENT_MAX_WIDTH),
            shape = RoundedCornerShape(Dimens.radiusMd),
            color = colors.panel,
            border = BorderStroke(1.dp, colors.borderSubtle),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            LazyColumn {
                if (state.profiles.isEmpty()) {
                    item(key = "a2a-empty") {
                        Text(
                            state.error ?: "No A2A client profiles",
                            style = ConsoleType.meta,
                            color = if (state.error != null) colors.danger else colors.muted,
                            modifier = Modifier.padding(Dimens.spaceLg),
                        )
                    }
                }
                state.profiles.forEachIndexed { index, profile ->
                    item(key = profile.id) {
                        if (index > 0) {
                            HorizontalDivider(
                                color = colors.borderSubtle,
                                modifier = Modifier.padding(horizontal = Dimens.spaceMd),
                            )
                        }
                        A2aProfileRow(profile, state, actions)
                    }
                }
            }
        }
    }
}

/**
 * Toggle row aligned with the Settings row family: body title, meta subtitle,
 * status chip, trailing switch. User profiles additionally get a Delete action;
 * packaged asset profiles are not deletable.
 */
@Composable
private fun A2aProfileRow(
    profile: A2aProfile,
    state: A2aModuleState,
    actions: ConsoleActions,
) {
    val colors = ConsoleColors
    val loaded = profile.id in state.loadedModuleIds
    val profileError = state.errorsByProfileId[profile.id]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = A2A_ROW_HEIGHT)
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        A2aProfileInfo(profile, profileError, profile.id in state.tokenProfileIds, Modifier.weight(1f))
        Spacer(Modifier.width(Dimens.spaceMd))
        if (profile.userDefined) {
            TextButton(onClick = { actions.deleteA2aProfile(profile.id) }) {
                Text("Delete", color = colors.danger)
            }
            Spacer(Modifier.width(Dimens.spaceXs))
        }
        SettingsStatusChip(
            label = a2aProfileStatusLabel(profile, loaded, profileError),
            active = loaded,
            danger = profileError != null,
        )
        Spacer(Modifier.width(Dimens.spaceSm))
        Switch(
            checked = profile.enabled,
            onCheckedChange = { enabled -> actions.setA2aProfileEnabled(profile.id, enabled) },
            colors = switchColors(),
        )
    }
}

@Composable
private fun A2aProfileInfo(
    profile: A2aProfile,
    error: String?,
    hasToken: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Column(modifier) {
        Text(profile.label, style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(profile.moduleId, profile.endpoint.toString(), "token set".takeIf { hasToken }).joinToString(" · "),
            style = ConsoleType.meta,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (profile.description.isNotEmpty()) {
            Text(profile.description, style = ConsoleType.meta, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (error != null) {
            Text(error, style = ConsoleType.meta, color = colors.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun a2aProfileStatusLabel(profile: A2aProfile, loaded: Boolean, error: String?): String = when {
    loaded -> "Loaded"
    error != null -> "Error"
    profile.enabled -> "Enabled"
    else -> "Disabled"
}

/**
 * Add-service form: label, endpoint, packaged module picker and the loopback
 * debug switch. Validation lives in [A2aModuleConfig.saveUserProfile];
 * failures surface inline without leaving the form.
 */
@Composable
private fun A2aProfileAddForm(
    actions: ConsoleActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    val moduleIds = remember { A2aModuleRegistry.packaged().moduleIds().toList() }
    var label by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var moduleId by rememberSaveable { mutableStateOf(moduleIds.firstOrNull().orEmpty()) }
    var allowLoopback by rememberSaveable { mutableStateOf(DEFAULT_ALLOW_LOOPBACK) }
    var description by rememberSaveable { mutableStateOf("") }
    // Not saveable: the token must not outlive the form in saved instance state.
    var token by remember { mutableStateOf("") }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.widthIn(max = A2A_PROVIDERS_CONTENT_MAX_WIDTH)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            A2aFormField("Name", label) { label = it }
            A2aFormField("Endpoint URL", endpoint) { endpoint = it }
            A2aFormField("What it can do (shown to the model)", description) { description = it }
            A2aFormField("Access token (optional, stored on this device only)", token, secret = true) { token = it }
            A2aModulePicker(moduleIds, moduleId) { moduleId = it }
            A2aLoopbackSwitchRow(allowLoopback) { allowLoopback = it }
            saveError?.let {
                Text(it, style = ConsoleType.meta, color = colors.danger)
            }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        HorizontalDivider(color = colors.border)
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Spacer(Modifier.width(Dimens.spaceSm))
            Button(
                onClick = {
                    val error = actions.saveA2aProfile(A2aProfileDraft(label, moduleId, endpoint, allowLoopback, description, token))
                    if (error == null) onDone() else saveError = error
                },
                shape = RoundedCornerShape(Dimens.radiusMd),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun A2aFormField(
    label: String,
    value: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = outlinedTextFieldColors(),
    )
}

/** Packaged-module picker: adapters only come from the APK registry, so this is a fixed option list. */
@Composable
private fun A2aModulePicker(
    moduleIds: List<String>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text("Module", style = ConsoleType.meta, color = ConsoleColors.muted)
        moduleIds.forEach { option ->
            SelectableOptionRow(
                label = option,
                selected = option == selectedId,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun A2aLoopbackSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Allow loopback HTTP for debug", style = ConsoleType.body, color = colors.text)
            Text(
                "Required for http:// or 127.0.0.1 / localhost endpoints",
                style = ConsoleType.meta,
                color = colors.muted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = switchColors(),
        )
    }
}

private val A2A_ROW_HEIGHT = 56.dp
private val A2A_PROVIDERS_CONTENT_MAX_WIDTH = 920.dp
private const val DEFAULT_ALLOW_LOOPBACK = false
