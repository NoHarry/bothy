package cc.noharry.bothy.app.console

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ui.SelectableOptionRow

/** 设置 → 通用 → 主题. Follows the system until the person picks light or dark. */
internal object ConsoleAppearance {
    enum class Mode { SYSTEM, LIGHT, DARK }

    var current by mutableStateOf(Mode.SYSTEM)
        private set

    fun load(context: Context) {
        val prefs = prefs(context)
        val saved = prefs.getString(KEY, null)
            ?: context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current = Mode.entries.firstOrNull { it.name == saved } ?: Mode.SYSTEM
        if (saved != null && !prefs.contains(KEY)) prefs.edit().putString(KEY, current.name).apply()
    }

    fun set(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY, mode.name).apply()
        current = mode
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "console_appearance"
    private const val LEGACY_PREFS = "debug_appearance"
    private const val KEY = "theme"
}

@Composable
internal fun SettingsThemeGroup() {
    val context = LocalContext.current
    val selected = ConsoleAppearance.current
    SettingsGroup(title = stringResource(R.string.settings_theme), subtitle = stringResource(R.string.settings_theme_note)) {
        Column(Modifier.padding(Dimens.spaceSm), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            ConsoleAppearance.Mode.entries.forEach { option ->
                SelectableOptionRow(
                    label = option.label(),
                    selected = option == selected,
                    onClick = { ConsoleAppearance.set(context, option) },
                )
            }
        }
    }
}

@Composable
private fun ConsoleAppearance.Mode.label(): String = when (this) {
    ConsoleAppearance.Mode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ConsoleAppearance.Mode.LIGHT -> stringResource(R.string.settings_theme_light)
    ConsoleAppearance.Mode.DARK -> stringResource(R.string.settings_theme_dark)
}
