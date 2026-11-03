package cc.noharry.bothy.app.console

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.app.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsoleAppearanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun modeMapsOntoTheLightAndDarkPalettes() {
        assertEquals(LightConsoleColors, consoleColors(ConsoleAppearance.Mode.LIGHT, systemDark = true))
        assertEquals(DarkConsoleColors, consoleColors(ConsoleAppearance.Mode.DARK, systemDark = false))
        assertEquals(DarkConsoleColors, consoleColors(ConsoleAppearance.Mode.SYSTEM, systemDark = true))
        assertEquals(LightConsoleColors, consoleColors(ConsoleAppearance.Mode.SYSTEM, systemDark = false))
    }

    @Test
    fun aSavedModeIsLoadedBeforeTheFirstFrame() {
        context.getSharedPreferences("console_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        ConsoleAppearance.set(context, ConsoleAppearance.Mode.DARK)
        ConsoleAppearance.load(context)
        assertEquals(ConsoleAppearance.Mode.DARK, ConsoleAppearance.current)
    }

    @Test
    fun aLegacyPreferenceIsStillRead() {
        context.getSharedPreferences("console_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("debug_appearance", Context.MODE_PRIVATE).edit().putString("theme", "LIGHT").commit()
        ConsoleAppearance.load(context)
        assertEquals(ConsoleAppearance.Mode.LIGHT, ConsoleAppearance.current)
    }

    @Test
    fun finishingSetupLeavesTheWakeWordOff() {
        Onboarding.finish(context)
        assertFalse(cc.noharry.bothy.app.voice.loadVoiceConfig(context).overlayWake)
    }
}
