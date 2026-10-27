package cc.noharry.bothy.app.console
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 显示思考过程: off until switched on (owner 2026-10-14: 「思考默认关」), and the choice survives a restart. */
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.getSharedPreferences("debug_display", Context.MODE_PRIVATE).edit().clear().commit()
        DisplaySettings.init(context)
    }

    @Test
    fun thinkingIsHiddenUntilSwitchedOn() {
        DisplaySettings.init(context)
        assertFalse(DisplaySettings.showThinking.value)
        DisplaySettings.setShowThinking(context, true)
        DisplaySettings.init(context)
        assertTrue(DisplaySettings.showThinking.value)
    }
}
