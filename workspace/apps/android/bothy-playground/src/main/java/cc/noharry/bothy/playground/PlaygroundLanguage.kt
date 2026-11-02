package cc.noharry.bothy.playground
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * The language the head unit shows (owner 2026-11-02: 中英双语, following the system unless chosen in 设置与控制), as
 * Bothy's own AppLanguage does. What the cabin and home tell the model stays as it is; only the screens change.
 */
internal enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    CHINESE("zh-CN"),
    ENGLISH("en"),
    ;

    companion object {
        /** The choice a stored or system language tag stands for: any Chinese is [CHINESE], any English [ENGLISH]. */
        fun fromTag(tag: String?): AppLanguage = when (tag?.substringBefore('-')?.lowercase(Locale.ROOT)) {
            "zh" -> CHINESE
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}

/**
 * Keeps and applies [AppLanguage]. From Android 13 the choice is the app's own language (LocaleManager): the system keeps
 * it, offers it in its per-app language settings too, and recreates the screens on a change. Before 13 it is kept here
 * and every context the app shows text from is [wrap]ped with it; a change recreates the screen it was made on.
 */
internal object AppLanguages {
    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppLanguage.fromTag(localeManager(context).applicationLocales.get(0)?.toLanguageTag())
        } else {
            AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null))
        }

    fun set(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(context).applicationLocales =
                if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            return
        }
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
        val locale = chosenLocale(context) ?: systemLocale()
        Locale.setDefault(locale)
        val resources = context.applicationContext.resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(Configuration(resources.configuration).apply { setLocale(locale) }, resources.displayMetrics)
        context.findActivity()?.recreate()
    }

    /** [base] showing the chosen language, before Android 13; from 13 on the system applies it and [base] is returned. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = chosenLocale(base) ?: return base
        Locale.setDefault(locale)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }

    private fun chosenLocale(context: Context): Locale? =
        AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null)).takeIf { it != AppLanguage.SYSTEM }
            ?.let { Locale.forLanguageTag(it.tag) }

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales.get(0)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun localeManager(context: Context): LocaleManager = context.getSystemService(LocaleManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "app_language"
    private const val KEY_LANGUAGE = "language"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
