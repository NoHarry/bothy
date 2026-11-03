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
import cc.noharry.bothy.app.console.ui.UiScale

/**
 * 设置 → 通用 → 界面布局: which front end the app shows over the one runtime, the phone's screens
 * ([cc.noharry.bothy.app.phone.PhoneApp]) or the console ([ConsoleScreen]). Picked by the screen until the person
 * chooses. The two also behave differently outside the app: a phone has no resting 小博 over other apps
 * ([cc.noharry.bothy.app.overlay.OverlayPhoneRest]).
 */
internal object FrontEnd {
    enum class Choice { AUTO, PHONE, CONSOLE }

    var choice by mutableStateOf(Choice.AUTO)
        private set
    @Volatile private var loaded = false

    fun load(context: Context) {
        val saved = prefs(context).getString(KEY, null)
        choice = Choice.entries.firstOrNull { it.name == saved } ?: Choice.AUTO
        loaded = true
    }

    fun set(context: Context, next: Choice) {
        prefs(context).edit().putString(KEY, next.name).apply()
        choice = next
        loaded = true
    }

    /** Whether [context]'s screen shows the phone's screens: a phone's by default, or what the person chose. */
    fun phone(context: Context): Boolean {
        if (!loaded) load(context)
        return resolve(choice, UiScale.phone(context))
    }

    /** As [phone], for a screen already known to be a phone's or not ([phoneScreen]); reads the choice as state. */
    fun phone(phoneScreen: Boolean): Boolean = resolve(choice, phoneScreen)

    internal fun resolve(choice: Choice, phoneScreen: Boolean): Boolean = when (choice) {
        Choice.AUTO -> phoneScreen
        Choice.PHONE -> true
        Choice.CONSOLE -> false
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "console_appearance"
    private const val KEY = "front_end"
}

/** The layout as the console's settings list it, beside the theme. */
@Composable
internal fun SettingsLayoutGroup() {
    val context = LocalContext.current
    val selected = FrontEnd.choice
    SettingsGroup(title = stringResource(R.string.settings_layout), subtitle = stringResource(R.string.settings_layout_note)) {
        Column(Modifier.padding(Dimens.spaceSm), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            FrontEnd.Choice.entries.forEach { option ->
                SelectableOptionRow(
                    label = stringResource(option.labelRes()),
                    selected = option == selected,
                    onClick = { FrontEnd.set(context, option) },
                )
            }
        }
    }
}

internal fun FrontEnd.Choice.labelRes(): Int = when (this) {
    FrontEnd.Choice.AUTO -> R.string.settings_layout_auto
    FrontEnd.Choice.PHONE -> R.string.settings_layout_phone
    FrontEnd.Choice.CONSOLE -> R.string.settings_layout_console
}
