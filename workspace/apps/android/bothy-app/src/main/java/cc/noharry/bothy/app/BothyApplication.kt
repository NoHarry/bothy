package cc.noharry.bothy.app
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import cc.noharry.bothy.app.console.AppLanguages
import cc.noharry.bothy.app.console.ConsoleAppearance
import cc.noharry.bothy.app.console.DisplaySettings
import cc.noharry.bothy.app.overlay.HostConnection
import cc.noharry.bothy.server.server.BothyServer
import java.util.Locale

/** The app: its screens' settings, the overlay as the server's presentation, and the server that runs in its process. */
class BothyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguages.wrap(base))
    }

    private var shownLocale: Locale? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A change of the screens' language (Settings → General, or the system's) rewords what the loaded config names.
        // The app's own resources tell it: before Android 13 they keep the chosen language when the system's changes.
        val locale = resources.configuration.locales.get(0)
        if (shownLocale != null && locale != shownLocale) HostConnection.languageChanged()
        shownLocale = locale
    }

    override fun onCreate() {
        super.onCreate()
        shownLocale = resources.configuration.locales.get(0)
        ConsoleAppearance.load(this)
        DisplaySettings.init(this)
        BothyServer.install(this)
        // The overlay presents the server on this screen: what the server asks to come up, show and wake, over the
        // protocol like any front end.
        HostConnection.start(this)
    }
}
