package cc.noharry.bothy.app.a2a

import android.app.Application
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class A2aServerAccessTest {
    @Test fun copiedCredentialIsMarkedSensitive() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(copyA2aServerToken(context, "A2A access token", "test-a2a-token"))
        val clip = requireNotNull(context.getSystemService(ClipboardManager::class.java).primaryClip)
        assertEquals("test-a2a-token", clip.getItemAt(0).text.toString())
        assertTrue(requireNotNull(clip.description.extras).getBoolean("android.content.extra.IS_SENSITIVE"))
    }
}
