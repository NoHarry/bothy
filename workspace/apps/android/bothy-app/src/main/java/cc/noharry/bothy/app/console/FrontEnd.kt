package cc.noharry.bothy.app.console

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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
 * chooses: a phone, or a tablet held upright, gets the phone's screens; a head unit keeps the console, upright or not.
 * The two also behave differently outside the app: a phone has no resting 小博 over other apps
 * ([cc.noharry.bothy.app.phoneassist.PhoneAssistant]).
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

    /**
     * Whether this device behaves as a phone outside the app, with no resting 小博: a phone does by default, or as the
     * person chose. A tablet held upright shows the phone's screens in the app but stays a tablet here.
     */
    fun phone(context: Context): Boolean {
        if (!loaded) load(context)
        return resolve(choice, UiScale.phone(context))
    }

    /**
     * Whether the app shows the phone's screens on a screen already known to be a phone's or not ([phoneScreen]), held
     * [upright] or not: left to the screen, a tablet held upright shows them too. Reads the choice as state.
     */
    fun phone(phoneScreen: Boolean, upright: Boolean): Boolean = resolve(choice, phoneScreen, upright)

    /**
     * Whether the screen is upright the way a tablet's is: higher than wide, and not a head unit's (some car screens
     * are upright, or turn).
     */
    fun upright(context: Context, configuration: Configuration): Boolean =
        configuration.screenHeightDp > configuration.screenWidthDp &&
            (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_CAR &&
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    internal fun resolve(choice: Choice, phoneScreen: Boolean, upright: Boolean = false): Boolean = when (choice) {
        Choice.AUTO -> phoneScreen || upright
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
