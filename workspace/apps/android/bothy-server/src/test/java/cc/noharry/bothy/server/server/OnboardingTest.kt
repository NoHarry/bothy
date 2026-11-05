package cc.noharry.bothy.server.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.server.voice.loadVoiceConfig
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun finishingSetupLeavesTheWakeWordOff() {
        Onboarding.finish(context)
        // As a released build: a local build may package VOICE_OVERLAY_WAKE=on, which keeps the wake word on.
        assertFalse(loadVoiceConfig(context) { emptyMap() }.overlayWake)
    }
}
