package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugContextWindowConfigTest {
    @Test fun confirmedDeepSeekAliasesUseOneMillionAndOthersUseEditableDefault() {
        assertEquals(1_000_000L, defaultDebugContextWindow("deepseek-flash"))
        assertEquals(1_000_000L, defaultDebugContextWindow("cline-pass/deepseek-v4.1-flash"))
        assertEquals(128_000L, defaultDebugContextWindow("deepseek-chat"))
        assertEquals(128_000L, defaultDebugContextWindow("unknown-model"))
    }

    @Test fun windowOverridePersistsPerProfileModelAndEndpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("debug_context_windows", Context.MODE_PRIVATE).edit().clear().commit()
        val profile = DebugProviderEditState("", "custom", "", "https://example.test", profileId = "test")
        assertEquals(128_000L, loadDebugContextWindow(context, profile))
        saveDebugContextWindow(context, profile, 475_000)
        assertEquals(475_000L, loadDebugContextWindow(context, profile))
        assertEquals(128_000L, loadDebugContextWindow(context, profile.copy(model = "another")))
        assertEquals(128_000L, loadDebugContextWindow(context, profile.copy(profileId = "other")))
        assertEquals(128_000L, loadDebugContextWindow(context, profile.copy(baseUrl = "https://other.test")))
        assertThrows(IllegalArgumentException::class.java) { saveDebugContextWindow(context, profile, 0) }
        assertEquals(475_000L, loadDebugContextWindow(context, profile))
    }
    @Test fun emptyOverrideRestoresDefaultAndCapacityParserRejectsInvalidValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = DebugProviderEditState("", "deepseek-flash", "", "https://reset.test", profileId = "reset")
        saveDebugContextWindow(context, profile, 64_000)
        saveDebugContextWindow(context, profile, null)
        assertEquals(1_000_000L, loadDebugContextWindow(context, profile))
        assertNull(contextWindowOverride(context, profile))
        assertEquals(128_000L, parseContextWindow("128K"))
        assertEquals(1_500_000L, parseContextWindow("1.5m"))
        listOf("", "0", "-1", "abc", "0.0001K", "999999999999999999999M").forEach { assertNull(parseContextWindow(it)) }
    }

}
