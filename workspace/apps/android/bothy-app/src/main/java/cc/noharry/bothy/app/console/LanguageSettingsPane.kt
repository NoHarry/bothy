package cc.noharry.bothy.app.console
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ui.SelectableOptionRow

/** 设置 → 通用 → 语言: follow the system, or always Chinese or English ([AppLanguages]). */
@Composable
internal fun SettingsLanguageGroup() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguages.current(context)) }
    SettingsGroup(title = stringResource(R.string.settings_language), subtitle = stringResource(R.string.settings_language_note)) {
        Column(Modifier.padding(Dimens.spaceSm), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            AppLanguage.entries.forEach { option ->
                SelectableOptionRow(option.label(), option == language, {
                    language = option
                    AppLanguages.set(context, option)
                })
            }
        }
    }
}

/** Each language is named in itself, so it can be found from either. */
@Composable
internal fun AppLanguage.label(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
    AppLanguage.CHINESE -> "中文"
    AppLanguage.ENGLISH -> "English"
}
