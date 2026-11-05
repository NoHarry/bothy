package cc.noharry.bothy.server.voice

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * The language the person chose for the screens, read for what the server shows itself (the microphone notification).
 * The app keeps and changes the choice (console/AppLanguage.kt): from Android 13 the system applies it to every
 * context; before 13 it is in the app's preferences, read here the same way.
 */
internal object ScreenLanguage {
    /** [base] showing the chosen language, before Android 13; from 13 on [base] already does. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
            ?.takeIf { it.isNotBlank() } ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }

    // The app's own store for the choice before Android 13 (AppLanguages).
    private const val PREFS = "app_language"
    private const val KEY_LANGUAGE = "language"
}
